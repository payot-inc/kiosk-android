using System.Diagnostics;
using System.Drawing.Imaging;
using System.IO.Pipes;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Text.Json;

namespace KioskAgent;

/// <summary>
/// 키오스크 사이드카 에이전트 — 셸(KioskShell)과 별도 프로세스로 상주하며
/// 원격 HTTP 명령을 받아 셸을 제어한다. 셸이 죽거나 행이 걸려도 에이전트는 살아있다.
///
/// 설정(exe 옆 kiosk.ini — 셸과 공유):
///   AgentPort=9100        수신 포트
///   AgentToken=...        원격 접근 토큰. 비우면 localhost 전용으로만 바인딩
///   AgentWatchdog=false   true면 셸이 꺼져 있을 때 30초 간격 자동 재시작
///
/// API (토큰: X-Token 헤더 또는 ?token= 쿼리):
///   GET  /status       에이전트/셸 상태 (shellRunning, shellResponsive, bill ...)
///   POST /reload       셸 페이지 새로고침 (파이프)
///   POST /url          본문 또는 ?url= 의 주소로 변경 — kiosk.ini 영구 저장 + 즉시 이동
///   POST /restart      셸 재시작 (정상 종료 시도 → 강제 종료 폴백)
///   POST /start        셸 시작
///   POST /stop         셸 종료 (워치독도 다시 살리지 않음)
///   POST /reboot       윈도우 재부팅 (5초 후)
///   GET  /screenshot   주 모니터 화면 캡처 (PNG)
///
/// 결제장치 API (키오스크 웹이 사용 — 같은 PC(loopback)에서는 토큰 없이 허용, CORS 개방):
///   GET  /events           SSE 스트림 — kiosk:bill-* / kiosk:card-event 푸시 (접속 즉시 지폐기 상태 스냅샷)
///   GET  /bill/status      {connected, running}
///   POST /bill/connect     시리얼 연결 (kiosk.ini SerialPort, 비우면 첫 포트 자동)
///   POST /bill/disconnect  연결 해제
///   POST /bill/run         투입구 개방 (RUN)
///   POST /bill/stop        투입구 차폐 (STOP)
///   POST /bill/write       임의 명령 — ?cmd= 또는 본문
///   GET  /card/ping        EasyCardK 설치/실행 확인 (GV 전문) — {ok}
///   POST /card/approve     신용 승인 D1 — 본문 {amount, tax?, tip?, installment?, timeoutSec?, taxOption?}
///   POST /card/cancel      승인 취소 D4 — 본문 {amount, approvalNum, approvalDate, ...}
///                          응답: EasyCardK 원본 필드 JSON (SUC/RQxx/RSxx). 실패 시 502 {ok,error}
/// </summary>
internal static class Program
{
    private const string ShellProcessName = "KioskShell";
    private const string ShellExeName = "KioskShell.exe";
    private const string PipeName = "kioskshell-cmd";

    private static readonly DateTime StartedAt = DateTime.UtcNow;
    private static readonly string BaseDir = AppContext.BaseDirectory;
    private static readonly string IniPath = Path.Combine(BaseDir, "kiosk.ini");

    private static int _port = 9100;
    private static string _token = "";
    private static bool _watchdog;
    private static string _serialPort = "";
    private static string _updateUrl = "";
    private static int _updateCheckHours = 6;
    /// <summary>운영자가 의도한 셸 상태 — /stop 하면 워치독이 되살리지 않는다</summary>
    private static volatile bool _desiredRunning = true;
    /// <summary>지폐기 — 시리얼은 사이드카가 잡고, 웹은 /bill/* + SSE로만 접근한다</summary>
    private static BillAcceptor _bill = null!;

    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(false, "KioskAgent-Singleton");
        try
        {
            if (!mutex.WaitOne(TimeSpan.FromSeconds(3))) return;
        }
        catch (AbandonedMutexException) { /* 이전 인스턴스 비정상 종료 — 이어받음 */ }

        RemoteLog.Configure(Path.Combine(BaseDir, "logs"));
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            RemoteLog.Error($"처리되지 않은 예외: {(e.ExceptionObject as Exception)?.ToString() ?? "unknown"}");

