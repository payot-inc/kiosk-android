using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace KioskShell;

/// <summary>
/// WebView2 호스트 객체 — JS에서 chrome.webview.hostObjects.sync.kiosk 로 접근.
/// 부트스트랩 스크립트(MainForm.Bootstrap)가 이를 감싸 window.windows 를 정의해
/// 안드로이드 window.android 와 같은 모양의 인터페이스를 제공한다.
/// 지폐기는 셸이 아니라 사이드카(KioskAgent)가 시리얼로 처리한다 — 웹이 직접
/// 127.0.0.1:9100 /bill/* + SSE 를 쓰므로 여기에는 지폐기 메서드가 없다.
/// </summary>
[ComVisible(true)]
[ClassInterface(ClassInterfaceType.AutoDual)]
public class Bridge
{
    private readonly MainForm _form;
    private readonly Config _config;

    public Bridge(MainForm form, Config config)
    {
        _form = form;
        _config = config;
    }

    public string GetInfo() => JsonSerializer.Serialize(new
    {
        appVersion = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0",
        shell = "windows",
        url = _config.Url
    });

    public void Reload() => _form.NavigateHome();

    /// <summary>셸 프로세스 재시작 — 안드로이드 restartApp과 동일</summary>
    public void RestartApp() => Program.Relaunch();

    /// <summary>키오스크 종료 (Ctrl+Alt+Shift+Q 관리자 단축키에서 호출)</summary>
    public void Quit() => _form.QuitShell();
}
