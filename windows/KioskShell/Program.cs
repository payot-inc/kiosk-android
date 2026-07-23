using System.Diagnostics;

namespace KioskShell;

/// <summary>
/// 키오스크 무인 운영용 엔트리포인트.
/// - 단일 인스턴스 (Mutex)
/// - 처리되지 않은 예외 → 로그 남기고 1.5초 뒤 자동 재실행 (안드로이드 KioskApp과 동일한 동작)
/// </summary>
internal static class Program
{
    private const string MutexName = "KioskShell-Singleton";
    private static Mutex? _mutex;

    [STAThread]
    private static void Main(string[] args)
    {
        // 크래시 재실행 시 이전 프로세스가 죽는 동안 잠깐 겹칠 수 있어 5초까지 대기
        _mutex = new Mutex(false, MutexName);
        try
        {
            if (!_mutex.WaitOne(TimeSpan.FromSeconds(5))) return;
        }
        catch (AbandonedMutexException)
        {
            // 이전 인스턴스가 비정상 종료 — 그대로 이어받는다
        }

        var config = Config.Load(args);
        Log.Info($"셸 시작 (v{System.Reflection.Assembly.GetExecutingAssembly().GetName().Version?.ToString(3)}, url {config.Url})");

        Application.ThreadException += (_, e) => CrashRestart(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => CrashRestart(e.ExceptionObject as Exception);

        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new MainForm(config));
    }

    /// <summary>셸 프로세스 재시작 (브리지 restartApp / 크래시 핸들러 공용)</summary>
    public static void Relaunch()
    {
        try { _mutex?.ReleaseMutex(); } catch { /* 미보유 상태면 무시 */ }
        Process.Start(new ProcessStartInfo
        {
            FileName = Environment.ProcessPath!,
            UseShellExecute = true
        });
        Environment.Exit(10);
    }

    private static void CrashRestart(Exception? e)
    {
        Log.Error($"셸 크래시 — 1.5초 뒤 재실행: {e}");
        try
        {
            File.AppendAllText(
                Path.Combine(AppContext.BaseDirectory, "kiosk-crash.log"),
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {e}\n\n");
        }
        catch { /* 로그 실패는 무시 */ }
        Thread.Sleep(1500);
        Relaunch();
    }
}
