# EasyCardK 모의 서버 — 스펙과 동일하게 홑따옴표 JS 리터럴 JSONP로 응답한다.
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, unquote


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        q = parse_qs(urlparse(self.path).query)
        cb = q.get('callback', ['cb'])[0]
        req = unquote(q.get('REQ', [''])[0])
        f = req.split('^')
        kind = f[0] if f else ''
        print(f'[MOCK] REQ={req}', flush=True)

        if kind == 'GV':
            body = f"{cb}({{'SUC':'00','MSG':'EasyCardK 1.2.0.99 (MOCK)'}})"
        elif kind == 'D1':
            amount = f[2] if len(f) > 2 else '0'
            body = (f"{cb}({{'SUC':'00','RQ01':'D1','RQ02':'0700083','RQ07':'{amount}',"
                    f"'RS03':'1234','RS04':'0000','RS07':'2606111300004','RS08':'062041148921',"
                    f"'RS09':'30012345','RS11':'041','RS12':'모의카드','RS13':'741069091','RS18':'N'}})")
        elif kind == 'D4':
            amount = f[2] if len(f) > 2 else '0'
            org_date, org_num = (f[4] if len(f) > 4 else ''), (f[5] if len(f) > 5 else '')
            if org_date and org_num:
                body = (f"{cb}({{'SUC':'00','RQ01':'D4','RQ07':'{amount}','RS04':'0000',"
                        f"'RS07':'2606111301004','RS09':'30054321','RS12':'모의카드'}})")
            else:
                body = f"{cb}({{'SUC':'00','RS04':'7001','RS16':'원거래 정보 없음'}})"
        else:
            body = f"{cb}({{'SUC':'01','MSG':'Wrong Request Message !'}})"

        data = body.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/javascript; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


print('mock EasyCardK on 127.0.0.1:8090', flush=True)
HTTPServer(('127.0.0.1', 8090), Handler).serve_forever()
