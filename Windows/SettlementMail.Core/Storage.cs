using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace SettlementMail.Core;

public sealed class WorkspacePaths
{
    public string Root { get; }
    public string Documents => Directory.CreateDirectory(System.IO.Path.Combine(Root, "documents")).FullName;
    public string Outbox => Directory.CreateDirectory(System.IO.Path.Combine(Root, "outbox")).FullName;
    public WorkspacePaths(string root) { Root = root; Directory.CreateDirectory(root); }
    public string At(string name) => System.IO.Path.Combine(Root, name);
    public static void WriteJson<T>(string path, T value)
    {
        string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try { File.WriteAllBytes(temp, JsonSerializer.SerializeToUtf8Bytes(value)); File.Move(temp, path, true); }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
    public static T? ReadJson<T>(string path) => File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllBytes(path)) : default;
}
public sealed class CredentialStore(WorkspacePaths paths)
{
    public void Save(Credentials credentials)
    {
        var clear = JsonSerializer.SerializeToUtf8Bytes(credentials);
        try
        {
            var encrypted = ProtectedData.Protect(clear, null, DataProtectionScope.CurrentUser);
            string temp = paths.At("account.tmp"); File.WriteAllBytes(temp, encrypted); File.Move(temp, paths.At("account.bin"), true);
        }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }
    public Credentials? Read()
    {
        if (!File.Exists(paths.At("account.bin"))) return null;
        var clear = ProtectedData.Unprotect(File.ReadAllBytes(paths.At("account.bin")), null, DataProtectionScope.CurrentUser);
        try { return JsonSerializer.Deserialize<Credentials>(clear); }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }
}
public sealed class Ledger
{
    private readonly string connectionString;
    public Ledger(WorkspacePaths paths)
    {
        connectionString = new SqliteConnectionStringBuilder { DataSource = paths.At("history.db"), Mode = SqliteOpenMode.ReadWriteCreate }.ToString();
        using var db = Open();
        using var cmd = db.CreateCommand();
        cmd.CommandText = "PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS mails(id TEXT PRIMARY KEY, batch TEXT NOT NULL, position INTEGER NOT NULL, state TEXT NOT NULL, time INTEGER NOT NULL, payload TEXT NOT NULL, UNIQUE(batch,position));";
        cmd.ExecuteNonQuery();
    }
    private SqliteConnection Open() { var db = new SqliteConnection(connectionString); db.Open(); return db; }
    private static MailRow Decode(SqliteDataReader r) => JsonSerializer.Deserialize<MailRow>(r.GetString(0))! with { State = r.GetString(1), Time = r.GetInt64(2) };
    public List<MailRow> All()
    {
        using var db = Open(); using var cmd = db.CreateCommand(); cmd.CommandText = "SELECT payload,state,time FROM mails ORDER BY batch DESC, position";
        using var r = cmd.ExecuteReader(); var result = new List<MailRow>(); while (r.Read()) result.Add(Decode(r)); return result;
    }
    public MailRow Get(string id) => All().Single(x => x.Id == id);
    public void Add(IEnumerable<MailRow> rows)
    {
        using var db = Open(); using var tx = db.BeginTransaction();
        foreach (var row in rows)
        {
            using var cmd = db.CreateCommand(); cmd.Transaction = tx;
            cmd.CommandText = "INSERT INTO mails(id,batch,position,state,time,payload) VALUES($id,$batch,$position,$state,$time,$payload)";
            cmd.Parameters.AddWithValue("$id", row.Id); cmd.Parameters.AddWithValue("$batch", row.Batch); cmd.Parameters.AddWithValue("$position", row.Position);
            cmd.Parameters.AddWithValue("$state", row.State); cmd.Parameters.AddWithValue("$time", row.Time); cmd.Parameters.AddWithValue("$payload", JsonSerializer.Serialize(row)); cmd.ExecuteNonQuery();
        }
        tx.Commit();
    }
    public bool Claim(string id) => Transition(id, "state='PENDING'", "CONNECTING");
    public bool Retry(string id) => Transition(id, "state IN ('FAILED','CANCELLED')", "PENDING");
    private bool Transition(string id, string predicate, string state)
    {
        using var db = Open(); using var cmd = db.CreateCommand();
        cmd.CommandText = $"UPDATE mails SET state=$state WHERE id=$id AND {predicate}";
        cmd.Parameters.AddWithValue("$id", id); cmd.Parameters.AddWithValue("$state", state); return cmd.ExecuteNonQuery() == 1;
    }
    public void Save(MailRow row)
    {
        using var db = Open(); using var cmd = db.CreateCommand(); cmd.CommandText = "UPDATE mails SET state=$state,time=$time,payload=$payload WHERE id=$id";
        cmd.Parameters.AddWithValue("$id", row.Id); cmd.Parameters.AddWithValue("$state", row.State); cmd.Parameters.AddWithValue("$time", row.Time);
        cmd.Parameters.AddWithValue("$payload", JsonSerializer.Serialize(row)); if (cmd.ExecuteNonQuery() != 1) throw new InvalidOperationException("발송 이력을 찾을 수 없습니다.");
    }
    public void RecoverInterrupted()
    {
        // Called only after the app acquires its exclusive workspace lock.
        foreach (var row in All().Where(r => r.State is "CONNECTING" or "SENDING"))
            Save(row with { State = row.State == "SENDING" ? "UNCERTAIN" : "FAILED", Error = "앱이 중단되었습니다. 전송 시도 후 상태는 서버 접수를 별도 확인하세요." });
    }
    public void CancelPending(string batch)
    {
        using var db = Open(); using var cmd = db.CreateCommand(); cmd.CommandText = "UPDATE mails SET state='CANCELLED' WHERE batch=$batch AND state='PENDING'";
        cmd.Parameters.AddWithValue("$batch", batch); cmd.ExecuteNonQuery();
    }
}
