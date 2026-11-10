#!/usr/bin/env python3
"""Deterministic range server for device acceptance tests; Python 3 standard library only."""
import argparse
import hashlib
import re
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

BLOCK = bytes(i % 251 for i in range(251 * 256))


def data_at(start, count):
    offset = start % len(BLOCK)
    return (BLOCK + BLOCK)[offset:offset + count]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # Never log query parameters or authentication values.

    def do_GET(self):
        args = parse_qs(urlsplit(self.path).query)
        mode = args.get("mode", ["range"])[0]
        if mode == "expired":
            self.send_error(410)
            return
        if mode == "auth" and self.headers.get("Authorization") != "Bearer local-test-token":
            self.send_error(403)
            return
        size = self.server.payload_size
        start, end = 0, size - 1
        range_value = self.headers.get("Range")
        ranged = mode not in ("plain", "unknown") and range_value is not None
        if ranged:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", range_value)
            if not match:
                self.send_error(416)
                return
            start = int(match[1])
            end = min(int(match[2]) if match[2] else size - 1, size - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        self.send_response(206 if ranged else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("ETag", '"fixture-' + args.get("version", ["1"])[0] + '"')
        if ranged:
            self.send_header("Content-Range", f"bytes {start + (1 if mode == 'wrong-range' else 0)}-{end}/{size}")
        if mode == "unknown":
            self.send_header("Connection", "close")
        else:
            self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        position = start
        try:
            while position <= end:
                count = min(32768, end - position + 1)
                self.wfile.write(data_at(position, count))
                position += count
                if mode == "disconnect" and position - start >= 65536:
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.close_connection = True
                    return
        except (BrokenPipeError, ConnectionResetError):
            pass
        if mode == "unknown":
            self.close_connection = True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--size", type=int, default=16 * 1024 * 1024, help="payload bytes; supports values above 4 GiB")
    options = parser.parse_args()
    if options.size <= 0:
        parser.error("size must be positive")
    digest = hashlib.sha256()
    for position in range(0, options.size, 32768):
        digest.update(data_at(position, min(32768, options.size - position)))
    print(f"Serving {options.size} bytes on :{options.port}; SHA-256 {digest.hexdigest()}", flush=True)
    print("Modes: range, plain, unknown, wrong-range, expired, auth, disconnect (query ?mode=...)", flush=True)
    server = ThreadingHTTPServer(("0.0.0.0", options.port), Handler)
    server.payload_size = options.size
    server.serve_forever()
