using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using PdfSharp.Pdf;
using SettlementMail.Core;
using Document=SettlementMail.Core.Document;

namespace SettlementMail.App;
public partial class MainWindow : Window
{
    private readonly WorkspacePaths paths;
    private readonly Ledger ledger;
    private readonly CredentialStore credentials;
    private readonly DocumentService documents;
    private readonly WorkspaceViewModel vm=new();
    private CancellationTokenSource? active;
    private bool ready,suppressSelection;
    public MainWindow(WorkspacePaths workspace)
    {
        paths=workspace;ledger=new(paths);credentials=new(paths);documents=new(paths);
        InitializeComponent();DataContext=vm;
        ledger.RecoverInterrupted();
        var s=WorkspacePaths.ReadJson<Settings>(paths.At("settings.json"))??new Settings();
        JobBox.Text=s.Job;RecipientBox.Text=s.Recipient;SubjectBox.Text=s.Subject;MinimizeBox.IsChecked=s.Minimize;
        AttachBox.Text=s.AttachmentMB.ToString();FileLimitBox.Text=s.FileMB.ToString();MessageBoxLimit.Text=s.MessageMB.ToString();
        PdfBox.Text=s.PdfMB.ToString();IntervalBox.Text=s.IntervalSeconds.ToString();ExtensionsBox.Text=s.Extensions;
        vm.Settings=s;vm.RefreshDocuments((WorkspacePaths.ReadJson<List<Document>>(paths.At("selection.json"))??[]).Where(d=>File.Exists(d.Path)).ToList(),s.Policy());
        var saved=credentials.Read();if(saved!=null){EmailBox.Text=saved.Email;vm.Account=$"저장됨 · {saved.Email} · 비밀번호 재입력 불필요";}
        vm.RefreshHistory(ledger.All());
        var tracked=ledger.All().Select(x=>x.Batch).ToHashSet();
        foreach(var directory in Directory.EnumerateDirectories(paths.Outbox))if(!tracked.Contains(Path.GetFileName(directory)))Directory.Delete(directory,true);
        vm.SelectionChanged=SelectionChanged;ready=true;
    }
    private void Navigate(object sender,RoutedEventArgs e){if(Pages!=null&&sender is RadioButton b&&int.TryParse(b.Tag?.ToString(),out int i))Pages.SelectedIndex=i;}
    private Settings ReadSettings()
    {
        int Number(TextBox box,string label)=>int.TryParse(box.Text,out int n)?n:throw new InvalidOperationException($"{label}: 정수를 입력하세요.");
        var s=new Settings{Job=JobBox.Text.Trim(),Recipient=RecipientBox.Text.Trim(),Subject=SubjectBox.Text.Trim(),
            AttachmentMB=Number(AttachBox,"메일 첨부 합계"),FileMB=Number(FileLimitBox,"개별 파일"),MessageMB=Number(MessageBoxLimit,"전체 메시지"),
            PdfMB=Number(PdfBox,"PDF 목표"),IntervalSeconds=Number(IntervalBox,"발송 간격"),Extensions=ExtensionsBox.Text,Minimize=MinimizeBox.IsChecked==true};
        s.Policy();return s;
    }
    private void PlanChanged(object sender,RoutedEventArgs e){if(ready&&!vm.IsBusy)ClearDraft();}
    private void ClearDraft(){foreach(var row in vm.Draft)if(File.Exists(row.Value.Eml))File.Delete(row.Value.Eml);vm.Draft.Clear();}
    private void SelectionChanged()
    {
        if(!ready||suppressSelection)return;
        try
        {
            ClearDraft();var policy=ReadSettings().Policy();
            foreach(var row in vm.Documents)row.Status=!row.Selected?"선택 제외":policy.Problem(row.Value)??"전송 준비";
            WorkspacePaths.WriteJson(paths.At("selection.json"),vm.Documents.Select(x=>x.Value).ToList());
        }
        catch(Exception e){vm.Status=e.Message;}
    }
    private void ApplyDocuments(List<Document> list)
    {
        var policy=ReadSettings().Policy();WorkspacePaths.WriteJson(paths.At("selection.json"),list);
        string? selected=(FileGrid.SelectedItem as DocumentRow)?.Value.Id,processing=(ProcessGrid.SelectedItem as DocumentRow)?.Value.Id;
        suppressSelection=true;try{vm.RefreshDocuments(list,policy);}finally{suppressSelection=false;}
        FileGrid.SelectedItem=vm.Documents.FirstOrDefault(x=>x.Value.Id==selected);
        ProcessGrid.SelectedItem=vm.Documents.FirstOrDefault(x=>x.Value.Id==processing);
        ClearDraft();
    }
    private void MergeDocuments(List<Document> incoming,string? sourceId=null,string sourceNote="")
    {
        var existing=vm.Documents.Select(x=>x.Value.Id==sourceId?x.Value with{Selected=false,Note=sourceNote}:x.Value).ToList();
        var hashes=existing.Select(x=>x.Sha256).ToHashSet();var duplicates=new List<string>();
        var unique=new List<Document>();
        foreach(var doc in incoming)
        {
            if(hashes.Add(doc.Sha256))unique.Add(doc);
            else
            {
                duplicates.Add(doc.Path);
                if(doc.Selected&&sourceId!=null)existing=existing.Select(x=>x.Id==sourceId&&x.Sha256==doc.Sha256?x with{Selected=true,Note="동일 내용: 원본 유지"}:x).ToList();
            }
        }
        try{ApplyDocuments(existing.Concat(unique).ToList());}
        catch{foreach(var file in incoming)if(File.Exists(file.Path))File.Delete(file.Path);throw;}
        foreach(var file in duplicates)File.Delete(file);
        vm.Status=$"{unique.Count}개 추가 · 중복 {duplicates.Count}개 제외 · 원본 보존";
    }
    private async Task Run(string label,Func<CancellationToken,Task> work)
    {
        if(vm.IsBusy)return;vm.IsBusy=true;vm.Status=label;active=new();
        try{await work(active.Token);if(vm.Status==label)vm.Status=label+" 완료";}
        catch(OperationCanceledException){vm.Status="작업을 취소했습니다. 전송 중이던 메일은 접수 상태를 확인하세요.";}
        catch(Exception e){vm.Status=e.Message;}
        finally{active.Dispose();active=null;vm.IsBusy=false;vm.RefreshHistory(ledger.All());}
    }
    private async Task Import(IEnumerable<string> input)=>await Run("파일 가져오기",async ct=>
    {
        var progress=new Progress<string>(s=>vm.Status=s);
        var files=await Task.Run(()=>documents.ImportAsync(input,progress,ct),ct);MergeDocuments(files);
    });
    private async void AddFiles(object sender,RoutedEventArgs e)
    {
        if(vm.IsBusy)return;var dialog=new OpenFileDialog{Title="전송할 문서 선택",Multiselect=true,Filter="문서와 압축파일|*.pdf;*.xls;*.xlsx;*.doc;*.docx;*.hwp;*.hwpx;*.jpg;*.png;*.zip|모든 파일|*.*"};
        if(dialog.ShowDialog(this)==true)await Import(dialog.FileNames);
    }
    private async void AddFolder(object sender,RoutedEventArgs e)
    {
        if(vm.IsBusy)return;var dialog=new OpenFolderDialog{Title="문서 폴더 선택",Multiselect=true};if(dialog.ShowDialog(this)==true)await Import(dialog.FolderNames);
    }
    private void FilesDragged(object sender,DragEventArgs e){if(e.Data.GetDataPresent(DataFormats.FileDrop)){e.Effects=vm.IsBusy?DragDropEffects.None:DragDropEffects.Copy;e.Handled=true;}}
    private async void FilesDropped(object sender,DragEventArgs e){if(!vm.IsBusy&&e.Data.GetData(DataFormats.FileDrop) is string[] files)await Import(files);}
    private void CancelWork(object sender,RoutedEventArgs e)=>active?.Cancel();
    private async void ExcludeUnsupported(object sender,RoutedEventArgs e)=>await Run("미승인 형식 선택 해제",ct=>
    {
        var list=Packing.ExcludeUnapproved(vm.Documents.Select(x=>x.Value),ReadSettings().Policy());
        int count=vm.Documents.Count(x=>x.Selected)-list.Count(x=>x.Selected);ApplyDocuments(list);
        vm.Status=$"미승인 형식 {count}개 선택 해제 · 파일은 삭제하지 않았습니다.";return Task.CompletedTask;
    });
    private async void SortFiles(object sender,RoutedEventArgs e)=>await Run("파일명 정렬",ct=>{ApplyDocuments(vm.Documents.Select(x=>x.Value).OrderBy(x=>x.Name,StringComparer.CurrentCultureIgnoreCase).ToList());return Task.CompletedTask;});
    private Document? SelectedDocument()=>((Pages.SelectedIndex==1?ProcessGrid.SelectedItem:FileGrid.SelectedItem) as DocumentRow)?.Value;
    private void FileSelected(object sender,SelectionChangedEventArgs e)
    {
        if(SelectedFileInfo!=null&&FileGrid.SelectedItem is DocumentRow row)SelectedFileInfo.Text=$"{row.Name}\n{row.Size}\n\n{row.Note}\n\n출처: {row.Origin}\n\nSHA-256\n{row.Hash}";
    }
    private void PreviewFile(object sender,RoutedEventArgs e)
    {
        var doc=SelectedDocument();if(doc==null){vm.Status="미리 볼 문서를 선택하세요.";return;}
        try
        {
            var safe=new HashSet<string>(StringComparer.OrdinalIgnoreCase){".pdf",".xls",".xlsx",".doc",".docx",".hwp",".hwpx",".jpg",".jpeg",".png",".txt"};
            if(safe.Contains(Path.GetExtension(doc.Name)))Process.Start(new ProcessStartInfo(doc.Path){UseShellExecute=true});
            else{var p=new ProcessStartInfo("explorer.exe"){UseShellExecute=true};p.ArgumentList.Add("/select,"+doc.Path);Process.Start(p);}
        }
        catch(Exception error){vm.Status=$"문서를 열 수 없습니다 ({error.GetType().Name}). 해당 형식의 뷰어를 확인하세요.";}
    }
    private async void MoveUp(object sender,RoutedEventArgs e)=>await Move(-1);
    private async void MoveDown(object sender,RoutedEventArgs e)=>await Move(1);
    private async Task Move(int direction)=>await Run("순서 변경",ct=>
    {
        var doc=SelectedDocument();if(doc==null)return Task.CompletedTask;var list=vm.Documents.Select(x=>x.Value).ToList();int index=list.FindIndex(x=>x.Id==doc.Id),next=index+direction;
        if(next>=0&&next<list.Count){list.RemoveAt(index);list.Insert(next,doc);ApplyDocuments(list);}return Task.CompletedTask;
    });
    private async void ExtractZip(object sender,RoutedEventArgs e)
    {
        var doc=SelectedDocument();if(doc==null||!doc.Name.EndsWith(".zip",StringComparison.OrdinalIgnoreCase)){vm.Status="처리 목록에서 ZIP 파일을 선택하세요.";return;}
        await Run("ZIP 목록 확인",async ct=>
        {
            var policy=ReadSettings().Policy();var entries=await Task.Run(()=>documents.ZipPreview(doc),ct);
            if(!ShowZipList(entries))return;
            var files=await Task.Run(()=>documents.ExtractAsync(doc,policy,ct),ct);MergeDocuments(files,doc.Id,"ZIP 원본 보존");
        });
    }
    private bool ShowZipList(List<string> entries)
    {
        bool approved=false;var dialog=new Window{Title="ZIP 내부 목록 · 승인된 문서만 해제",Owner=this,Width=700,Height=540,WindowStartupLocation=WindowStartupLocation.CenterOwner};
        var root=new DockPanel{Margin=new Thickness(20)};
        var notice=new TextBlock{Text=$"총 {entries.Count}개 · 기관에서 승인한 문서에 한해 로컬로 해제하세요.",TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,0,0,15)};DockPanel.SetDock(notice,Dock.Top);root.Children.Add(notice);
        var accept=new Button{Content="승인된 문서 해제",Margin=new Thickness(0,15,0,0)};accept.Click+=(_,_)=>{approved=true;dialog.Close();};DockPanel.SetDock(accept,Dock.Bottom);root.Children.Add(accept);
        root.Children.Add(new ListBox{ItemsSource=entries});dialog.Content=root;dialog.ShowDialog();return approved;
    }
    private async void SplitPdf(object sender,RoutedEventArgs e)
    {
        var doc=SelectedDocument();if(doc==null||!doc.Name.EndsWith(".pdf",StringComparison.OrdinalIgnoreCase)){vm.Status="처리 목록에서 PDF를 선택하세요.";return;}
        if(MessageBox.Show(this,"전자서명·인증정보가 분할본에서 유지되지 않을 수 있습니다. 원본은 보존합니다. 분할할까요?","PDF 분할",MessageBoxButton.YesNo,MessageBoxImage.Information)!=MessageBoxResult.Yes)return;
        await Run("PDF 실제 크기 측정·분할",async ct=>
        {
            long limit=ReadSettings().PdfMB*Units.MB;if(doc.Size<=limit){vm.Status="목표 크기 이하입니다. 원본 그대로 사용할 수 있습니다.";return;}
            var progress=new Progress<string>(s=>vm.Status=s);
            var parts=await Task.Run(()=>documents.Split(doc,limit,progress,ct),ct);
            MergeDocuments(parts,doc.Id,parts.Count==1&&!parts[0].Selected?parts[0].Note:"분할 원본 보존");
        });
    }
    private async void SaveAccount(object sender,RoutedEventArgs e)=>await Run("계정 보안 저장",async ct=>
    {
        string email=Mailer.Address(EmailBox.Text.Trim()).Address,password=PasswordBox.Password;
        if(!email.EndsWith("@naver.com",StringComparison.OrdinalIgnoreCase)||string.IsNullOrWhiteSpace(password))throw new InvalidOperationException("@naver.com 계정과 애플리케이션 비밀번호를 입력하세요.");
        await Task.Run(()=>credentials.Save(new(email,password)),ct);PasswordBox.Clear();vm.Account=$"저장됨 · {email} · 비밀번호 재입력 불필요";ClearDraft();
    });
    private async void TestAccount(object sender,RoutedEventArgs e)=>await Run("SMTP 연결 테스트",async ct=>
    {
        var account=await Task.Run(credentials.Read,ct)??throw new InvalidOperationException("계정을 먼저 저장하세요.");vm.Status=await Mailer.TestAsync(account,ct);
    });
    private async void SaveSettings(object sender,RoutedEventArgs e)=>await Run("기관 승인 기준 저장",ct=>
    {
        var s=ReadSettings();WorkspacePaths.WriteJson(paths.At("settings.json"),s);vm.Settings=s;ClearDraft();ApplyDocuments(vm.Documents.Select(x=>x.Value).ToList());return Task.CompletedTask;
    });
    private async void PrepareMail(object sender,RoutedEventArgs e)=>await Run("메일 구성·실제 MIME 크기 검증",async ct=>
    {
        ClearDraft();var settings=ReadSettings();WorkspacePaths.WriteJson(paths.At("settings.json"),settings);
        var account=await Task.Run(credentials.Read,ct)??throw new InvalidOperationException("계정을 먼저 저장하세요.");
        var rows=await Task.Run(()=>Mailer.PrepareAsync(paths,vm.Documents.Select(x=>x.Value).ToList(),settings,account,ct),ct);
        foreach(var row in rows)vm.Draft.Add(new(row));vm.Status=$"메일 {rows.Count}개 구성 완료 · 전체 목록 검토 후 승인하세요.";
    });
    private async void ApproveSend(object sender,RoutedEventArgs e)
    {
        if(vm.IsBusy||vm.Draft.Count==0){vm.Status="먼저 첨부 목록을 구성하세요.";return;}
        var rows=vm.Draft.Select(x=>x.Value).ToList();
        if(MessageBox.Show(this,$"{rows[0].Recipient}로 메일 {rows.Count}개를 발송합니다.\n수신자·첨부파일·승인 정책을 확인했습니까?","전체 발송 최종 승인",MessageBoxButton.YesNo,MessageBoxImage.Question)!=MessageBoxResult.Yes)return;
        await Run("승인된 메일 발송",async ct=>
        {
            await Task.Run(()=>ledger.Add(rows),ct);vm.Draft.Clear();vm.RefreshHistory(ledger.All());Pages.SelectedIndex=3;SendNav.IsChecked=true;
            await new Sender(ledger,credentials.Read).SendAsync(rows[0].Batch,new Progress<MailRow>(vm.UpdateMail),ct);vm.Status="발송 처리 종료 · 개별 접수 상태를 확인하세요.";
        });
    }
    private MailRow? SelectedMail()=>(HistoryGrid.SelectedItem as HistoryRow)?.Value;
    private async void RetryMail(object sender,RoutedEventArgs e)
    {
        var row=SelectedMail();if(row==null)return;
        await Run("선택 메일 재시도",async ct=>
        {
            if(ledger.All().Any(x=>x.Batch==row.Batch&&x.State is "UNCERTAIN" or "CONNECTING" or "SENDING"))throw new InvalidOperationException("접수 불확실 또는 실행 중인 메일을 먼저 확인하세요.");
            if(!ledger.Retry(row.Id))throw new InvalidOperationException("실패·취소된 메일만 재시도할 수 있습니다.");
            await new Sender(ledger,credentials.Read).SendAsync(row.Batch,new Progress<MailRow>(vm.UpdateMail),ct,row.Id);
        });
    }
    private async void ResumeBatch(object sender,RoutedEventArgs e)
    {
        var row=SelectedMail();if(row==null)return;
        if(MessageBox.Show(this,"선택한 작업에서 아직 보내지 않은 대기 메일을 이어서 발송할까요?","대기 메일 발송",MessageBoxButton.YesNo)!=MessageBoxResult.Yes)return;
        await Run("대기 메일 발송",ct=>new Sender(ledger,credentials.Read).SendAsync(row.Batch,new Progress<MailRow>(vm.UpdateMail),ct));
    }
    private async void ResolveMail(object sender,RoutedEventArgs e)
    {
        var row=SelectedMail();if(row?.State!="UNCERTAIN"){vm.Status="접수 확인 필요 상태의 메일을 선택하세요.";return;}
        var choice=MessageBox.Show(this,"서버나 관리자에게 접수 여부를 별도 확인한 경우에만 선택하세요.\n\n예: 접수 확인됨\n아니요: 미접수 확인됨 (실패 상태로 바꿈)\n취소: 아직 확인하지 않음\n\n보낸메일함에 없다는 이유만으로 미접수를 단정하지 마세요.","접수 결과 확인",MessageBoxButton.YesNoCancel,MessageBoxImage.Question);
        if(choice==MessageBoxResult.Cancel)return;
        await Run("접수 확인 결과 저장",ct=>{if(ledger.Get(row.Id).State!="UNCERTAIN")throw new InvalidOperationException("이미 변경된 상태입니다.");ledger.Save(row with{State=choice==MessageBoxResult.Yes?"CONFIRMED":"FAILED",Time=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),Error="사용자가 별도 확인한 접수 결과"});return Task.CompletedTask;});
    }
    private async void ExportHistory(object sender,RoutedEventArgs e)
    {
        var dialog=new SaveFileDialog{Filter="CSV 파일|*.csv",FileName="정산자료_발송내역.csv"};if(dialog.ShowDialog(this)!=true)return;
        await Run("CSV 이력 내보내기",ct=>Csv.ExportAsync(dialog.FileName,ledger.All(),ct));
    }
    private async void ClearTemporary(object sender,RoutedEventArgs e)
    {
        if(MessageBox.Show(this,"선택 목록과 완료 작업의 앱 내부 복사본을 삭제할까요? 원본과 이력은 보존합니다.","임시파일 정리",MessageBoxButton.YesNo)!=MessageBoxResult.Yes)return;
        await Run("임시파일 정리",ct=>
        {
            if(ledger.All().Any(r=>r.State is "PENDING" or "CONNECTING" or "SENDING" or "UNCERTAIN" or "FAILED" or "CANCELLED"))throw new InvalidOperationException("미완료·재시도 가능 작업이 있어 파일을 보존합니다.");
            ClearDraft();ApplyDocuments([]);Directory.Delete(paths.Documents,true);Directory.Delete(paths.Outbox,true);return Task.CompletedTask;
        });
    }
    private void WindowClosing(object? sender,CancelEventArgs e)
    {
        if(vm.IsBusy){e.Cancel=true;active?.Cancel();vm.Status="취소 요청 중입니다. 접수 상태가 저장된 뒤 창을 다시 닫아주세요.";}
    }
    public async Task RunSmokeAsync(string output)
    {
        Directory.CreateDirectory(output);string source=paths.At("dummy-input");Directory.CreateDirectory(source);
        string pdf=Path.Combine(source,"정산증빙.pdf"),txt=Path.Combine(source,"전송안내.txt");
        using(var file=new PdfDocument()){file.AddPage();file.Save(pdf);}File.WriteAllText(txt,"실제 업무자료가 아닌 Windows UI 테스트용 더미입니다.");
        string original=await DocumentService.HashAsync(pdf);
        await Import([pdf,txt]);
        BulkExcludeButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        while(vm.IsBusy)await Task.Delay(20);
        if(vm.Documents.Count!=2||vm.Documents.Count(x=>x.Selected)!=1||vm.Documents.Single(x=>x.Name.EndsWith(".txt")).Selected||await DocumentService.HashAsync(pdf)!=original)
            throw new InvalidOperationException("일괄 해제·원본 보존 UI 검사 실패");
        await Task.Delay(300);UpdateLayout();Capture(Path.Combine(output,"01-files.png"));
        Pages.SelectedIndex=4;SettingsNav.IsChecked=true;await Task.Delay(300);UpdateLayout();Capture(Path.Combine(output,"02-settings.png"));
        File.WriteAllText(Path.Combine(output,"result.txt"),"PASS: published EXE launched, XAML loaded, dummy import, bulk exclusion, original hash unchanged, UI screenshots captured. No SMTP attempted.");
    }
    private void Capture(string file)
    {
        var bitmap=new RenderTargetBitmap((int)ActualWidth,(int)ActualHeight,96,96,PixelFormats.Pbgra32);bitmap.Render(this);
        var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using var stream=File.Create(file);encoder.Save(stream);
    }
}
