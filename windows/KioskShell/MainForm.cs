using System.Runtime.InteropServices;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace KioskShell;

/// <summary>
/// 전체화면 WebView2 키오스크 셸 — 안드로이드 MainActivity와 같은 역할.
/// - 전체화면(테두리 없음, 항상 위), 화면 항상 켜짐(SetThreadExecutionState)
/// - 페이지 로드 실패 시 5초 간격 자동 재시도
/// - WebView2 프로세스 사망 시 셸 재시작으로 복구
/// - Alt+F4 종료 허용 (Ctrl+Alt+Shift+Q 도 동작)
/// </summary>
public class MainForm : Form
{
    /// <summary>JS로 주입되는 부트스트랩 — window.windows 정의 + 종료 단축키.
    /// 지폐기는 사이드카(KioskAgent)가 처리하므로 브리지에 없다 (웹이 /bill/* + SSE 직접 사용).</summary>
    private const string Bootstrap = """
        (function () {
          const host = chrome.webview.hostObjects.sync.kiosk;
          window.windows = {
            getInfo: () => host.GetInfo(),
            reload: () => host.Reload(),
            restartApp: () => host.RestartApp(),
          };
          document.addEventListener('keydown', (e) => {
            if (e.ctrlKey && e.altKey && e.shiftKey && e.code === 'KeyQ') host.Quit();
          });
        })();
        """;

    private readonly Config _config;
    private readonly WebView2 _webView = new() { Dock = DockStyle.Fill };
    private Bridge? _bridge;
    private System.Windows.Forms.Timer? _retryTimer;

    public MainForm(Config config)
    {
        _config = config;
        Text = "Kiosk";
        FormBorderStyle = FormBorderStyle.None;
        WindowState = FormWindowState.Maximized;
        TopMost = config.TopMost;
        BackColor = Color.Black;
        Controls.Add(_webView);

        Load += async (_, _) => await InitWebViewAsync();
        CommandPipe.Start(this); // 사이드카(KioskAgent) 원격 명령 수신
        FormClosing += (_, _) => AllowSleep();
    }

    // ---------- 브리지에서 호출 ----------

    public void NavigateHome() => BeginInvoke(() => _webView.CoreWebView2?.Navigate(_config.Url));

    /// <summary>임의 URL로 이동 (사이드카 원격 명령용)</summary>
    public void NavigateTo(string url) => BeginInvoke(() => _webView.CoreWebView2?.Navigate(url));

    public void QuitShell() => BeginInvoke(Close);

    // ---------- 내부 ----------

    private async Task InitWebViewAsync()
    {
        KeepScreenOn();

        var userData = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KioskShell");
        var environment = await CoreWebView2Environment.CreateAsync(null, userData);
        await _webView.EnsureCoreWebView2Async(environment);

        var core = _webView.CoreWebView2;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.IsZoomControlEnabled = false;
        core.Settings.AreDevToolsEnabled = _config.DevTools;
        core.Settings.AreBrowserAcceleratorKeysEnabled = _config.DevTools;

        _bridge = new Bridge(this, _config);
        core.AddHostObjectToScript("kiosk", _bridge);
        await core.AddScriptToExecuteOnDocumentCreatedAsync(Bootstrap);

        core.NewWindowRequested += (_, e) => e.Handled = true; // 팝업 차단(같은 창 유지)
        core.NavigationCompleted += (_, e) =>
        {
            if (e.IsSuccess)
            {
                StopRetry();
            }
            else
            {
                Log.Warn($"페이지 로드 실패({e.WebErrorStatus}) — 5초 후 재시도: {_config.Url}");
                ScheduleRetry(); // 서버 미기동 등 — 5초 간격 자동 재시도
            }
        };
        core.ProcessFailed += (_, e) =>
        {
            Log.Error($"WebView2 프로세스 사망({e.ProcessFailedKind}) — 셸 재시작");
            Program.Relaunch(); // 렌더러/브라우저 프로세스 사망 → 셸 재시작
        };

        core.Navigate(_config.Url);
    }

    private void ScheduleRetry()
    {
        if (_retryTimer != null) return;
        _retryTimer = new System.Windows.Forms.Timer { Interval = 5000 };
        _retryTimer.Tick += (_, _) => _webView.CoreWebView2?.Navigate(_config.Url);
        _retryTimer.Start();
    }

    private void StopRetry()
    {
        _retryTimer?.Stop();
        _retryTimer?.Dispose();
        _retryTimer = null;
    }

    // ---------- 상시 켜짐 ----------

    private const uint ES_CONTINUOUS = 0x80000000;
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;
    private const uint ES_DISPLAY_REQUIRED = 0x00000002;

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint esFlags);

    private static void KeepScreenOn() =>
        SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);

    private static void AllowSleep() => SetThreadExecutionState(ES_CONTINUOUS);
}
