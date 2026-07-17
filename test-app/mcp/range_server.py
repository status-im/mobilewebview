#!/usr/bin/env python3
"""Minimal HTTP server with Accept-Ranges for pause/resume stress tests."""

from __future__ import annotations

import argparse
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def make_handler(payload: bytes):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A003
            pass

        def do_GET(self):  # noqa: N802
            if self.path not in ("/", "/big.bin", "/stress.bin"):
                self.send_error(404)
                return
            size = len(payload)
            range_header = self.headers.get("Range")
            if range_header:
                m = re.match(r"bytes=(\d*)-(\d*)", range_header)
                if not m:
                    self.send_error(400, "bad range")
                    return
                start_s, end_s = m.group(1), m.group(2)
                start = int(start_s) if start_s else 0
                end = int(end_s) if end_s else size - 1
                end = min(end, size - 1)
                if start > end or start >= size:
                    self.send_error(416, "range not satisfiable")
                    return
                chunk = payload[start : end + 1]
                self.send_response(206)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(len(chunk)))
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                self.end_headers()
                self.wfile.write(chunk)
                return

            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(size))
            self.send_header(
                "Content-Disposition", 'attachment; filename="stress-large.bin"'
            )
            self.end_headers()
            # Slow chunks so pause/resume stress can interrupt mid-transfer.
            import time

            view = memoryview(payload)
            step = 32 * 1024
            for i in range(0, size, step):
                self.wfile.write(view[i : i + step])
                self.wfile.flush()
                time.sleep(0.05)

        def do_HEAD(self):  # noqa: N802
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--mb", type=int, default=20)
    args = parser.parse_args()
    payload = (b"mwv-range-" * 1024) * (args.mb * 1024 // 10 + 1)
    payload = payload[: args.mb * 1024 * 1024]
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(payload))
    print(f"range-server http://127.0.0.1:{args.port}/big.bin ({args.mb} MiB)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
