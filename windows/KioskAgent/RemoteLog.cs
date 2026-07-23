using System.Text;

namespace KioskAgent;

/// <summary>
/// 원격 진단용 파일 로그 — 에이전트의 주요 이벤트(시작/셸 제어/업데이트/재부팅/오류)를
/// logs/agent.log 에 남긴다. 크기 초과 시 logs/agent.log.1 로 1회 회전한다.
/// 원격에서는 GET /logs 로 최근 줄을 받아본다 (셸 로그는 ?src=shell → logs/shell.log).
/// 안드로이드는 HTTP 서버가 없어 window.android.getLogs() 브리지로 같은 역할을 한다.
/// </summary>
internal static class RemoteLog
{
    private const long MaxBytes = 512 * 1024;
    private static readonly object Lock = new();
    private static string _path = "";
    private static string _shellPath = "";

    public static void Configure(string logDir)
    {
        try
        {
            Directory.CreateDirectory(logDir);
            _path = Path.Combine(logDir, "agent.log");
            _shellPath = Path.Combine(logDir, "shell.log");
        }
        catch { /* 디렉토리 생성 실패 시 로깅 비활성 */ }
    }

    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message) => Write("ERROR", message);

    private static void Write(string level, string message)
    {
        if (_path.Length == 0) return;
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{level}] {message}{Environment.NewLine}";
        lock (Lock)
        {
            try
            {
                var info = new FileInfo(_path);
                if (info.Exists && info.Length > MaxBytes)
                {
                    var backup = _path + ".1";
                    if (File.Exists(backup)) File.Delete(backup);
                    File.Move(_path, backup);
                }
                File.AppendAllText(_path, line, Encoding.UTF8);
            }
            catch { /* 로깅 실패는 무시 */ }
        }
    }

    /// <summary>에이전트 로그 최근 maxLines 줄 (회전본 포함)</summary>
    public static string Tail(int maxLines) => TailFile(_path, maxLines);

    /// <summary>셸 로그 최근 maxLines 줄 (logs/shell.log)</summary>
    public static string TailShell(int maxLines) => TailFile(_shellPath, maxLines);

    private static string TailFile(string path, int maxLines)
    {
        if (path.Length == 0) return "";
        var lines = new List<string>();
        foreach (var candidate in new[] { path + ".1", path }) // 회전본(오래된 것) 먼저
        {
            if (File.Exists(candidate))
            {
                try { lines.AddRange(File.ReadLines(candidate)); } catch { /* 무시 */ }
            }
        }
        if (maxLines > 0 && lines.Count > maxLines)
            lines = lines.GetRange(lines.Count - maxLines, maxLines);
        return string.Join("\n", lines);
    }
}
