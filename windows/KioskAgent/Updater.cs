using System.Diagnostics;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;

namespace KioskAgent;

/// <summary>
/// 윈도우 자가 업데이트 — 사이드카가 매니페스트를 주기적으로 확인하고,
/// windows.version 이 현재 설치본보다 높으면 설치파일(NSIS)을 받아 무인설치(/S)한다.
/// 무인설치 과정에서 셸/에이전트가 종료됐다가, 설치 끝에 새 버전이 자동 재실행된다.
///
/// 설정(kiosk.ini): UpdateUrl=(매니페스트 URL), UpdateCheckHours=(0이면 비활성, 기본 6)
/// 매니페스트 형식: deploy/manifest.example.json
/// </summary>
public static class Updater
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(60) };
    private static string _url = "";
    private static int _checkHours = 6;

    /// <summary>마지막 확인에서 발견한 최신 버전 정보 (/status 노출용)</summary>
    public static string? AvailableVersion { get; private set; }

    public static void Configure(string url, int checkHours)
    {
        _url = url;
        _checkHours = checkHours;
    }

    public static void Start()
    {
        if (string.IsNullOrWhiteSpace(_url) || _checkHours <= 0) return;
        new Thread(Loop) { IsBackground = true, Name = "Updater" }.Start();
    }

    private static void Loop()
    {
        // 부팅 직후 네트워크 안정 대기
        Thread.Sleep(TimeSpan.FromSeconds(30));
        while (true)
        {
            try { CheckOnce(apply: true); }
            catch { /* 다음 주기에 재시도 */ }
            Thread.Sleep(TimeSpan.FromHours(_checkHours));
        }
    }

    /// <summary>현재 설치본보다 높은 버전이 있으면 버전 문자열 반환, 없으면 null. apply=true면 설치 실행.</summary>
    public static string? CheckOnce(bool apply)
    {
        if (string.IsNullOrWhiteSpace(_url)) return null;

        var json = Http.GetStringAsync(_url).GetAwaiter().GetResult();
        using var doc = JsonDocument.Parse(json);
        if (!doc.RootElement.TryGetProperty("windows", out var win)) return null;

        var latestText = win.TryGetProperty("version", out var v) ? v.GetString() : null;
        var downloadUrl = win.TryGetProperty("url", out var u) ? u.GetString() : null;
        if (latestText == null || downloadUrl == null) return null;
        if (!Version.TryParse(latestText, out var latest)) return null;

        var current = Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0, 0);
        // 어셈블리 버전은 4자리(x.y.z.0)라 3자리로 맞춰 비교
        var currentShort = new Version(current.Major, current.Minor, current.Build);
        if (latest <= currentShort)
        {
            AvailableVersion = null;
            return null;
        }

        AvailableVersion = latestText;
        if (apply) DownloadAndInstall(downloadUrl, latestText);
        return latestText;
    }

    private static void DownloadAndInstall(string url, string version)
    {
        var setup = Path.Combine(Path.GetTempPath(), $"KioskShell-Setup-{version}.exe");
        using (var response = Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult())
        using (var src = response.Content.ReadAsStream())
        using (var dst = File.Create(setup))
        {
            src.CopyTo(dst);
        }

        // NSIS 무인설치: 같은 디렉토리에 덮어쓰며 셸/에이전트를 종료 후 교체하고,
        // 설치 끝의 자동실행 섹션이 새 버전을 다시 띄운다. 에이전트는 별도 프로세스로
        // 설치를 시작했으므로 자신이 taskkill 당해도 설치는 계속된다.
        Process.Start(new ProcessStartInfo
        {
            FileName = setup,
            Arguments = "/S",
            UseShellExecute = true
        });
    }
}
