using System.IO.Ports;
using System.Text;

namespace KioskAgent;

/// <summary>
/// 지폐기 — 시리얼 9600bps, CRLF 종단 ASCII 라인 프로토콜.
/// 안드로이드 BillAcceptor.kt와 동일한 동작 (시리얼은 네이티브가 잡고, 웹은 이벤트만 받는다):
///   앱 → 지폐기 : "RUN\r\n"(투입구 개방) / "STOP\r\n"(차폐)
///   지폐기 → 앱 : "RUN:OK" / "STOP:OK" / "BILL:{금액}" (장당 비동기 푸시)
/// 이벤트는 emit 콜백(KioskEvents.Broadcast)을 통해 SSE(/events)로 브라우저에 전달된다.
/// </summary>
public sealed class BillAcceptor(string? preferredPort, Action<string, object> emit)
{
    private SerialPort? _port;
    private readonly StringBuilder _buffer = new();
    private volatile bool _running;

    public bool Connected => _port?.IsOpen == true;
    public bool Running => _running;

    public void Connect()
    {
        lock (this)
        {
            if (_port?.IsOpen == true)
            {
                EmitConnection(true, _port.PortName);
                return;
            }

            var names = SerialPort.GetPortNames();
            var name = !string.IsNullOrEmpty(preferredPort) ? preferredPort : names.FirstOrDefault();
            if (name == null)
            {
                EmitError("시리얼 포트를 찾을 수 없습니다 (kiosk.ini의 SerialPort 확인)");
                return;
            }

            try
            {
                var port = new SerialPort(name, 9600, Parity.None, 8, StopBits.One)
                {
                    Encoding = Encoding.ASCII,
                    // CH340 계열은 DTR/RTS가 내려가 있으면 송수신이 안 된다
                    // (안드로이드 usb-serial-for-android 드라이버는 open 시 자동 처리)
                    DtrEnable = true,
                    RtsEnable = true,
                    // 기본 WriteTimeout은 무한 — 장치가 안 받으면 Write가 영원히 멈춘다
                    WriteTimeout = 1000
                };
                port.DataReceived += OnDataReceived;
                port.Open();
                _port = port;
                EmitConnection(true, name);
            }
            catch (Exception e)
            {
                _port = null;
                EmitError($"지폐기 연결 실패({name}): {e.Message}");
            }
        }
    }

    public void Disconnect()
    {
        lock (this)
        {
            var had = _port != null;
            CloseQuietly();
            if (had) EmitConnection(false, null);
        }
    }

    public void Write(string command)
    {
        var port = _port;
        if (port?.IsOpen != true)
        {
            EmitError("지폐기가 연결되어 있지 않습니다");
            return;
        }
        try
        {
            port.Write($"{command}\r\n");
        }
        catch (Exception e)
        {
            EmitError($"전송 실패({command}): {e.Message}");
        }
    }

    private void OnDataReceived(object sender, SerialDataReceivedEventArgs e)
    {
        try
        {
            var chunk = _port?.ReadExisting();
            if (string.IsNullOrEmpty(chunk)) return;
            lock (_buffer)
            {
                _buffer.Append(chunk);
                while (true)
                {
                    var text = _buffer.ToString();
                    var newline = text.IndexOf('\n');
                    if (newline < 0) break;
                    var line = text[..newline].Trim();
                    _buffer.Remove(0, newline + 1);
                    if (line.Length > 0) HandleLine(line);
                }
            }
        }
        catch (Exception ex)
        {
            EmitError($"시리얼 수신 오류: {ex.Message}");
        }
    }

    private void HandleLine(string line)
    {
        emit("kiosk:bill-line", new { line });
        var upper = line.ToUpperInvariant();
        if (upper.Contains("RUN:OK"))
        {
            _running = true;
            emit("kiosk:bill-status", new { running = true });
        }
        else if (upper.Contains("STOP:OK"))
        {
            _running = false;
            emit("kiosk:bill-status", new { running = false });
        }
        else if (upper.StartsWith("BILL:"))
        {
            var raw = line[(line.IndexOf(':') + 1)..].Trim();
            if (int.TryParse(raw, out var amount))
            {
                emit("kiosk:bill-inserted", new { amount });
            }
            else
            {
                EmitError($"금액 파싱 실패: {line}");
            }
        }
    }

    private void CloseQuietly()
    {
        try { _port?.Close(); } catch { /* 이미 닫힘 */ }
        try { _port?.Dispose(); } catch { /* ignore */ }
        _port = null;
        _running = false;
        lock (_buffer) _buffer.Clear();
    }

    private void EmitConnection(bool connected, string? device)
    {
        if (device != null) emit("kiosk:bill-connection", new { connected, device });
        else emit("kiosk:bill-connection", new { connected });
    }

    private void EmitError(string message) => emit("kiosk:bill-error", new { message });
}
