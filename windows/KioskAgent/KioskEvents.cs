using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace KioskAgent;

/// <summary>
/// 키오스크 이벤트 SSE 브로드캐스터 — GET /events 로 붙은 브라우저들에
/// "data: {"event":"kiosk:*","detail":{...}}" 프레임을 푸시한다.
/// 지폐기(kiosk:bill-*)와 카드 진행상태(kiosk:card-event)가 한 스트림으로 흐르고,
/// 웹(agentWin.ts)이 이를 안드로이드와 동일한 window CustomEvent로 재발행한다.
/// </summary>
internal static class KioskEvents
{
    private static readonly object Lock = new();
    private static readonly List<(TcpClient Client, NetworkStream Stream)> Clients = [];

    /// <summary>SSE 클라이언트가 하나라도 붙어 있는지 — 크론 보류 큐 전달 여부 판단용</summary>
    public static bool HasClients
    {
        get { lock (Lock) return Clients.Count > 0; }
    }

    static KioskEvents()
    {
        // 15초 주기 SSE 주석 핑 — 죽은 연결 회수 + 중간 프록시 타임아웃 방지
        new Thread(() =>
        {
            var ping = Encoding.ASCII.GetBytes(": ping\n\n");
            while (true)
            {
                Thread.Sleep(15000);
                Send(ping);
            }
        }) { IsBackground = true, Name = "KioskEventsPing" }.Start();
    }

    /// <summary>SSE 응답 헤더 + 지폐기 상태 스냅샷을 쓰고 연결 소유권을 가져간다</summary>
    public static void Attach(TcpClient client, NetworkStream stream, bool billConnected, bool billRunning)
    {
        var header = Encoding.ASCII.GetBytes(
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: keep-alive\r\n\r\n");
        try
        {
            client.SendTimeout = 2000;
            stream.Write(header);
            // 접속 즉시 현재 상태를 내려 웹의 connected/running ref를 동기화한다
            stream.Write(Frame("kiosk:bill-connection", new { connected = billConnected }));
            stream.Write(Frame("kiosk:bill-status", new { running = billRunning }));
            stream.Flush();
        }
        catch
        {
            try { client.Dispose(); } catch { /* ignore */ }
            return;
        }
        lock (Lock) Clients.Add((client, stream));
        // 이 클라이언트가 붙기 전에 발화한 크론 이벤트가 큐에 있으면 지금 전달한다 (유실 방지)
        CronScheduler.FlushPending();
    }

    public static void Broadcast(string eventName, object detail) => Send(Frame(eventName, detail));

    private static byte[] Frame(string eventName, object detail) =>
        Encoding.UTF8.GetBytes($"data: {JsonSerializer.Serialize(new { @event = eventName, detail })}\n\n");

    private static void Send(byte[] payload)
    {
        lock (Lock)
        {
            for (var i = Clients.Count - 1; i >= 0; i--)
            {
                try
                {
                    Clients[i].Stream.Write(payload);
                    Clients[i].Stream.Flush();
                }
                catch
                {
                    try { Clients[i].Client.Dispose(); } catch { /* ignore */ }
                    Clients.RemoveAt(i);
                }
            }
        }
    }
}