        LoadConfig();
        _bill = new BillAcceptor(_serialPort.Length > 0 ? _serialPort : null, KioskEvents.Broadcast);
        EasyCard.StartStatusRelay(KioskEvents.Broadcast); // 카드 진행상태 WS → SSE 중계
        if (_watchdog) StartWatchdog();
        Updater.Configure(_updateUrl, _updateCheckHours);
        Updater.Start();
        CronScheduler.Configure(Path.Combine(BaseDir, "kiosk-cron.json"), KioskEvents.Broadcast);
        CronScheduler.Start();
        RemoteLog.Info($"에이전트 시작 (v{Assembly.GetExecutingAssembly().GetName().Version?.ToString(3)}, port {_port}, watchdog {_watchdog})");
        RunServer();
    }

    private static void LoadConfig()
    {
        if (!File.Exists(IniPath)) return;
        foreach (var rawLine in File.ReadAllLines(IniPath))
        {
            var line = rawLine.Trim();
            if (line.Length == 0 || line.StartsWith('#') || line.StartsWith(';')) continue;
            var eq = line.IndexOf('=');
            if (eq <= 0) continue;
            var key = line[..eq].Trim().ToLowerInvariant();
            var value = line[(eq + 1)..].Trim();
            switch (key)
            {
                case "agentport" when int.TryParse(value, out var port): _port = port; break;
                case "agenttoken": _token = value; break;
                case "agentwatchdog": _watchdog = value.Equals("true", StringComparison.OrdinalIgnoreCase); break;
                case "serialport": _serialPort = value; break;
                case "updateurl": _updateUrl = value; break;
                case "updatecheckhours" when int.TryParse(value, out var hours): _updateCheckHours = hours; break;
            }
        }
    }

    // ---------- HTTP 서버 (TcpListener 기반 최소 구현 — URL ACL 불필요) ----------

    private static void RunServer()
    {
        // 토큰이 없으면 보안상 localhost 전용으로만 연다
        var address = _token.Length > 0 ? IPAddress.Any : IPAddress.Loopback;
        var listener = new TcpListener(address, _port);
        listener.Start();
        while (true)
        {
            var client = listener.AcceptTcpClient();
            _ = Task.Run(() => HandleClient(client));
        }
    }

    private static void HandleClient(TcpClient client)
    {
        var handedOff = false;
        try
        {
            var stream = client.GetStream();
            client.ReceiveTimeout = 10000;
            var request = ReadRequest(stream);
            if (request == null) return;
            var (method, path, query, headers, body) = request.Value;

            if (method == "OPTIONS")
            {
                WritePreflight(stream); // 브라우저 CORS/PNA 프리플라이트 (지폐기 API용)
                return;
            }

            if (!Authorized(client, path, headers, query))
            {
                WriteJson(stream, 401, new { ok = false, error = "unauthorized" });
                return;
            }

            handedOff = Route(client, stream, method, path, query, body);
        }
        catch { /* 연결 단위 오류는 무시 */ }
        finally
        {
            // SSE(/bill/events)는 BillEvents가 연결을 계속 들고 있으므로 닫지 않는다
            if (!handedOff) client.Dispose();
        }
    }

    private static bool Authorized(TcpClient client, string path, Dictionary<string, string> headers, Dictionary<string, string> query)
    {
        if (_token.Length == 0)
        {
            // 토큰 미설정 = localhost 바인딩이므로 항상 로컬 — 허용
            return true;
        }
        // 키오스크 화면(같은 PC의 브라우저)이 쓰는 API는 로컬 접속을 토큰 없이 허용:
        // 결제장치(/events·/bill·/card)에 더해 예약(/cron)·진단(/sysinfo·/logs)도 키오스크 웹이 쓴다.
        if ((path == "/events" || path == "/sysinfo" || path == "/logs"
                || path.StartsWith("/bill/") || path.StartsWith("/card/") || path.StartsWith("/cron"))
            && IsLoopback(client))
        {
            return true;
        }

        var given = headers.GetValueOrDefault("x-token") ?? query.GetValueOrDefault("token") ?? "";
        return given == _token;
    }

    private static bool IsLoopback(TcpClient client) =>
        client.Client.RemoteEndPoint is IPEndPoint endpoint && IPAddress.IsLoopback(endpoint.Address);

    /// <summary>true 반환 = 연결 소유권을 넘김(SSE) — 호출자가 닫으면 안 된다</summary>
    private static bool Route(TcpClient client, NetworkStream stream, string method, string path, Dictionary<string, string> query, string body)
    {
        switch (method, path)
        {
            case ("GET", "/status"):
                WriteJson(stream, 200, BuildStatus());
                break;

            case ("POST", "/reload"):
                WriteJson(stream, PipeSend("RELOAD") == "OK" ? 200 : 502,
                    new { ok = PipeSend("PING") == "PONG" });
                break;

            case ("POST", "/url"):
            {
                var url = query.GetValueOrDefault("url") ?? body.Trim();
                if (!url.StartsWith("http", StringComparison.OrdinalIgnoreCase))
                {
                    WriteJson(stream, 400, new { ok = false, error = "url 필요 (본문 또는 ?url=)" });
                    break;
                }
                UpdateIni("Url", url);
                var live = PipeSend($"URL {url}") == "OK";
                WriteJson(stream, 200, new { ok = true, url, live });
                break;
            }

            case ("POST", "/restart"):
                StopShell();
                _desiredRunning = true;
                StartShell();
                WriteJson(stream, 200, new { ok = true });
                break;

            case ("POST", "/start"):
                _desiredRunning = true;
                StartShell();
                WriteJson(stream, 200, new { ok = true });
                break;

            case ("POST", "/stop"):
                _desiredRunning = false;
                StopShell();
                WriteJson(stream, 200, new { ok = true });
                break;

            case ("POST", "/reboot"):
                RemoteLog.Warn("원격 재부팅 요청 — 5초 후 shutdown /r");
                WriteJson(stream, 200, new { ok = true, message = "5초 후 재부팅" });
                Process.Start(new ProcessStartInfo("shutdown", "/r /t 5") { CreateNoWindow = true });
                break;

            case ("POST", "/update"):
            {
                // 즉시 업데이트 확인 — 새 버전 있으면 무인설치 시작
                var version = Updater.CheckOnce(apply: true);
                RemoteLog.Info(version != null ? $"업데이트 시작: v{version}" : "업데이트 확인 — 최신");
                WriteJson(stream, 200, new { ok = true, updating = version != null, version });
                break;
            }

            case ("GET", "/screenshot"):
                WriteBytes(stream, 200, "image/png", CaptureScreen());
                break;

            case ("GET", "/sysinfo"):
                WriteJson(stream, 200, SysInfo.Collect(
                    (long)(DateTime.UtcNow - StartedAt).TotalSeconds,
                    Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0"));
                break;

            case ("GET", "/logs"):
            {
                var tail = int.TryParse(query.GetValueOrDefault("tail"), out var n) && n > 0 ? n : 500;
                var text = query.GetValueOrDefault("src") == "shell"
                    ? RemoteLog.TailShell(tail)
                    : RemoteLog.Tail(tail);
                WriteBytes(stream, 200, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes(text));
                break;
            }

            // ---------- 예약 작업 (크론 — 안드로이드 window.android.cron* 와 동일) ----------

            case ("POST", "/cron"):
                try
                {
                    var id = CronScheduler.Schedule(body);
                    WriteJson(stream, 200, new { ok = true, id });
                }
                catch (Exception e)
                {
                    WriteJson(stream, 400, new { ok = false, error = e.Message });
                }
                break;

            case ("POST", "/cron/cancel"):
            {
                var id = query.GetValueOrDefault("id") ?? body.Trim();
                WriteJson(stream, 200, new { ok = CronScheduler.Cancel(id) });
                break;
            }

            case ("GET", "/cron/list"):
                WriteBytes(stream, 200, "application/json; charset=utf-8",
                    Encoding.UTF8.GetBytes(CronScheduler.ListJson()));
                break;

            // ---------- 결제장치 (키오스크 웹이 사용) ----------

            case ("GET", "/events"):
                // SSE — 응답을 끝내지 않고 KioskEvents가 연결을 보관하며 계속 푸시한다
                KioskEvents.Attach(client, stream, _bill.Connected, _bill.Running);
                return true;

            case ("GET", "/bill/status"):
                WriteJson(stream, 200, new { connected = _bill.Connected, running = _bill.Running });
                break;

            case ("POST", "/bill/connect"):
                _bill.Connect();
                WriteJson(stream, 200, new { ok = true });
                break;

            case ("POST", "/bill/disconnect"):
                _bill.Disconnect();
                WriteJson(stream, 200, new { ok = true });
                break;

            case ("POST", "/bill/run"):
                _bill.Write("RUN");
                WriteJson(stream, 200, new { ok = true });
                break;

            case ("POST", "/bill/stop"):
                _bill.Write("STOP");
                WriteJson(stream, 200, new { ok = true });
                break;

            case ("POST", "/bill/write"):
            {
                var command = query.GetValueOrDefault("cmd") ?? body.Trim();
                if (command.Length == 0)
                {
                    WriteJson(stream, 400, new { ok = false, error = "명령 필요 (본문 또는 ?cmd=)" });
                    break;
                }
                _bill.Write(command);
                WriteJson(stream, 200, new { ok = true });
                break;
            }

            // ---------- 카드 (EasyCardK 프록시) ----------

            case ("GET", "/card/ping"):
                WriteJson(stream, 200, new { ok = EasyCard.Ping() });
                break;

            case ("POST", "/card/approve"):
            case ("POST", "/card/cancel"):
            {
                if (!EasyCard.TryAcquire())
                {
                    WriteJson(stream, 409, new { ok = false, error = "이미 진행 중인 카드 거래가 있습니다" });
                    break;
                }
                try
                {
                    // 성공 시 EasyCardK 원본 필드(SUC/RQxx/RSxx)를 그대로 JSON으로 — 해석은 웹이 한다
                    var raw = path == "/card/approve" ? EasyCard.Approve(body) : EasyCard.Cancel(body);
                    WriteJson(stream, 200, raw);
                }
                catch (JsonException)
                {
                    WriteJson(stream, 400, new { ok = false, error = "잘못된 요청 본문 (JSON 필요)" });
                }
                catch (Exception e)
                {
                    WriteJson(stream, 502, new
                    {
                        ok = false,
                        error = $"EasyCardK 호출 실패: {e.Message} — 설치/실행 확인 (127.0.0.1:8090)"
                    });
                }
                finally
                {
                    EasyCard.Release();
                }
                break;
            }

            default:
                WriteJson(stream, 404, new { ok = false, error = "unknown endpoint" });
                break;
        }
        return false;
    }

    private static object BuildStatus()
    {
        var running = Process.GetProcessesByName(ShellProcessName).Length > 0;
        return new
        {
            agentVersion = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3),
            machine = Environment.MachineName,
            agentUptimeSec = (long)(DateTime.UtcNow - StartedAt).TotalSeconds,
            shellRunning = running,
            // 떠 있는데 PING 무응답이면 행(hang) 상태 — /restart 권장
            shellResponsive = running && PipeSend("PING") == "PONG",
            desiredRunning = _desiredRunning,
            watchdog = _watchdog,
            bill = new { connected = _bill.Connected, running = _bill.Running },
            updateAvailable = Updater.AvailableVersion // 마지막 확인에서 발견한 새 버전 (없으면 null)
        };
    }

    // ---------- 셸 제어 ----------

    private static void StartShell()
    {
        if (Process.GetProcessesByName(ShellProcessName).Length > 0) return;
        Process.Start(new ProcessStartInfo
        {
            FileName = Path.Combine(BaseDir, ShellExeName),
            UseShellExecute = true
        });
    }

    private static void StopShell()
    {
        PipeSend("QUIT"); // 정상 종료 시도
        for (var i = 0; i < 6; i++)
        {
            if (Process.GetProcessesByName(ShellProcessName).Length == 0) return;
            Thread.Sleep(500);
        }
        foreach (var process in Process.GetProcessesByName(ShellProcessName))
        {
            try { process.Kill(entireProcessTree: true); } catch { /* 이미 종료 */ }
        }
    }

    private static void StartWatchdog()
    {
        new Thread(() =>
        {
            while (true)
            {
                Thread.Sleep(30000);
                try
                {
                    if (_desiredRunning && Process.GetProcessesByName(ShellProcessName).Length == 0)
                    {
                        RemoteLog.Warn("워치독: 셸이 꺼져 있어 재시작한다");
                        StartShell();
                    }
                }
                catch { /* 다음 주기에 재시도 */ }
            }
        }) { IsBackground = true, Name = "Watchdog" }.Start();
    }

    /// <summary>셸 Named Pipe로 한 줄 명령 전송, 응답 한 줄 반환 (실패 시 null)</summary>
    private static string? PipeSend(string command, int timeoutMs = 3000)
    {
        try
        {
            using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut);
            pipe.Connect(timeoutMs);
            using var writer = new StreamWriter(pipe) { AutoFlush = true };
            using var reader = new StreamReader(pipe);
            writer.WriteLine(command);
            return reader.ReadLine();
        }
        catch
        {
            return null;
        }
    }

    private static void UpdateIni(string key, string value)
    {
        var lines = File.Exists(IniPath) ? File.ReadAllLines(IniPath).ToList() : [];
        var index = lines.FindIndex(l => l.TrimStart().StartsWith($"{key}=", StringComparison.OrdinalIgnoreCase));
        if (index >= 0) lines[index] = $"{key}={value}";
        else lines.Add($"{key}={value}");
        File.WriteAllLines(IniPath, lines);
    }

    private static byte[] CaptureScreen()
    {
        var bounds = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
        using var bitmap = new Bitmap(bounds.Width, bounds.Height);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.CopyFromScreen(bounds.Location, Point.Empty, bounds.Size);
        }
        using var memory = new MemoryStream();
        bitmap.Save(memory, ImageFormat.Png);
        return memory.ToArray();
    }

    // ---------- 최소 HTTP 파서/응답 ----------

    private static (string method, string path, Dictionary<string, string> query,
        Dictionary<string, string> headers, string body)? ReadRequest(NetworkStream stream)
    {
        // 헤더 끝(\r\n\r\n)까지 읽기
        var raw = new MemoryStream();
        var buffer = new byte[4096];
        var headerEnd = -1;
        while (headerEnd < 0)
        {
            var n = stream.Read(buffer, 0, buffer.Length);
            if (n <= 0) return null;
            raw.Write(buffer, 0, n);
            if (raw.Length > 64 * 1024) return null; // 비정상 요청 차단
            headerEnd = FindHeaderEnd(raw.GetBuffer(), (int)raw.Length);
        }

        var headerText = Encoding.ASCII.GetString(raw.GetBuffer(), 0, headerEnd);
        var lines = headerText.Split("\r\n");
        var requestParts = lines[0].Split(' ');
        if (requestParts.Length < 2) return null;
        var method = requestParts[0].ToUpperInvariant();

        var target = requestParts[1];
        var questionMark = target.IndexOf('?');
        var path = questionMark < 0 ? target : target[..questionMark];
        var query = new Dictionary<string, string>();
        if (questionMark >= 0)
        {
            foreach (var pair in target[(questionMark + 1)..].Split('&'))
            {
                var eq = pair.IndexOf('=');
                if (eq > 0) query[WebUtility.UrlDecode(pair[..eq])] = WebUtility.UrlDecode(pair[(eq + 1)..]);
            }
        }

        var headers = new Dictionary<string, string>();
        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon > 0) headers[line[..colon].Trim().ToLowerInvariant()] = line[(colon + 1)..].Trim();
        }

        // 본문 (Content-Length 기준)
        var body = "";
        if (int.TryParse(headers.GetValueOrDefault("content-length"), out var contentLength) && contentLength > 0)
        {
            if (contentLength > 64 * 1024) return null;
            var bodyBytes = new MemoryStream();
            var already = (int)raw.Length - (headerEnd + 4);
            if (already > 0) bodyBytes.Write(raw.GetBuffer(), headerEnd + 4, already);
            while (bodyBytes.Length < contentLength)
            {
                var n = stream.Read(buffer, 0, (int)Math.Min(buffer.Length, contentLength - bodyBytes.Length));
                if (n <= 0) break;
                bodyBytes.Write(buffer, 0, n);
            }
            body = Encoding.UTF8.GetString(bodyBytes.ToArray());
        }

        return (method, path, query, headers, body);
    }

    private static int FindHeaderEnd(byte[] data, int length)
    {
        for (var i = 3; i < length; i++)
        {
            if (data[i - 3] == '\r' && data[i - 2] == '\n' && data[i - 1] == '\r' && data[i] == '\n')
                return i - 3;
        }
        return -1;
    }

    private static void WriteJson(NetworkStream stream, int status, object payload) =>
        WriteBytes(stream, status, "application/json; charset=utf-8",
            JsonSerializer.SerializeToUtf8Bytes(payload));

    private static void WriteBytes(NetworkStream stream, int status, string contentType, byte[] body)
    {
        var reason = status switch { 200 => "OK", 400 => "Bad Request", 401 => "Unauthorized", 404 => "Not Found", 502 => "Bad Gateway", _ => "Error" };
        var header = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {status} {reason}\r\nContent-Type: {contentType}\r\nContent-Length: {body.Length}\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
        stream.Write(header);
        stream.Write(body);
        stream.Flush();
    }

    /// <summary>CORS 프리플라이트 — 키오스크 페이지(다른 오리진)가 /bill/* 를 fetch/SSE 하도록 허용.
    /// Allow-Private-Network 는 https 페이지 → 127.0.0.1 (Chrome PNA) 대응.</summary>
    private static void WritePreflight(NetworkStream stream)
    {
        var header = Encoding.ASCII.GetBytes(
            "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Allow-Private-Network: true\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Connection: close\r\n\r\n");
        stream.Write(header);
        stream.Flush();
    }
}
