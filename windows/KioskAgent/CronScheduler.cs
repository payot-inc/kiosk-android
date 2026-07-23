using System.Text.Json.Nodes;

namespace KioskAgent;

/// <summary>
/// 사이드카 예약 작업(크론) — 안드로이드 CronScheduler.kt 와 동일한 모델/API.
/// 지정 시각(epoch ms)이 되면 kiosk:cron 이벤트({id, data, firedAt})를 SSE로 푸시한다.
/// 반복(daily/ms 간격)을 지원하고, 예약은 파일(kiosk-cron.json)로 영구 저장되어
/// 에이전트/PC 재부팅에도 유지된다.
///
/// 유실 방지: 발화 시점에 붙어 있는 SSE 클라이언트가 없으면 보류 큐에 쌓았다가,
/// 다음 /events 접속 때 몰아서 전달한다 (안드로이드의 pending 큐와 동일한 보장).
///
/// 저장 형식: [ {id, at, period, data} ]  — deploy 문서의 CronScheduleInput 과 대응.
/// </summary>
internal static class CronScheduler
{
    private static readonly object Lock = new();
    private static string _storePath = "";
    private static Action<string, object>? _broadcast;

    private static readonly Dictionary<string, Entry> Schedules = new();
    private static readonly List<JsonObject> Pending = new();

    private sealed class Entry
    {
        public string Id = "";
        public long At;
        public long Period;
        public JsonNode? Data;
    }

    public static void Configure(string storePath, Action<string, object> broadcast)
    {
        _storePath = storePath;
        _broadcast = broadcast;
        Load();
    }

    public static void Start() =>
        new Thread(Loop) { IsBackground = true, Name = "Cron" }.Start();

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    /// <summary>예약 등록. json = {id?, at(epoch ms), repeat?, data?}. 반환: 등록 id.</summary>
    public static string Schedule(string json)
    {
        var input = JsonNode.Parse(json)?.AsObject()
            ?? throw new InvalidOperationException("cron: JSON 객체 필요");

        var id = input["id"]?.GetValue<string>() is { Length: > 0 } given
            ? given
            : Guid.NewGuid().ToString();
        var at = AsLong(input["at"]);
        if (at <= 0) throw new InvalidOperationException("cron: 'at'(epoch ms) 필수");

        var period = input["repeat"] switch
        {
            JsonValue v when v.TryGetValue<string>(out var s) => s == "daily" ? 86_400_000L : 0L,
            JsonValue v when v.TryGetValue<double>(out var n) => (long)n,
            _ => 0L
        };

        lock (Lock)
        {
            Schedules[id] = new Entry { Id = id, At = at, Period = period, Data = input["data"]?.DeepClone() };
            Save();
        }
        return id;
    }

    public static bool Cancel(string id)
    {
        lock (Lock)
        {
            if (!Schedules.Remove(id)) return false;
            Save();
            return true;
        }
    }

    /// <summary>등록된 예약 목록 JSON (CronEntry[] — {id, at, period, data})</summary>
    public static string ListJson()
    {
        lock (Lock) return ToArray(Schedules.Values).ToJsonString();
    }

    /// <summary>SSE 클라이언트가 새로 붙었을 때 KioskEvents가 호출 — 밀린 발화분을 전달한다.</summary>
    public static void FlushPending()
    {
        lock (Lock) TryFlushLocked();
    }

    private static void Loop()
    {
        while (true)
        {
            Thread.Sleep(1000);
            try { Tick(); } catch { /* 다음 주기에 재시도 */ }
        }
    }

    private static void Tick()
    {
        lock (Lock)
        {
            var now = Now();
            var changed = false;
            foreach (var id in Schedules.Keys.ToList())
            {
                var e = Schedules[id];
                if (e.At > now) continue;

                Pending.Add(new JsonObject { ["id"] = e.Id, ["data"] = e.Data?.DeepClone(), ["firedAt"] = now });
                if (e.Period > 0)
                {
                    var next = e.At;
                    while (next <= now) next += e.Period;
                    e.At = next;
                }
                else
                {
                    Schedules.Remove(id);
                }
                changed = true;
            }
            if (changed) Save();
            TryFlushLocked();
        }
    }

    /// <summary>Lock 보유 상태에서 호출 — 클라이언트가 있으면 보류분을 모두 푸시하고 큐를 비운다.</summary>
    private static void TryFlushLocked()
    {
        if (Pending.Count == 0 || _broadcast == null || !KioskEvents.HasClients) return;
        foreach (var detail in Pending) _broadcast("kiosk:cron", detail);
        Pending.Clear();
    }

    private static JsonArray ToArray(IEnumerable<Entry> entries)
    {
        var arr = new JsonArray();
        foreach (var e in entries)
            arr.Add(new JsonObject { ["id"] = e.Id, ["at"] = e.At, ["period"] = e.Period, ["data"] = e.Data?.DeepClone() });
        return arr;
    }

    private static void Save()
    {
        try { File.WriteAllText(_storePath, ToArray(Schedules.Values).ToJsonString()); }
        catch { /* 저장 실패는 무시 — 다음 변경 때 재시도 */ }
    }

    private static void Load()
    {
        if (_storePath.Length == 0 || !File.Exists(_storePath)) return;
        try
        {
            var arr = JsonNode.Parse(File.ReadAllText(_storePath))?.AsArray();
            if (arr == null) return;
            foreach (var node in arr)
            {
                if (node is not JsonObject o) continue;
                var id = o["id"]?.GetValue<string>();
                if (string.IsNullOrEmpty(id)) continue;
                Schedules[id] = new Entry
                {
                    Id = id,
                    At = AsLong(o["at"]),
                    Period = AsLong(o["period"]),
                    Data = o["data"]?.DeepClone()
                };
            }
        }
        catch { /* 손상된 저장 파일은 무시 */ }
    }

    /// <summary>JsonNode(메모리/파싱 양쪽)에서 정수를 안전하게 읽는다.</summary>
    private static long AsLong(JsonNode? node)
    {
        if (node is not JsonValue v) return 0;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d)) return (long)d;
        if (v.TryGetValue<string>(out var s) && long.TryParse(s, out var p)) return p;
        return 0;
    }
}
