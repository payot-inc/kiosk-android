#!/usr/bin/env python3
"""지폐기(CH340 USB 시리얼) <-> TCP 브리지.

에뮬레이터는 호스트 USB 를 패스스루하지 못하므로, Mac 에 물린 실물 지폐기를
TCP 로 노출해 앱(DEBUG 빌드의 소켓 경로)이 붙게 한다.

  1) 이 스크립트 실행:      ./scripts/bill-bridge.py
  2) adb reverse tcp:6790 tcp:6790   (에뮬레이터 localhost:6790 -> Mac 6790)
  3) 앱에서 지폐기 연결(billConnect) → 실물 지폐기와 그대로 통신

프로토콜은 그대로 통과시키는 raw 바이트 브리지다(앱 <-> 지폐기 ASCII 라인).
9600 8N1, 흐름제어 없음 — BillAcceptor.kt 와 동일.
"""
import argparse
import glob
import os
import select
import socket
import sys
import termios

sys.stdout.reconfigure(line_buffering=True)  # 로그 실시간 출력(버퍼링 방지)

BAUD = termios.B9600


def open_serial(dev: str) -> int:
    fd = os.open(dev, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    attrs = termios.tcgetattr(fd)
    # [iflag, oflag, cflag, lflag, ispeed, ospeed, cc] — raw 9600 8N1
    attrs[0] = 0
    attrs[1] = 0
    attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    attrs[3] = 0
    attrs[4] = BAUD
    attrs[5] = BAUD
    cc = attrs[6]
    cc[termios.VMIN] = 0
    cc[termios.VTIME] = 0
    termios.tcsetattr(fd, termios.TCSANOW, attrs)
    termios.tcflush(fd, termios.TCIOFLUSH)
    return fd


def find_serial() -> str:
    cands = sorted(glob.glob("/dev/cu.usbserial*")) or sorted(glob.glob("/dev/cu.wchusbserial*"))
    if not cands:
        sys.exit("시리얼 장치를 찾지 못했습니다. --dev 로 직접 지정하세요 (ls /dev/cu.*).")
    return cands[0]


def bridge(client: socket.socket, fd: int) -> None:
    client.setblocking(False)
    while True:
        r, _, _ = select.select([client, fd], [], [], 1.0)
        if client in r:
            data = client.recv(4096)
            if not data:
                print("[bridge] 클라이언트 연결 종료")
                return
            os.write(fd, data)          # 앱 -> 지폐기
            print(f"[app->bill] {data!r}")
        if fd in r:
            data = os.read(fd, 4096)
            if data:
                client.sendall(data)    # 지폐기 -> 앱
                print(f"[bill->app] {data!r}")


def main() -> None:
    ap = argparse.ArgumentParser(description="지폐기 시리얼 <-> TCP 브리지")
    ap.add_argument("--dev", help="시리얼 장치 (기본: /dev/cu.usbserial* 자동탐지)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=6790)
    args = ap.parse_args()

    dev = args.dev or find_serial()
    fd = open_serial(dev)
    print(f"[bridge] 시리얼 열림: {dev} @9600 8N1")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(1)
    print(f"[bridge] TCP 대기: {args.host}:{args.port}  (adb reverse tcp:{args.port} tcp:{args.port})")

    try:
        while True:
            client, addr = srv.accept()
            print(f"[bridge] 앱 연결됨: {addr}")
            termios.tcflush(fd, termios.TCIOFLUSH)
            try:
                bridge(client, fd)
            except OSError as e:
                print(f"[bridge] 오류: {e}")
            finally:
                client.close()
    except KeyboardInterrupt:
        print("\n[bridge] 종료")
    finally:
        os.close(fd)
        srv.close()


if __name__ == "__main__":
    main()
