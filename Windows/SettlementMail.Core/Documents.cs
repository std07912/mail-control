using System.Security.Cryptography;
using ICSharpCode.SharpZipLib.Zip;
using ICSharpCode.SharpZipLib.Checksum;
using PdfSharp.Pdf;
using PdfSharp.Pdf.IO;

namespace SettlementMail.Core;

public sealed class DocumentService(WorkspacePaths paths)
{
    public static async Task<string> HashAsync(string path, CancellationToken ct = default)
    {
        await using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, true);
        return Convert.ToHexString(await SHA256.HashDataAsync(input, ct)).ToLowerInvariant();
    }
    private static void Space(string target, long bytes)
    {
        var drive = new DriveInfo(System.IO.Path.GetPathRoot(System.IO.Path.GetFullPath(target))!);
        if (drive.AvailableFreeSpace < bytes + 32 * Units.MB) throw new IOException("저장공간이 부족합니다. 32MB 이상의 여유 공간이 필요합니다.");
    }
    public async Task<List<Document>> ImportAsync(IEnumerable<string> inputs, IProgress<string>? progress, CancellationToken ct)
    {
        var docs = new List<Document>(); var stack = new Stack<(string Path, int Depth)>(inputs.Select(x => (x, 0))); int visited = 0;
        try
        {
            while (stack.TryPop(out var current))
            {
                ct.ThrowIfCancellationRequested();
                if (++visited > 10000 || current.Depth > 32) throw new InvalidOperationException("폴더 항목 10,000개 또는 깊이 32의 안전 한도를 초과했습니다.");
                var attr = File.GetAttributes(current.Path);
                if (attr.HasFlag(FileAttributes.ReparsePoint)) continue;
                if (Directory.Exists(current.Path)) { foreach (var p in Directory.EnumerateFileSystemEntries(current.Path)) stack.Push((p, current.Depth + 1)); continue; }
                var info = new FileInfo(current.Path); if (info.Length > 2000 * Units.MB) throw new InvalidOperationException("가져오기 파일당 안전 한도는 2GB입니다.");
                string id = Guid.NewGuid().ToString("N"), dir = System.IO.Path.Combine(paths.Documents, id); Directory.CreateDirectory(dir);
                string target = System.IO.Path.Combine(dir, info.Name);
                progress?.Report($"가져오는 중 {docs.Count + 1}개 · {info.Name}");
                try
                {
                    await using var source = new FileStream(info.FullName, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, true);
                    await using (var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
                        await CopyLimited(source, output, target, 2000 * Units.MB, ct);
                    docs.Add(new(id, info.Name, target, new FileInfo(target).Length, await HashAsync(target, ct), Origin: info.FullName));
                }
                catch { Directory.Delete(dir, true); throw; }
            }
            return docs;
        }
        catch { foreach (var d in docs) File.Delete(d.Path); throw; }
    }
    private static async Task<long> CopyLimited(Stream input, Stream output, string target, long limit, CancellationToken ct, Action<byte[],int>? consume = null)
    {
        var buffer = new byte[65536]; long count = 0;
        while (true)
        {
            int n = await input.ReadAsync(buffer, ct); if (n == 0) break;
            count += n; if (count > limit) throw new InvalidDataException("실제 파일 크기가 안전 한도를 초과했습니다.");
            Space(target, n); consume?.Invoke(buffer,n); await output.WriteAsync(buffer.AsMemory(0,n), ct);
        }
        return count;
    }
    public static string SafeZipTarget(string root, string entry)
    {
        if (string.IsNullOrWhiteSpace(entry) || entry.Contains('\\') || entry.StartsWith('/') || entry.Contains(':')) throw new InvalidDataException("ZIP 경로 탈출 또는 잘못된 경로를 차단했습니다.");
        var parts = entry.TrimEnd('/').Split('/');
        foreach (var part in parts)
        {
            string stem = part.Split('.')[0].ToUpperInvariant();
            if (part is "." or ".." || part.Length == 0 || part.EndsWith(' ') || part.EndsWith('.') || part.IndexOfAny(System.IO.Path.GetInvalidFileNameChars()) >= 0 ||
                stem is "CON" or "PRN" or "AUX" or "NUL" || System.Text.RegularExpressions.Regex.IsMatch(stem, "^(COM|LPT)[1-9]$"))
                throw new InvalidDataException("Windows에서 안전하게 보존할 수 없는 ZIP 경로입니다.");
        }
        string prefix = System.IO.Path.GetFullPath(root).TrimEnd(System.IO.Path.DirectorySeparatorChar) + System.IO.Path.DirectorySeparatorChar;
        string target = System.IO.Path.GetFullPath(System.IO.Path.Combine(root, entry.Replace('/', System.IO.Path.DirectorySeparatorChar)));
        if (!target.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("ZIP 경로 탈출을 차단했습니다.");
        return target;
    }
    public List<string> ZipPreview(Document doc)
    {
        using var zip = new ZipFile(doc.Path); long total = 0; var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase); var result = new List<string>();
        if (zip.Count > 5000) throw new InvalidDataException("ZIP 항목 5,000개 한도 초과");
        foreach (ZipEntry entry in zip)
        {
            if (entry.IsCrypted) throw new InvalidDataException("비밀번호 ZIP은 지원하지 않습니다. 승인된 환경에서 별도 해제하세요.");
            string target = SafeZipTarget(System.IO.Path.Combine(paths.Documents,"preview"),entry.Name);
            if (!names.Add(target)) throw new InvalidDataException("ZIP 내부 중복 경로를 차단했습니다.");
            if (((entry.ExternalFileAttributes >> 16) & 0xF000) == 0xA000) throw new InvalidDataException("ZIP 심볼릭 링크를 차단했습니다.");
            if (entry.Size < 0 || entry.Size > 500 * Units.MB || entry.CompressedSize < 0 || entry.CompressedSize > doc.Size ||
                entry.Size > Math.Max(1, entry.CompressedSize) * 200 || (total += entry.Size) > 1500 * Units.MB) throw new InvalidDataException("ZIP 크기·압축률 안전 한도를 초과했습니다.");
            result.Add($"{entry.Name}  ·  {Units.Size(entry.Size)}");
        }
        return result;
    }
    public async Task<List<Document>> ExtractAsync(Document doc, Policy policy, CancellationToken ct)
    {
        ZipPreview(doc); string dir = System.IO.Path.Combine(paths.Documents, "zip-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(dir);
        var output = new List<Document>(); long total = 0;
        try
        {
            using var zip = new ZipFile(doc.Path);
            foreach (ZipEntry entry in zip)
            {
                ct.ThrowIfCancellationRequested(); string target = SafeZipTarget(dir,entry.Name);
                if (entry.IsDirectory) { Directory.CreateDirectory(target); continue; }
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(target)!);
                var crc = new Crc32();
                using var input = zip.GetInputStream(entry);
                await using (var destination = new FileStream(target,FileMode.CreateNew,FileAccess.Write,FileShare.None,65536,true))
                {
                    long n = await CopyLimited(input,destination,target,Math.Min(entry.Size,500*Units.MB),ct,(b,n) => crc.Update(new ArraySegment<byte>(b,0,n)));
                    if ((total += n) > 1500 * Units.MB || n != entry.Size || crc.Value != entry.Crc) throw new InvalidDataException("ZIP 실제 크기 또는 CRC가 일치하지 않습니다.");
                }
                bool approved = policy.Approved(target);
                output.Add(new(Guid.NewGuid().ToString("N"),System.IO.Path.GetFileName(target),target,new FileInfo(target).Length,await HashAsync(target,ct),approved,
                    approved ? "ZIP 해제본" : "승인 형식 아님",doc.Name+"/"+entry.Name));
            }
            return output;
        }
        catch { Directory.Delete(dir,true); throw; }
    }
    public List<Document> Split(Document doc, long limit, IProgress<string>? progress, CancellationToken ct)
    {
        if (limit < Units.MB || limit > 9*Units.MB) throw new InvalidOperationException("PDF 목표는 1~9MB입니다.");
        if (doc.Size > 500*Units.MB) throw new InvalidOperationException("500MB 초과 PDF는 별도 처리하세요. 메모리 안전 한도입니다.");
        string dir = System.IO.Path.Combine(paths.Documents,"pdf-"+Guid.NewGuid().ToString("N")); Directory.CreateDirectory(dir);
        try
        {
            using var source = PdfReader.Open(doc.Path,PdfDocumentOpenMode.Import);
            if (!source.SecuritySettings.PermitExtractContent) throw new InvalidOperationException("PDF의 추출 권한이 없습니다. 권한을 우회하지 않습니다.");
            if (source.PageCount == 0) throw new InvalidDataException("페이지가 없는 PDF입니다.");
            var result = new List<Document>(); int start = 0, part = 1;
            void Write(int end,string target)
            {
                ct.ThrowIfCancellationRequested(); Space(target, Math.Min(doc.Size,100*Units.MB));
                using var dest = new PdfDocument();
                for (int page = start;page < end;page++) { ct.ThrowIfCancellationRequested(); dest.AddPage(source.Pages[page]); }
                dest.Save(target);
            }
            while (start < source.PageCount)
            {
                progress?.Report($"PDF 분할 · {start+1}/{source.PageCount}페이지");
                string candidate = System.IO.Path.Combine(dir,"candidate.pdf"), trial = System.IO.Path.Combine(dir,"trial.pdf");
                int end = start+1; Write(end,candidate); bool oversized = new FileInfo(candidate).Length > limit;
                if (!oversized)
                {
                    int low = start+2, high = source.PageCount;
                    while (low <= high)
                    {
                        int middle = low+(high-low)/2; Write(middle,trial);
                        if (new FileInfo(trial).Length <= limit) { File.Copy(trial,candidate,true);end = middle;low = middle+1; } else high = middle-1;
                        File.Delete(trial);
                    }
                }
                string name = $"{System.IO.Path.GetFileNameWithoutExtension(doc.Name)}_{part:00}_{start+1}-{end}p.pdf";
                string target = System.IO.Path.Combine(dir,name); File.Move(candidate,target); long size = new FileInfo(target).Length;
                if (!oversized && size > limit) throw new InvalidDataException("분할 후 실제 크기가 기준을 초과했습니다.");
                result.Add(new(Guid.NewGuid().ToString("N"),name,target,size,HashAsync(target,ct).GetAwaiter().GetResult(),!oversized,
                    oversized ? "단일 페이지 초과: 별도 처리 필요" : "PDF 분할본 · 서명 보존 보장 안 됨",doc.Name));
                start = end;part++;
            }
            return result;
        }
        catch (PdfReaderException e) { Directory.Delete(dir,true); throw new InvalidOperationException("암호화·손상 또는 지원하지 않는 PDF입니다. 별도 처리하세요.",e); }
        catch { Directory.Delete(dir,true); throw; }
    }
}
