using System.Text;

namespace KioskShell;

/// <summary>
/// 셸 진단 로그 — logs/shell.log 에 남긴다 (에이전트와 같은 BaseDir/logs 공유).
/// 사이드카가 GET /logs?src=shell 로 원격 조회한다. 크기 초과 시 1회 회전.
/// </summary>
internal static class Log
{
    private const long MaxBytes = 512 * 1024;
    private static readonly object Lock = new();
    private static readonly string Path = System.IO.Path.Combine(AppContext.BaseDirectory, "logs", "shell.log");

    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message) => Write("ERROR", message);

    private static void Write(string level, string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{level}] {message}{Environment.NewLine}";
        lock (Lock)
        {
            try
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
                var info = new FileInfo(Path);
                if (info.Exists && info.Length > MaxBytes)
                {
                    var backup = Path + ".1";
                    if (File.Exists(backup)) File.Delete(backup);
                    File.Move(Path, backup);
                }
                File.AppendAllText(Path, line, Encoding.UTF8);
            }
            catch { /* 로깅 실패는 무시 */ }
        }
    }
}
