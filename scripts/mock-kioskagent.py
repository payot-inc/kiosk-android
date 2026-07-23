# KioskAgent(사이드카) 모의 서버 — 실제 에이전트와 같은 /bill/* /card/* + SSE(/events)를 제공한다.
# 시리얼 장치/EasyCardK 없이 웹(useKiosk → agentWin.ts)의 결제 플로우를 테스트할 때 사용.
#
#   python3 scripts/mock-kioskagent.py          # 127.0.0.1:9100
#
# 지폐 투입 시뮬레이션 (투입구 개방 상태에서만 동작):
#   curl -X POST "http://127.0.0.1:9100/mock/insert?amount=1000"
# 카드: /card/approve 는 진행상태 이벤트(1000/1006) 푸시 후 1초 뒤 가짜 승인으로 응답한다.
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

state = {'connected': False, 'running': False}
clients = []          # SSE 응답 스트림 목록
lock = threading.Lock()


def broadcast(event, detail):
    frame = f'data: {json.dumps({"event": event, "detail": detail})}\n\n'.encode()
    with lock:
        for w in clients[:]:
            try:
                w.write(frame)
                w.flush()
            except OSError:
                clients.remove(w)
    print(f'[MOCK] {event} {detail}', flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def _json(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        length = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(length).decode().strip() if length else ''

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.send_header('Access-Control-Allow-Private-Network', 'true')
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/events':
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            # 접속 즉시 지폐기 상태 스냅샷 (실제 에이전트와 동일)
            for event, detail in (('kiosk:bill-connection', {'connected': state['connected']}),
                                  ('kiosk:bill-status', {'running': state['running']})):
                self.wfile.write(f'data: {json.dumps({"event": event, "detail": detail})}\n\n'.encode())
            self.wfile.flush()
            with lock:
                clients.append(self.wfile)
            threading.Event().wait()  # 연결 유지 (끊기면 broadcast 가 회수)
        elif path == '/bill/status':
            self._json(200, state)
        elif path == '/card/ping':
            self._json(200, {'ok': True})
        else:
            self._json(404, {'ok': False, 'error': 'unknown endpoint'})

    def do_POST(self):
        url = urlparse(self.path)
        path, q = url.path, parse_qs(url.query)
        if path == '/bill/connect':
            state['connected'] = True
            self._json(200, {'ok': True})
            broadcast('kiosk:bill-connection', {'connected': True, 'device': 'MOCK'})
        elif path == '/bill/disconnect':
            state.update(connected=False, running=False)
            self._json(200, {'ok': True})
            broadcast('kiosk:bill-connection', {'connected': False})
        elif path in ('/bill/run', '/bill/stop', '/bill/write'):
            cmd = {'/bill/run': 'RUN', '/bill/stop': 'STOP'}.get(path) or q.get('cmd', [self._body()])[0]
            self._json(200, {'ok': True})
            if not state['connected']:
                broadcast('kiosk:bill-error', {'message': '지폐기가 연결되어 있지 않습니다'})
            elif cmd.upper() in ('RUN', 'STOP'):
                state['running'] = cmd.upper() == 'RUN'
                broadcast('kiosk:bill-line', {'line': f'{cmd.upper()}:OK'})
                broadcast('kiosk:bill-status', {'running': state['running']})
        elif path == '/mock/insert':
            amount = int(q.get('amount', ['1000'])[0])
            if state['connected'] and state['running']:
                self._json(200, {'ok': True, 'amount': amount})
                broadcast('kiosk:bill-line', {'line': f'BILL:{amount}'})
                broadcast('kiosk:bill-inserted', {'amount': amount})
            else:
                self._json(409, {'ok': False, 'error': '투입구가 개방되어 있지 않습니다 (connect + run 먼저)'})
        elif path == '/card/approve':
            req = json.loads(self._body() or '{}')
            amount = str(req.get('amount', 0))
            broadcast('kiosk:card-event', {'EVENT_CODE': '1000', 'EVENT_MSG': '승인 시작'})
            time.sleep(0.5)
            broadcast('kiosk:card-event', {'EVENT_CODE': '1006', 'EVENT_MSG': '승인 요청'})
            time.sleep(0.5)
            self._json(200, {'SUC': '00', 'RQ01': 'D1', 'RQ07': amount,
                             'RS04': '0000', 'RS07': '2606121300004', 'RS09': '30012345',
                             'RS11': '041', 'RS12': '모의카드'})
        elif path == '/card/cancel':
            req = json.loads(self._body() or '{}')
            amount = str(req.get('amount', 0))
            if req.get('approvalNum') and req.get('approvalDate'):
                self._json(200, {'SUC': '00', 'RQ01': 'D4', 'RQ07': amount,
                                 'RS04': '0000', 'RS07': '2606121301004', 'RS09': '30054321', 'RS12': '모의카드'})
            else:
                self._json(200, {'SUC': '00', 'RS04': '7001', 'RS16': '원거래 정보 없음'})
        else:
            self._json(404, {'ok': False, 'error': 'unknown endpoint'})

    def log_message(self, *args):
        pass


print('mock KioskAgent on 127.0.0.1:9100 — 지폐 투입: curl -X POST "http://127.0.0.1:9100/mock/insert?amount=1000"', flush=True)
ThreadingHTTPServer(('127.0.0.1', 9100), Handler).serve_forever()
