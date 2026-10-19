using System.Net.Sockets;
using MailKit.Net.Smtp;
using MailKit.Security;
using MimeKit;
using MimeKit.Utils;

namespace SettlementMail.Core;

public interface IOutboundSession : IDisposable
{
    Task ConnectAsync(Credentials credentials,CancellationToken ct);
    Task<string> SendAsync(MimeMessage message,CancellationToken ct);
}
public sealed class NaverSession : IOutboundSession
{
    private readonly SmtpClient client = new() { Timeout = 30000 };
    public async Task ConnectAsync(Credentials credentials,CancellationToken ct)
    {
        await client.ConnectAsync("smtp.naver.com",587,SecureSocketOptions.StartTls,ct);
        client.AuthenticationMechanisms.Remove("XOAUTH2");
        await client.AuthenticateAsync(credentials.Email,credentials.Password,ct);
    }
    public Task<string> SendAsync(MimeMessage message,CancellationToken ct) => client.SendAsync(Mailer.Options(),message,ct);
    public void Dispose() => client.Dispose();
}
public static class Mailer
{
    public static FormatOptions Options()
    {
        var options = FormatOptions.Default.Clone(); options.NewLineFormat = NewLineFormat.Dos; options.International = false; return options;
    }
    public static MailboxAddress Address(string text)
    {
        if (text.Any(c => c is '\r' or '\n' || c > 127) || !MailboxAddress.TryParse(text,out var address) ||
            !string.Equals(text.Trim(),address.Address,StringComparison.OrdinalIgnoreCase) || !text.Contains('@'))
            throw new InvalidOperationException("수신자 한 명의 이메일 주소만 입력하세요. 표시 이름·줄바꿈·비ASCII 주소는 지원하지 않습니다.");
        return address;
    }
    public static string SafeError(Exception error) => error switch {
        AuthenticationException => "SMTP 인증 거부: 앱 비밀번호·2단계 인증·IMAP/SMTP 설정을 확인하세요.",
        SmtpCommandException command => $"SMTP 응답 {(int)command.StatusCode} ({command.ErrorCode})",
        OperationCanceledException => "사용자 취소. 전송 시도 후에는 서버 접수 여부를 별도 확인하세요.",
        System.Security.Authentication.AuthenticationException => "TLS 인증서 또는 보안 연결 오류",
        SocketException => "네트워크 연결 오류",
        _ => $"SMTP/네트워크 오류 ({error.GetType().Name})"
    };
    public static async Task<string> TestAsync(Credentials credentials,CancellationToken ct)
    {
        using var session = new NaverSession();
        try { await session.ConnectAsync(credentials,ct);return "SMTP 인증 및 STARTTLS 연결 성공 (메일 전송 없음)"; }
        catch (OperationCanceledException) { throw; }
        catch (Exception e) { throw new InvalidOperationException(SafeError(e)); }
    }
    public static async Task<List<MailRow>> PrepareAsync(WorkspacePaths paths,IEnumerable<Document> documents,Settings settings,Credentials credentials,CancellationToken ct)
    {
        var policy = settings.Policy(); Address(settings.Recipient); Address(credentials.Email);
        if (string.IsNullOrWhiteSpace(settings.Subject) || settings.Subject.Length > 150 || settings.Subject.IndexOfAny(['\r','\n']) >= 0)
            throw new InvalidOperationException("메일 제목을 150자 이하로 입력하세요.");
        var groups = Packing.Group(documents,policy,settings.Minimize);
        if (groups.Count == 0) throw new InvalidOperationException("전송할 파일이 없습니다.");
        string batch = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()+"-"+Guid.NewGuid().ToString("N");
        string dir = System.IO.Path.Combine(paths.Outbox,batch);Directory.CreateDirectory(dir);
        var rows = new List<MailRow>();
        try
        {
            for (int i=0;i<groups.Count;i++)
            {
                ct.ThrowIfCancellationRequested(); var files = groups[i];
                using var message = new MimeMessage();
                message.From.Add(Address(credentials.Email));message.To.Add(Address(settings.Recipient));
                message.Subject = $"{settings.Subject} ({i+1}/{groups.Count})";message.Date = DateTimeOffset.Now;
                message.MessageId = MimeUtils.GenerateMessageId("settlement.local");
                var multipart = new Multipart("mixed") { new TextPart("plain") { Text = $"{settings.Job}\r\n정산자료 {i+1}/{groups.Count}\r\n첨부 문서를 확인해 주세요." } };
                message.Body = multipart;
                foreach (var doc in files)
                {
                    if (!File.Exists(doc.Path) || new FileInfo(doc.Path).Length != doc.Size || await DocumentService.HashAsync(doc.Path,ct) != doc.Sha256)
                        throw new InvalidDataException($"파일 변경 감지: {doc.Name}");
                    multipart.Add(new MimePart(ContentType.Parse(MimeTypes.GetMimeType(doc.Name))) {
                        Content = new MimeContent(File.OpenRead(doc.Path),ContentEncoding.Default), ContentTransferEncoding = ContentEncoding.Base64,
                        ContentDisposition = new ContentDisposition(ContentDisposition.Attachment), FileName = doc.Name
                    });
                }
                message.Prepare(EncodingConstraint.SevenBit);
                string id = Guid.NewGuid().ToString("N"), target = System.IO.Path.Combine(dir,id+".eml");
                await using (var output = new FileStream(target,FileMode.CreateNew,FileAccess.Write,FileShare.None,65536,true))
                    await message.WriteToAsync(Options(),output,ct);
                long size = new FileInfo(target).Length;
                if (size > policy.MessageBytes) throw new InvalidOperationException("실제 MIME 메시지 크기가 기준을 초과했습니다. 첨부 합계 기준을 낮추고 다시 구성하세요.");
                rows.Add(new(id,batch,settings.Job,i+1,groups.Count,settings.Recipient,message.Subject,credentials.Email,files,target,size,
                    await DocumentService.HashAsync(target,ct),settings.IntervalSeconds,policy.MessageBytes));
            }
            return rows;
        }
        catch { Directory.Delete(dir,true);throw; }
    }
}
public sealed class Sender(Ledger ledger,Func<Credentials?> readCredentials,Func<IOutboundSession>? factory = null,Func<TimeSpan,CancellationToken,Task>? delay = null)
{
    public async Task SendAsync(string batch,IProgress<MailRow>? progress,CancellationToken ct,string? onlyId = null)
    {
        var rows = ledger.All().Where(r => r.Batch == batch).OrderBy(r => r.Position).ToList();
        if (rows.Any(r => r.State is "UNCERTAIN" or "SENDING" or "CONNECTING")) throw new InvalidOperationException("접수 불확실 또는 실행 중인 메일이 있습니다. 먼저 접수 여부를 확인하세요.");
        try
        {
            foreach (var row in rows.Where(r => onlyId == null || r.Id == onlyId))
            {
                ct.ThrowIfCancellationRequested(); if (!ledger.Claim(row.Id)) continue;
                bool sending = false;
                try
                {
                    progress?.Report(row with { State = "CONNECTING" });
                    long last = ledger.All().Max(r => r.Time);
                    long wait = last + row.IntervalSeconds*1000L-DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    if (wait > 0) await (delay ?? Task.Delay)(TimeSpan.FromMilliseconds(wait),ct);
                    var credentials = readCredentials() ?? throw new InvalidOperationException("계정을 먼저 저장하세요.");
                    if (!string.Equals(credentials.Email,row.Sender,StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("구성 당시 발신 계정과 현재 계정이 다릅니다.");
                    if (!File.Exists(row.Eml) || new FileInfo(row.Eml).Length != row.MessageBytes || row.MessageBytes > row.MessageLimit || await DocumentService.HashAsync(row.Eml,ct) != row.EmlHash)
                        throw new InvalidDataException("승인한 MIME 파일의 누락·변경을 감지했습니다.");
                    using var session = factory?.Invoke() ?? new NaverSession();
                    await session.ConnectAsync(credentials,ct);
                    await using var input = new FileStream(row.Eml,FileMode.Open,FileAccess.Read,FileShare.Read,65536,true);
                    var parser = new MimeParser(input,MimeFormat.Entity,true);
                    using var message = await parser.ParseMessageAsync(ct);
                    ct.ThrowIfCancellationRequested();
                    var started = row with { State = "SENDING",Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),Error = "" };
                    ledger.Save(started);progress?.Report(started);sending = true;
                    string response = await session.SendAsync(message,ct);
                    // SMTP success is durable before any later cancellation/disconnect is observed.
                    var accepted = started with { State = "ACCEPTED",Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),Response = response.Replace(credentials.Password,"[삭제]")[..Math.Min(response.Replace(credentials.Password,"[삭제]").Length,500)] };
                    ledger.Save(accepted);progress?.Report(accepted);
                }
                catch (Exception error)
                {
                    bool rejected = error is SmtpCommandException command && (int)command.StatusCode is >= 400 and <= 599;
                    var failed = row with { State = DeliveryState.Failure(sending,rejected,error is OperationCanceledException),Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),Error = Mailer.SafeError(error) };
                    ledger.Save(failed);progress?.Report(failed);
                    if (error is OperationCanceledException) throw;
                    break; // No automatic retries, including authentication/rate errors.
                }
            }
        }
        catch (OperationCanceledException) { ledger.CancelPending(batch);throw; }
    }
}
