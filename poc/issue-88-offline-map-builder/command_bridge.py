"""Drive an unmodified GNSS Command binary through its existing local API.

Only documented loopback endpoints are used (`/local/map-import`,
`/local/maps/{id}/chunks/{key}`, `/local/maps/{id}/products`,
`/local/maps/{id}/approve-transfer`, `/local/maps/{id}/export`,
`/local/map-package`, `/local/maps/{id}/manifest`). Nothing is patched.
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Command:
    def __init__(self, binary: str, db: str, log: str, proxy: str | None, sandbox_offline: bool = False):
        self.port = free_port()
        self.base = f"http://127.0.0.1:{self.port}"
        env = dict(os.environ)
        for k in ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "NO_PROXY", "no_proxy"):
            env.pop(k, None)
        if proxy:
            env["HTTPS_PROXY"] = env["HTTP_PROXY"] = proxy
            env["NO_PROXY"] = "127.0.0.1,localhost"
        cmd = [binary, "-db", db, "-dashboard-listen", f"127.0.0.1:{self.port}", "-ingest-listen", f"127.0.0.1:{free_port()}"]
        if sandbox_offline:
            cmd = sandbox_wrap(cmd)
        self.log = open(log, "ab")
        self.proc = subprocess.Popen(cmd, env=env, stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                self.get("/local/maps")
                return
            except Exception:
                time.sleep(0.2)
        raise RuntimeError("Command did not start; see " + log)

    def stop(self) -> None:
        self.proc.terminate()
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.close()

    def kill(self) -> None:
        self.proc.kill()
        self.proc.wait()
        self.log.close()

    def _req(self, method: str, path: str, body: bytes | None = None, ctype: str | None = None, timeout: float = 900):
        r = urllib.request.Request(self.base + path, data=body, method=method)
        if ctype:
            r.add_header("Content-Type", ctype)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(r, timeout=timeout) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def get(self, path: str):
        status, b = self._req("GET", path, timeout=60)
        if status != 200:
            raise RuntimeError(f"GET {path}: {status} {b[:300]!r}")
        return b

    def get_json(self, path: str):
        return json.loads(self.get(path))

    def post_json(self, path: str, obj) -> tuple[int, dict]:
        status, b = self._req("POST", path, json.dumps(obj).encode(), "application/json")
        try:
            return status, json.loads(b or b"{}")
        except json.JSONDecodeError:
            return status, {"raw": b.decode("utf-8", "replace")}

    def import_pbf(self, pbf: str, lat: float, lon: float, radius_m: float) -> dict:
        data = open(pbf, "rb").read()
        status, b = self._req("POST", f"/local/map-import?mode=crop&lat={lat}&lon={lon}&radius_m={radius_m}", data, "application/octet-stream")
        if status != 200:
            raise RuntimeError(f"PBF import refused: {status} {b[:500]!r}")
        return json.loads(b)

    def manifest(self, map_id: str) -> dict:
        return self.get_json(f"/local/maps/{map_id}/manifest")

    def acquire(self, map_id: str, key: str, layer: str, max_approvals: int = 8, approval_cap: int | None = None) -> dict:
        """Acquire one chunk; approve exact displayed cumulative transfer when
        Command asks, but never above approval_cap."""
        approvals = []
        for _ in range(max_approvals + 1):
            t0 = time.time()
            status, body = self.post_json(f"/local/maps/{map_id}/chunks/{key}", {"layer": layer})
            elapsed = time.time() - t0
            if status == 200:
                return {"status": "complete", "elapsed_s": round(elapsed, 3), "approvals": approvals}
            if status == 409 and body.get("error") == "transfer_approval_required":
                need = body.get("transfer", {}).get("approval_required_bytes", 0)
                if approval_cap is not None and need > approval_cap:
                    return {"status": "approval_refused", "required_bytes": need, "approvals": approvals, "elapsed_s": round(elapsed, 3)}
                s2, b2 = self.post_json(f"/local/maps/{map_id}/approve-transfer", {"approved_bytes": need})
                approvals.append({"approved_bytes": need, "status": s2})
                if s2 != 200:
                    return {"status": "approval_failed", "detail": b2, "approvals": approvals}
                continue
            if status == 409 and body.get("error") == "map_busy":
                time.sleep(1)
                continue
            return {"status": "failed", "http": status, "error": body.get("message") or body, "elapsed_s": round(elapsed, 3), "approvals": approvals}
        return {"status": "failed", "error": "approval loop exceeded", "approvals": approvals}

    def set_products(self, map_id: str, components: dict) -> None:
        status, body = self.post_json(f"/local/maps/{map_id}/products", {"components": components})
        if status != 200:
            raise RuntimeError(f"products refused: {status} {body}")

    def export(self, map_id: str, dst: str) -> int:
        status, b = self._req("GET", f"/local/maps/{map_id}/export", timeout=180)
        if status != 200:
            raise RuntimeError(f"export failed: {status} {b[:300]!r}")
        open(dst, "wb").write(b)
        return len(b)

    def import_package(self, path: str) -> tuple[int, dict]:
        status, b = self._req("POST", "/local/map-package", open(path, "rb").read(), "application/zip", timeout=180)
        try:
            return status, json.loads(b)
        except json.JSONDecodeError:
            return status, {"raw": b.decode("utf-8", "replace")}

    def maps(self) -> list:
        return self.get_json("/local/maps")


def sandbox_wrap(cmd: list[str]) -> list[str]:
    """macOS kernel sandbox: deny every outbound network connection except
    loopback. Used to prove offline operation without trusting proxy settings."""
    profile = ("(version 1)(allow default)"
               "(deny network-outbound)"
               "(allow network-outbound (remote ip \"localhost:*\"))"
               "(allow network-outbound (remote unix-socket))")
    return ["/usr/bin/sandbox-exec", "-p", profile] + cmd
