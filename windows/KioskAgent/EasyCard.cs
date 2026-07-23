using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace KioskAgent;

/// <summary>
/// KICC EasyCardK 프록시 — 웹 대신 사이드카가 EasyCardK를 상대한다.
///
/// EasyCardK는 localhost 가상 웹서버(127.0.0.1:8090)이고 응답이 홑따옴표 JS 리터럴이라
/// (cb({'SUC':'00',...})) 브라우저에서는 JSONP가 필요했지만, 여기서는 HTTP로 받아
/// 직접 파싱해 표준 JSON으로 웹에 돌려준다. 진행상태 웹소켓(ws://127.0.0.1:37445)도
/// 사이드카가 구독해 SSE(kiosk:card-event)로 중계한다.
///
/// 전문 스펙: guide/windows/EASYCARD_POS_Interface_Spec_*.pdf
///   3.1 요청전문(^구분 필드), 3.2 응답전문(SUC/RQxx/RSxx), 4.1-(2) 웹소켓
/// </summary>
public static class EasyCard
{
    private const string HttpUrl = "http://127.0.0.1:8090/";
    private const string WsUrl = "ws://127.0.0.1:37445/easycardk";

    // 거래 자체 타임아웃은 요청마다 CTS로 건다 (카드 대기 최대 240초)
    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };
    private static readonly SemaphoreSlim TranLock = new(1, 1);

    /// <summary>단말은 동시 거래 불가 — 진행 중이면 false (호출자가 409 응답)</summary>
    public static bool TryAcquire() => TranLock.Wait(0);

    public static void Release() => TranLock.Release();

    /// <summary>신용 승인 (전문구분 D1) — 본문 JSON: {amount, tax?, tip?, installment?, timeoutSec?, taxOption?}</summary>
    public static Dictionary<string, string> Approve(string bodyJson)
    {
        using var doc = JsonDocument.Parse(bodyJson);
        var root = doc.RootElement;
        var timeoutSec = TimeoutSec(root);
        var req = BuildReq(new Dictionary<int, string>
        {
            [1] = "D1",
            [3] = Int(root, "amount").ToString(),
            [4] = Int(root, "installment").ToString("00"),
            [12] = timeoutSec.ToString(),
            [13] = TaxField(root, allowAuto: true),
            [16] = "40",
            [23] = Int(root, "tip") is var tip and > 0 ? tip.ToString() : "",
            [24] = "UTF-8"
        });
        return Request(req, (timeoutSec + 60) * 1000);
    }

    /// <summary>승인 취소 (전문구분 D4) — 본문 JSON에 approvalNum/approvalDate(YYMMDD~) 필요</summary>
    public static Dictionary<string, string> Cancel(string bodyJson)
    {
        using var doc = JsonDocument.Parse(bodyJson);
        var root = doc.RootElement;
        var timeoutSec = TimeoutSec(root);
        var approvalDate = Str(root, "approvalDate");
        var req = BuildReq(new Dictionary<int, string>
        {
            [1] = "D4",
            [3] = Int(root, "amount").ToString(),
            [4] = Int(root, "installment").ToString("00"),
            [5] = approvalDate.Length > 6 ? approvalDate[..6] : approvalDate,
            [6] = Str(root, "approvalNum"),
            [12] = timeoutSec.ToString(),
            [13] = TaxField(root, allowAuto: false),
            [16] = "40",
            [24] = "UTF-8"
        });
        return Request(req, (timeoutSec + 60) * 1000);
    }

    /// <summary>EasyCardK 설치/실행 확인 — 'GV'(버전 정보) 전문으로 ping</summary>
    public static bool Ping()
    {
        try
        {
            return Request("GV^", 3000).GetValueOrDefault("SUC") == "00";
        }
        catch
        {
            return false;
        }
    }

    // ---------- EasyCardK HTTP + JS 리터럴 파싱 ----------

    private static Dictionary<string, string> Request(string req, int timeoutMs)
    {
        var url = $"{HttpUrl}?callback=cb&REQ={Uri.EscapeDataString(req)}";
        using var cts = new CancellationTokenSource(timeoutMs);
        // 응답 헤더에 charset이 없을 수 있어 바이트로 받아 UTF-8 고정 디코드
        var bytes = Http.GetByteArrayAsync(url, cts.Token).GetAwaiter().GetResult();
        return ParseJsLiteral(Encoding.UTF8.GetString(bytes));
    }

    /// <summary>^ 구분 요청전문 생성 (1-base 필드번호 → 값, 최소 24필드)</summary>
    private static string BuildReq(Dictionary<int, string> fields)
    {
        var max = Math.Max(24, fields.Keys.Max());
        var parts = new string[max];
        for (var i = 1; i <= max; i++) parts[i - 1] = fields.GetValueOrDefault(i, "");
        return string.Join("^", parts) + "^";
    }

    /// <summary>cb({'SUC':'00','RS12':'카드명',...}) → 사전. 홑따옴표 문자열을 순서대로 키/값 쌍으로 읽는다.</summary>
    private static Dictionary<string, string> ParseJsLiteral(string text)
    {
        var start = text.IndexOf('{');
        var end = text.LastIndexOf('}');
        if (start < 0 || end <= start) throw new FormatException($"EasyCardK 응답 형식 오류: {Truncate(text)}");
        var body = text.Substring(start + 1, end - start - 1);

        var dict = new Dictionary<string, string>();
        var i = 0;
        string? ReadQuoted()
        {
            while (i < body.Length && body[i] != '\'') i++;
            if (i >= body.Length) return null;
            i++;
            var sb = new StringBuilder();
            while (i < body.Length)
            {
                var c = body[i++];
                if (c == '\\' && i < body.Length) { sb.Append(body[i++]); continue; }
                if (c == '\'') return sb.ToString();
                sb.Append(c);
            }
            return null; // 닫는 따옴표 없음 — 손상된 응답
        }
        while (ReadQuoted() is { } key && ReadQuoted() is { } value)
        {
            dict[key] = value;
        }
        if (dict.Count == 0) throw new FormatException($"EasyCardK 응답 파싱 실패: {Truncate(text)}");
        return dict;
    }

    private static string Truncate(string s) => s.Length <= 120 ? s : s[..120] + "...";

    // ---------- 본문 JSON 헬퍼 ----------

    private static int TimeoutSec(JsonElement root)
    {
        var sec = Int(root, "timeoutSec");
        return sec <= 0 ? 30 : Math.Min(sec, 240);
    }

    private static int Int(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetInt32() : 0;

    private static string Str(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    /// <summary>부가세 필드(13): 'A' 자동계산(승인만) / 'M'+금액 수동</summary>
    private static string TaxField(JsonElement root, bool allowAuto)
    {
        if (allowAuto && Str(root, "taxOption") == "A") return "A";
        return $"M{Int(root, "tax")}";
    }

    // ---------- 진행상태 웹소켓 → SSE 중계 ----------

    /// <summary>EasyCardK 진행상태(1000 승인 시작 ...)를 kiosk:card-event 로 중계.
    /// 미실행/미지원(1.2.0.74 미만)이면 10초 간격 재접속만 반복한다.</summary>
    public static void StartStatusRelay(Action<string, object> emit)
    {
        new Thread(() =>
        {
            var buffer = new byte[8192];
            while (true)
            {
                try
                {
                    using var ws = new ClientWebSocket();
                    ws.ConnectAsync(new Uri(WsUrl), CancellationToken.None).GetAwaiter().GetResult();
                    var message = new StringBuilder();
                    while (ws.State == WebSocketState.Open)
                    {
                        var result = ws.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None)
                            .GetAwaiter().GetResult();
                        if (result.MessageType == WebSocketMessageType.Close) break;
                        message.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
                        if (!result.EndOfMessage) continue;
                        RelayStatus(message.ToString(), emit);
                        message.Clear();
                    }
                }
                catch { /* EasyCardK 미기동 등 — 아래에서 재시도 */ }
                Thread.Sleep(10000);
            }
        }) { IsBackground = true, Name = "EasyCardStatus" }.Start();
    }

    private static void RelayStatus(string text, Action<string, object> emit)
    {
        try
        {
            using var doc = JsonDocument.Parse(text);
            var code = Str(doc.RootElement, "MsgCd");
            if (code.Length == 0) return;
            // 안드로이드 EasyCardA의 EVENT_CODE/EVENT_MSG와 같은 모양으로 정규화
            emit("kiosk:card-event", new { EVENT_CODE = code, EVENT_MSG = Str(doc.RootElement, "MsgData") });
        }
        catch { /* 형식 밖 메시지 무시 */ }
    }
}
