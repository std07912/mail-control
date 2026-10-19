using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace SettlementMail.Core;

public static class Units { public const long MB = 1_000_000; public static string Size(long bytes) => $"{bytes / (double)MB:N2} MB"; }
public sealed record Document(string Id, string Name, string Path, long Size, string Sha256,
    bool Selected = true, string Note = "", string Origin = "");
public sealed record Credentials(string Email, string Password);
public sealed record Settings
{
    public string Recipient { get; init; } = "";
    public string Subject { get; init; } = "정산자료";
    public string Job { get; init; } = "사후정산";
    public int AttachmentMB { get; init; } = 9;
    public int FileMB { get; init; } = 9;
    public int MessageMB { get; init; } = 13;
    public int PdfMB { get; init; } = 9;
    public int IntervalSeconds { get; init; } = 30;
    public string Extensions { get; init; } = "pdf,xls,xlsx,doc,docx,hwp,hwpx,jpg,jpeg,png";
    public bool Minimize { get; init; }
    public Policy Policy()
    {
        if (AttachmentMB is < 1 or > 500 || FileMB is < 1 or > 500 || MessageMB is < 1 or > 500)
            throw new InvalidOperationException("첨부·개별·메시지 용량은 1~500MB로 입력하세요.");
        if (PdfMB is < 1 or > 9 || IntervalSeconds is < 10 or > 86400)
            throw new InvalidOperationException("PDF 목표는 1~9MB, 발송 간격은 10~86400초입니다.");
        var extensions = Extensions.Split(',').Select(x => x.Trim().TrimStart('.').ToLowerInvariant()).Where(x => x.Length > 0).ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (extensions.Count == 0 || extensions.Any(x => !Regex.IsMatch(x, "^[a-z0-9]{1,12}$"))) throw new InvalidOperationException("승인 확장자는 쉼표로 구분해 입력하세요.");
        return new(AttachmentMB * Units.MB, FileMB * Units.MB, MessageMB * Units.MB, extensions);
    }
}
public sealed record Policy(long AttachmentBytes, long FileBytes, long MessageBytes, IReadOnlySet<string> Extensions)
{
    public bool Approved(string name) => Extensions.Contains(System.IO.Path.GetExtension(name).TrimStart('.').ToLowerInvariant());
    public string? Problem(Document file) => !Approved(file.Name) ? "승인 형식 아님" :
        file.Size > FileBytes ? "개별 파일 용량 초과" : file.Size > AttachmentBytes ? "첨부 합계 제한 초과" :
        Packing.EstimatedBytes([file]) > MessageBytes ? "전체 메시지 제한 초과" : null;
}
public static class Packing
{
    public static long EstimatedBytes(IEnumerable<Document> documents) => 8192 + documents.Sum(d =>
    {
        long chars = ((d.Size + 2) / 3) * 4;
        return chars + ((chars + 75) / 76) * 2 + 2048 + Encoding.UTF8.GetByteCount(d.Name) * 8L;
    });
    public static List<List<Document>> Group(IEnumerable<Document> documents, Policy policy, bool minimize)
    {
        var selected = documents.Where(d => d.Selected).ToList();
        var invalid = selected.FirstOrDefault(d => policy.Problem(d) != null);
        if (invalid != null) throw new InvalidOperationException($"{invalid.Name}: {policy.Problem(invalid)}. 처리하거나 선택 해제하세요.");
        IEnumerable<Document> ordered = minimize ? selected.OrderByDescending(d => d.Size) : selected;
        var groups = new List<List<Document>>();
        bool Fits(List<Document> group, Document d) => group.Sum(x => x.Size) + d.Size <= policy.AttachmentBytes && EstimatedBytes(group.Append(d)) <= policy.MessageBytes;
        foreach (var file in ordered)
        {
            var target = minimize ? groups.FirstOrDefault(g => Fits(g, file)) : groups.LastOrDefault() is { } last && Fits(last, file) ? last : null;
            if (target == null) groups.Add([file]); else target.Add(file);
        }
        return groups;
    }
    public static List<Document> ExcludeUnapproved(IEnumerable<Document> files, Policy policy) => files.Select(d => d.Selected && !policy.Approved(d.Name) ? d with { Selected = false } : d).ToList();
}
public sealed record MailRow(string Id, string Batch, string Job, int Position, int Total, string Recipient,
    string Subject, string Sender, List<Document> Files, string Eml, long MessageBytes, string EmlHash,
    int IntervalSeconds, long MessageLimit, string State = "PENDING", long Time = 0, string Response = "", string Error = "");
public static class DeliveryState
{
    public static string Failure(bool sending, bool explicitlyRejected, bool cancelled) => sending && !explicitlyRejected ? "UNCERTAIN" : cancelled ? "CANCELLED" : "FAILED";
    public static string Label(string state) => state switch {
        "PENDING" => "대기", "CONNECTING" => "연결 중", "SENDING" => "전송 중", "ACCEPTED" => "SMTP 접수 성공",
        "CONFIRMED" => "사용자 접수 확인", "FAILED" => "실패", "CANCELLED" => "취소", "UNCERTAIN" => "접수 확인 필요", _ => state };
}
public static class Csv
{
    public static string Cell(string value)
    {
        if (value.TrimStart().FirstOrDefault() is '=' or '+' or '-' or '@') value = "'" + value;
        return "\"" + value.Replace("\"", "\"\"") + "\"";
    }
    public static async Task ExportAsync(string path, IEnumerable<MailRow> rows, CancellationToken ct)
    {
        await using var stream = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None);
        await using var output = new StreamWriter(stream, new UTF8Encoding(true));
        await output.WriteLineAsync("작업명,파일명,원본경로,파일크기,수신자,제목,발송시각,상태,SMTP접수응답,오류");
        foreach (var row in rows) foreach (var file in row.Files)
        {
            ct.ThrowIfCancellationRequested();
            var time = row.Time == 0 ? "" : DateTimeOffset.FromUnixTimeMilliseconds(row.Time).ToString("O", CultureInfo.InvariantCulture);
            await output.WriteLineAsync(string.Join(",", new[] { row.Job, file.Name, file.Origin, file.Size.ToString(CultureInfo.InvariantCulture), row.Recipient, row.Subject, time, row.State, row.Response, row.Error }.Select(Cell)));
        }
    }
}
