using System.Security.Cryptography;
using ICSharpCode.SharpZipLib.Zip;
using Microsoft.Data.Sqlite;
using MimeKit;
using PdfSharp.Pdf;
using SettlementMail.Core;
using Xunit;

namespace SettlementMail.Tests;
public sealed class CoreTests : IDisposable
{
    private readonly WorkspacePaths paths=new(Path.Combine(Path.GetTempPath(),"settlement-tests-"+Guid.NewGuid().ToString("N")));
    private static readonly Credentials Account=new("dummy@naver.com","dummy-not-a-real-password");
    private static Document Doc(int id,long size,string? name=null)=>new(id.ToString(),name??$"문서_{id}.pdf","/dummy/"+id,size,id.ToString());
    public void Dispose(){SqliteConnection.ClearAllPools();Directory.Delete(paths.Root,true);}
    private async Task<Document> FileDoc(string name,byte[] bytes)
    {
        string file=paths.At(name);await File.WriteAllBytesAsync(file,bytes);return new(Guid.NewGuid().ToString("N"),name,file,bytes.LongLength,await DocumentService.HashAsync(file));
    }
    private Settings Config()=>new(){Recipient="receiver@example.com",MessageMB=13};
    [Fact] public void Groups550FilesWithoutLossInBothModes()
    {
        var files=Enumerable.Range(1,550).Select(i=>Doc(i,10000+i*137)).ToList();var policy=new Policy(700000,700000,950000,new HashSet<string>{"pdf"});
        foreach(bool minimize in new[]{false,true})
        {
            var groups=Packing.Group(files,policy,minimize);Assert.Equal(550,groups.Sum(x=>x.Count));
            Assert.Equal(files.Select(x=>x.Id).Order(),groups.SelectMany(x=>x).Select(x=>x.Id).Order());
            Assert.All(groups,g=>{Assert.True(g.Sum(x=>x.Size)<=policy.AttachmentBytes);Assert.True(Packing.EstimatedBytes(g)<=policy.MessageBytes);});
            if(!minimize)Assert.Equal(files,groups.SelectMany(x=>x));
        }
    }
    [Fact] public void WholeFilesArePreservedAndMinimizationCanReduceCount()
    {
        var files=new[]{6,6,3,3}.Select((n,i)=>Doc(i,n*Units.MB)).ToList();var policy=new Settings{MessageMB=20}.Policy();
        Assert.Equal(3,Packing.Group(files,policy,false).Count);Assert.Equal(2,Packing.Group(files,policy,true).Count);
        Assert.Throws<InvalidOperationException>(()=>Packing.Group([Doc(99,10*Units.MB)],Config().Policy(),false));
    }
    [Fact] public void MimeOverheadCanRequireMoreMail()
    {
        Assert.Equal(2,Packing.Group([Doc(1,4*Units.MB),Doc(2,4*Units.MB)],new Settings{MessageMB=6}.Policy(),false).Count);
    }
    [Fact] public void BulkExclusionKeepsApprovedOversizedPdfAndMetadata()
    {
        var files=new[]{Doc(1,12*Units.MB),Doc(2,100,"안내.txt") with{Note="원본 메모",Origin="폴더/안내.txt"},Doc(3,100,"제외.exe") with{Selected=false}};
        var result=Packing.ExcludeUnapproved(files,Config().Policy());Assert.True(result[0].Selected);Assert.Equal(files[1] with{Selected=false},result[1]);Assert.Equal(files[2],result[2]);Assert.True(files[1].Selected);
        Assert.Equal(result,Packing.ExcludeUnapproved(result,Config().Policy()));
    }
    [Theory][InlineData("../escape.pdf")][InlineData("/absolute.pdf")][InlineData("C:/absolute.pdf")][InlineData("x\\escape.pdf")][InlineData("file.pdf:payload")][InlineData("NUL.pdf")][InlineData("same.pdf.")]
    public void ZipUnsafeWindowsPathsAreRejected(string entry)=>Assert.Throws<InvalidDataException>(()=>DocumentService.SafeZipTarget(paths.Documents,entry));
    [Fact] public void CsvEscapesAndNeutralizesSpreadsheetFormulas()
    {
        Assert.Equal("\"' =SUM(A1)\"",Csv.Cell(" =SUM(A1)"));Assert.Equal("\"한글,\"\"문서\"\"\"",Csv.Cell("한글,\"문서\""));
    }
    [Fact] public async Task ImportCopiesOriginalAndDetectsSameContentHash()
    {
        var source=await FileDoc("한글 원본.pdf",[1,2,3,4]);var service=new DocumentService(paths);var result=await service.ImportAsync([source.Path,source.Path],null,CancellationToken.None);
        Assert.Equal(2,result.Count);Assert.NotEqual(source.Path,result[0].Path);Assert.Equal(source.Sha256,result[0].Sha256);Assert.Equal(result[0].Sha256,result[1].Sha256);Assert.Equal(source.Sha256,await DocumentService.HashAsync(source.Path));
    }
    [Fact] public void DpapiCredentialsRoundTripWithoutPlaintextOnDisk()
    {
        var store=new CredentialStore(paths);store.Save(Account);Assert.Equal(Account,store.Read());Assert.DoesNotContain(Account.Password,System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(paths.At("account.bin"))));
    }
    [Fact] public async Task LedgerClaimsOnceAndBlocksSuccessfulAndUncertainRetries()
    {
        var doc=await FileDoc("더미.pdf",[1,2,3]);var rows=await Mailer.PrepareAsync(paths,[doc],Config(),Account,CancellationToken.None);var ledger=new Ledger(paths);ledger.Add(rows);
        Assert.True(ledger.Claim(rows[0].Id));Assert.False(new Ledger(paths).Claim(rows[0].Id));
        ledger.Save(rows[0] with{State="ACCEPTED"});Assert.False(ledger.Retry(rows[0].Id));Assert.False(ledger.Claim(rows[0].Id));
        ledger.Save(rows[0] with{State="UNCERTAIN"});Assert.False(ledger.Retry(rows[0].Id));
    }
    [Fact] public async Task CompletedMimeIsMeasuredAndFilenameIsPreserved()
    {
        var doc=await FileDoc("정산증빙(확인).pdf",RandomNumberGenerator.GetBytes(20000));var rows=await Mailer.PrepareAsync(paths,[doc],Config(),Account,CancellationToken.None);
        var row=Assert.Single(rows);Assert.Equal(new FileInfo(row.Eml).Length,row.MessageBytes);Assert.True(row.MessageBytes<=row.MessageLimit);
        using var mime=await MimeMessage.LoadAsync(row.Eml);Assert.Equal(doc.Name,Assert.IsType<MimePart>(Assert.Single(mime.Attachments)).FileName);Assert.False(string.IsNullOrWhiteSpace(mime.MessageId));
    }
    private sealed class FakeSession(bool loseResponse=false,bool rejectConnect=false) : IOutboundSession
    {
        public Task ConnectAsync(Credentials c,CancellationToken ct)=>rejectConnect?Task.FromException(new MailKit.Security.AuthenticationException("dummy")):Task.CompletedTask;
        public Task<string> SendAsync(MimeMessage message,CancellationToken ct)=>loseResponse?Task.FromException<string>(new IOException("lost final response")):Task.FromResult("250 queued dummy-id");
        public void Dispose(){}
    }
    [Fact] public async Task LostFinalResponseStaysUncertainAndCannotAutomaticallyResend()
    {
        var doc=await FileDoc("더미.pdf",[1,2,3]);var rows=await Mailer.PrepareAsync(paths,[doc],Config(),Account,CancellationToken.None);var ledger=new Ledger(paths);ledger.Add(rows);
        var sender=new Sender(ledger,()=>Account,()=>new FakeSession(true));await sender.SendAsync(rows[0].Batch,null,CancellationToken.None);
        Assert.Equal("UNCERTAIN",ledger.Get(rows[0].Id).State);Assert.False(ledger.Retry(rows[0].Id));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>sender.SendAsync(rows[0].Batch,null,CancellationToken.None));
    }
    [Fact] public async Task SmtpAcceptanceIsDurableAndNotSentTwice()
    {
        var doc=await FileDoc("더미.pdf",[1,2,3]);var rows=await Mailer.PrepareAsync(paths,[doc],Config(),Account,CancellationToken.None);var ledger=new Ledger(paths);ledger.Add(rows);int attempts=0;
        var sender=new Sender(ledger,()=>Account,()=>{attempts++;return new FakeSession();});await sender.SendAsync(rows[0].Batch,null,CancellationToken.None);await sender.SendAsync(rows[0].Batch,null,CancellationToken.None);
        Assert.Equal(1,attempts);Assert.Equal("ACCEPTED",ledger.Get(rows[0].Id).State);Assert.Contains("250",ledger.Get(rows[0].Id).Response);
    }
    [Fact] public async Task AuthenticationFailureIsFailedBeforeDataAndInterruptedSendIsQuarantined()
    {
        var doc=await FileDoc("더미.pdf",[1,2,3]);var rows=await Mailer.PrepareAsync(paths,[doc],Config(),Account,CancellationToken.None);var ledger=new Ledger(paths);ledger.Add(rows);
        await new Sender(ledger,()=>Account,()=>new FakeSession(rejectConnect:true)).SendAsync(rows[0].Batch,null,CancellationToken.None);
        Assert.Equal("FAILED",ledger.Get(rows[0].Id).State);Assert.DoesNotContain(Account.Password,ledger.Get(rows[0].Id).Error);
        ledger.Save(rows[0] with{State="SENDING"});ledger.RecoverInterrupted();Assert.Equal("UNCERTAIN",ledger.Get(rows[0].Id).State);
    }
    [Fact] public async Task ZipPreservesNamesDirectoriesAndRejectsEncryptedArchives()
    {
        string file=paths.At("dummy.zip");
        using(var output=new ZipOutputStream(File.Create(file)))
        {
            output.PutNextEntry(new ZipEntry("하위/증빙.pdf"));output.Write([1,2,3,4]);output.CloseEntry();output.Finish();
        }
        var doc=new Document("zip","dummy.zip",file,new FileInfo(file).Length,await DocumentService.HashAsync(file));var service=new DocumentService(paths);
        Assert.Single(service.ZipPreview(doc));var result=await service.ExtractAsync(doc,Config().Policy(),CancellationToken.None);Assert.EndsWith(Path.Combine("하위","증빙.pdf"),result[0].Path);Assert.True(File.Exists(file));
        string encrypted=paths.At("encrypted.zip");
        using(var output=new ZipOutputStream(File.Create(encrypted))){output.Password="dummy";output.PutNextEntry(new ZipEntry("증빙.pdf"));output.Write([1,2,3]);output.CloseEntry();output.Finish();}
        Assert.Throws<InvalidDataException>(()=>service.ZipPreview(doc with{Path=encrypted,Size=new FileInfo(encrypted).Length}));
    }
    [Fact] public async Task UnequalPdfPagesSplitIndependentlyAndFlagOversizedSinglePage()
    {
        string file=paths.At("불균등.pdf");var random=new Random(2026);
        using(var pdf=new PdfDocument())
        {
            foreach(int bytes in new[]{900000,3000000,100})
            {
                var page=pdf.AddPage();var data=new byte[bytes];
                for(int p=0;p<bytes;p+=100){data[p]=(byte)'%';for(int j=p+1;j<Math.Min(p+99,bytes);j++)data[j]=(byte)(33+random.Next(94));if(p+99<bytes)data[p+99]=(byte)'\n';}
                var content=new PdfDictionary(pdf);content.CreateStream(data);pdf.Internals.AddObject(content);page.Elements["/Contents"]=content.Reference;
            }
            pdf.Save(file);
        }
        string original=await DocumentService.HashAsync(file);var doc=new Document("pdf","불균등.pdf",file,new FileInfo(file).Length,original);
        var result=new DocumentService(paths).Split(doc,Units.MB,null,CancellationToken.None);Assert.Equal(3,result.Count);Assert.True(result[0].Selected);Assert.False(result[1].Selected);Assert.True(result[2].Selected);
        foreach(var part in result){using var loaded=PdfSharp.Pdf.IO.PdfReader.Open(part.Path,PdfSharp.Pdf.IO.PdfDocumentOpenMode.Import);Assert.Equal(1,loaded.PageCount);if(part.Selected)Assert.True(part.Size<=Units.MB);}
        Assert.Equal(original,await DocumentService.HashAsync(file));
    }
}
