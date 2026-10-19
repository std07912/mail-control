using System.IO;
using System.Windows;
using System.Windows.Media;
using SettlementMail.Core;

namespace SettlementMail.App;
public partial class App : Application
{
    private FileStream? workspaceLock;
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        bool smoke=e.Args.Contains("--smoke-test");
        string root=smoke ? Path.Combine(Path.GetTempPath(),"SettlementMail-Smoke-"+Guid.NewGuid().ToString("N")) : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"SettlementMailWindows");
        try
        {
            var paths=new WorkspacePaths(root);
            workspaceLock=new FileStream(paths.At("workspace.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);
            RenderOptions.ProcessRenderMode=System.Windows.Interop.RenderMode.SoftwareOnly;
            var window=new MainWindow(paths); MainWindow=window;window.Show();
            if(smoke)
            {
                string destination=e.Args.SkipWhile(x=>x!="--smoke-test").Skip(1).FirstOrDefault()??Path.Combine(root,"screens");
                try {await window.RunSmokeAsync(destination);Shutdown(0);}
                catch(Exception error){Directory.CreateDirectory(destination);File.WriteAllText(Path.Combine(destination,"smoke-error.txt"),error.ToString());Shutdown(1);}
            }
        }
        catch(IOException) {if(!smoke)MessageBox.Show("이미 실행 중이거나 저장 폴더를 사용할 수 없습니다.","정산자료 전송 도우미");Shutdown(1);}
        catch(Exception error){if(!smoke)MessageBox.Show($"시작할 수 없습니다: {error.GetType().Name}\n앱 데이터 폴더의 접근 권한과 저장공간을 확인하세요.","정산자료 전송 도우미");Shutdown(1);}
    }
    protected override void OnExit(ExitEventArgs e){workspaceLock?.Dispose();base.OnExit(e);}
}
