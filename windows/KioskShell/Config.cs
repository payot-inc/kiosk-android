namespace KioskShell;

/// <summary>
/// 셸 설정 — exe 옆의 kiosk.ini (KEY=VALUE) + 명령행 인자(1번째 = URL)로 결정.
/// </summary>
public sealed class Config
{
    /// <summary>WebView가 띄울 Nuxt 페이지 URL</summary>
    public string Url { get; private set; } = "http://localhost:3000/";

    /// <summary>F12 개발자도구 허용 여부</summary>
    public bool DevTools { get; private set; }

    /// <summary>창을 항상 위로 (키오스크 기본 true)</summary>
    public bool TopMost { get; private set; } = true;

    public static Config Load(string[] args)
    {
        var config = new Config();

        var ini = Path.Combine(AppContext.BaseDirectory, "kiosk.ini");
        if (File.Exists(ini))
        {
            foreach (var rawLine in File.ReadAllLines(ini))
            {
                var line = rawLine.Trim();
                if (line.Length == 0 || line.StartsWith('#') || line.StartsWith(';')) continue;
                var eq = line.IndexOf('=');
                if (eq <= 0) continue;
                var key = line[..eq].Trim();
                var value = line[(eq + 1)..].Trim();
                switch (key.ToLowerInvariant())
                {
                    case "url": config.Url = value; break;
                    case "devtools": config.DevTools = value.Equals("true", StringComparison.OrdinalIgnoreCase); break;
                    case "topmost": config.TopMost = !value.Equals("false", StringComparison.OrdinalIgnoreCase); break;
                }
            }
        }

        if (args.Length > 0 && args[0].StartsWith("http", StringComparison.OrdinalIgnoreCase))
        {
            config.Url = args[0];
        }
        return config;
    }
}
