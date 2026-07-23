using System.IO.Pipes;

namespace KioskShell;

/// <summary>
/// 사이드카(KioskAgent)가 셸에 명령을 보내는 로컬 Named Pipe 채널.
/// 한 줄 명령 → 한 줄 응답:
///   PING        → PONG          (살아있음/행 여부 확인)
///   RELOAD      → OK            (설정 URL 다시 로드)
///   URL {주소}  → OK            (해당 주소로 즉시 이동 — 영구 저장은 에이전트가 kiosk.ini에)
///   QUIT        → OK            (셸 정상 종료)
/// </summary>
public static class CommandPipe
{
    public const string PipeName = "kioskshell-cmd";

    public static void Start(MainForm form)
    {
        new Thread(() => Loop(form)) { IsBackground = true, Name = "CommandPipe" }.Start();
    }

    private static void Loop(MainForm form)
    {
        while (true)
        {
            try
            {
                using var server = new NamedPipeServerStream(
                    PipeName, PipeDirection.InOut, maxNumberOfServerInstances: 1);
                server.WaitForConnection();

                using var reader = new StreamReader(server);
                using var writer = new StreamWriter(server) { AutoFlush = true };
                var line = reader.ReadLine()?.Trim();
                if (string.IsNullOrEmpty(line)) continue;

                var space = line.IndexOf(' ');
                var command = (space < 0 ? line : line[..space]).ToUpperInvariant();
                var argument = space < 0 ? "" : line[(space + 1)..].Trim();

                switch (command)
                {
                    case "PING":
                        writer.WriteLine("PONG");
                        break;
                    case "RELOAD":
                        form.NavigateHome();
                        writer.WriteLine("OK");
                        break;
                    case "URL" when argument.Length > 0:
                        form.NavigateTo(argument);
                        writer.WriteLine("OK");
                        break;
                    case "QUIT":
                        writer.WriteLine("OK");
                        form.QuitShell();
                        break;
                    default:
                        writer.WriteLine("ERR unknown command");
                        break;
                }
            }
            catch
            {
                // 끊긴 연결/IO 오류는 무시하고 다음 연결 대기
                Thread.Sleep(200);
            }
        }
    }
}
