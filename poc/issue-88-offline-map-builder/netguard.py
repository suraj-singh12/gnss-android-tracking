"""Local HTTP(S) proxy that measures, caps or denies external transfers.

Every external request made by the POC runner, GDAL/curl and (for the baseline)
Command goes through this proxy. It provides three things the issue asks for:

* an explicit transfer allowance: once the cumulative received bytes reach the
  approved cap, open tunnels are closed and new connections are refused;
* an honest byte ledger per host (tunnelled TLS bytes, i.e. application payload
  plus TLS framing, excluding TCP/IP headers);
* a deny mode used to prove offline operation: any attempted external
  connection is refused and recorded as a violation.

This is POC tooling only; it is not used by Command in the field.
"""
from __future__ import annotations

import json
import select
import socket
import socketserver
import threading
import time
from dataclasses import dataclass, field


@dataclass
class Ledger:
    cap_bytes: int
    deny: bool = False
    received: int = 0
    sent: int = 0
    per_host: dict = field(default_factory=dict)
    refused: list = field(default_factory=list)
    cap_hit: bool = False
    lock: threading.Lock = field(default_factory=threading.Lock)

    def admit(self, host: str) -> bool:
        with self.lock:
            if self.deny:
                self.refused.append({"host": host, "at": time.time(), "reason": "offline/deny mode"})
                return False
            if self.received >= self.cap_bytes:
                self.cap_hit = True
                self.refused.append({"host": host, "at": time.time(), "reason": "transfer allowance exhausted"})
                return False
            return True

    def add(self, host: str, down: int, up: int) -> bool:
        """Account bytes; return False when the allowance is exhausted."""
        with self.lock:
            self.received += down
            self.sent += up
            h = self.per_host.setdefault(host, {"received": 0, "sent": 0, "connections": 0})
            h["received"] += down
            h["sent"] += up
            if self.received > self.cap_bytes:
                self.cap_hit = True
                return False
            return True

    def connection(self, host: str) -> None:
        with self.lock:
            self.per_host.setdefault(host, {"received": 0, "sent": 0, "connections": 0})["connections"] += 1

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "cap_bytes": self.cap_bytes,
                "deny": self.deny,
                "received_bytes": self.received,
                "sent_bytes": self.sent,
                "cap_hit": self.cap_hit,
                "per_host": json.loads(json.dumps(self.per_host)),
                "refused": list(self.refused),
            }


class _Handler(socketserver.BaseRequestHandler):
    def handle(self) -> None:  # noqa: C901 - small state machine
        ledger: Ledger = self.server.ledger  # type: ignore[attr-defined]
        client = self.request
        client.settimeout(30)
        head = b""
        while b"\r\n\r\n" not in head and len(head) < 65536:
            chunk = client.recv(4096)
            if not chunk:
                return
            head += chunk
        line = head.split(b"\r\n", 1)[0].decode("latin-1")
        parts = line.split()
        if len(parts) < 3:
            return
        method, target = parts[0].upper(), parts[1]
        if method != "CONNECT":
            # Plain HTTP is not needed by any configured source; refuse it so
            # nothing bypasses TLS accounting.
            with ledger.lock:
                ledger.refused.append({"host": target, "at": time.time(), "reason": "plain HTTP refused"})
            client.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
            return
        host, _, port = target.rpartition(":")
        if not ledger.admit(host):
            client.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
            return
        try:
            upstream = socket.create_connection((host, int(port or 443)), timeout=20)
        except OSError as e:
            with ledger.lock:
                ledger.refused.append({"host": host, "at": time.time(), "reason": f"connect failed: {e}"})
            client.sendall(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
            return
        ledger.connection(host)
        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        sockets = [client, upstream]
        try:
            while True:
                readable, _, _ = select.select(sockets, [], [], 120)
                if not readable:
                    break
                for s in readable:
                    data = s.recv(65536)
                    if not data:
                        return
                    if s is upstream:
                        if not ledger.add(host, len(data), 0):
                            return  # allowance exhausted: close mid-stream
                        client.sendall(data)
                    else:
                        ledger.add(host, 0, len(data))
                        upstream.sendall(data)
        except OSError:
            return
        finally:
            upstream.close()


class _Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True
    allow_reuse_address = True


def start(cap_bytes: int, deny: bool = False) -> tuple[_Server, Ledger, str]:
    ledger = Ledger(cap_bytes=cap_bytes, deny=deny)
    server = _Server(("127.0.0.1", 0), _Handler)
    server.ledger = ledger  # type: ignore[attr-defined]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_address[1]}"
    return server, ledger, url
