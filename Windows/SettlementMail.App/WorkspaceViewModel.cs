using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using SettlementMail.Core;

namespace SettlementMail.App;
public abstract class Observable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    protected void Changed([CallerMemberName]string? name=null) => PropertyChanged?.Invoke(this,new PropertyChangedEventArgs(name));
}
public sealed class DocumentRow(Document document,Action changed) : Observable
{
    public Document Value { get; private set; } = document;
    public string Name => Value.Name;
    public string Size => Units.Size(Value.Size);
    public string Extension => System.IO.Path.GetExtension(Name).TrimStart('.').ToUpperInvariant();
    public string Note => Value.Note;
    public string Hash => Value.Sha256;
    public string Origin => Value.Origin;
    public bool Selected { get => Value.Selected; set { Value = Value with { Selected=value };Changed();changed(); } }
    private string status = "";
    public string Status { get => status; set { status = value; Changed(); } }
}
public sealed class HistoryRow(MailRow row) : Observable
{
    public MailRow Value { get; private set; } = row;
    public string Job => Value.Job;
    public string Position => $"{Value.Position}/{Value.Total}";
    public string Recipient => Value.Recipient;
    public string Subject => Value.Subject;
    public string State => DeliveryState.Label(Value.State);
    public string Time => Value.Time==0 ? "대기" : DateTimeOffset.FromUnixTimeMilliseconds(Value.Time).ToLocalTime().ToString("MM-dd HH:mm:ss");
    public string Detail => string.Join(" · ",new[]{Value.Error,Value.Response}.Where(x=>!string.IsNullOrEmpty(x)));
    public string Size => Units.Size(Value.MessageBytes);
    public string Files => string.Join(Environment.NewLine,Value.Files.Select(x=>$"• {x.Name}  ({Units.Size(x.Size)})"));
    public void Update(MailRow next) { Value=next;Changed(null); }
}
public sealed class WorkspaceViewModel : Observable
{
    public ObservableCollection<DocumentRow> Documents { get; } = [];
    public ObservableCollection<HistoryRow> History { get; } = [];
    public ObservableCollection<HistoryRow> Draft { get; } = [];
    public Settings Settings { get; set; } = new();
    private bool busy;
    public bool IsBusy { get=>busy;set {busy=value;Changed();Changed(nameof(CanEdit));} }
    public bool CanEdit => !IsBusy;
    private string status="파일을 추가하거나 폴더를 끌어 놓으세요.";
    public string Status {get=>status;set{status=value;Changed();}}
    private string account="저장된 계정이 없습니다.";
    public string Account {get=>account;set{account=value;Changed();}}
    public string Summary => $"전체 {Documents.Count}개   ·   선택 {Documents.Count(x=>x.Selected)}개   ·   {Units.Size(Documents.Where(x=>x.Selected).Sum(x=>x.Value.Size))}";
    public string SelectedCount => Documents.Count(x=>x.Selected).ToString();
    public string SelectedSize => Units.Size(Documents.Where(x=>x.Selected).Sum(x=>x.Value.Size));
    public string MailSummary => $"접수 {History.Count(x=>x.Value.State is "ACCEPTED" or "CONFIRMED")}   ·   실패 {History.Count(x=>x.Value.State=="FAILED")}   ·   확인 필요 {History.Count(x=>x.Value.State=="UNCERTAIN")}";
    public Action? SelectionChanged;
    public void RefreshDocuments(IEnumerable<Document> docs,Policy? policy)
    {
        Documents.Clear();foreach(var doc in docs) Documents.Add(new DocumentRow(doc,OnSelection) {Status = !doc.Selected?"선택 제외":policy?.Problem(doc)??"전송 준비"});
        OnSelection();
    }
    public void RefreshPolicy(Policy policy)
    {
        RefreshDocuments(Documents.Select(x=>x.Value).ToList(),policy);
    }
    public void OnSelection()
    {
        Changed(nameof(Summary));Changed(nameof(SelectedCount));Changed(nameof(SelectedSize));SelectionChanged?.Invoke();
    }
    public void RefreshHistory(IEnumerable<MailRow> rows)
    {
        History.Clear();foreach(var row in rows) History.Add(new(row));Changed(nameof(MailSummary));
    }
    public void UpdateMail(MailRow row)
    {
        History.FirstOrDefault(x=>x.Value.Id==row.Id)?.Update(row);
        Status=$"메일 {row.Position}/{row.Total} · {DeliveryState.Label(row.State)}";Changed(nameof(MailSummary));
    }
}
