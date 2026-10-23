#!/usr/bin/env python3
"""Loopback-only synthetic profile-model fixture for iOS UI interaction tests.

Run: python3 tools/fake-hermes/profile_model_settings_fake.py 8899
Test: TEST_RUNNER_MERCURY_MODEL_SETTINGS_FIXTURE_ORIGIN=http://127.0.0.1:8899 xcodebuild ...
      -only-testing:MercuryUITests/ProfileModelSettingsUITests test

Imports the existing startup fake with an official auth-free status response.
All model/config values stay in memory;
the private control route is used by XCTest only, never by Mercury.
"""

import importlib.util
import json
import os
from pathlib import Path
import sys
import threading
from urllib.parse import parse_qs, urlsplit

os.environ["FAKE_HERMES_SCENARIO"] = "ios-startup"
spec = importlib.util.spec_from_file_location("mercury_model_settings_base", Path(__file__).with_name("fake_hermes.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

lock = threading.RLock()
models = {"default": "fast-example", "work": "normal-example"}
speeds = {"default": "fast", "work": "normal"}
runtime_requests = 0
original_handle_rpc = base.WsSession.handle_rpc


def tracked_rpc(self, text):
    global runtime_requests
    try:
        method = json.loads(text).get("method")
        if method in {"session.create", "session.resume"}:
            with lock:
                runtime_requests += 1
    except (ValueError, TypeError):
        pass
    return original_handle_rpc(self, text)


base.WsSession.handle_rpc = tracked_rpc


class Handler(base.Handler):
    def require_auth(self):
        # This loopback fixture exercises model settings; authenticated request
        # headers and credential scoping are covered by native HTTP tests.
        return True

    def do_GET(self):
        parts = urlsplit(self.path)
        profile = parse_qs(parts.query).get("profile", ["default"])[0]
        if parts.path == "/api/status":
            self.send_json({"version": "0.22.4", "auth_required": False, "active_sessions": 0})
        elif parts.path == "/__test__/model-settings":
            with lock:
                self.send_json({"synthetic": True, "models": dict(models), "speeds": dict(speeds),
                                "runtime_requests": runtime_requests})
        elif parts.path == "/api/model/options":
            if not self.require_auth():
                return
            with lock:
                self.send_json({"provider": "fake", "model": models.get(profile, "fast-example"),
                                "providers": [{"slug": "fake", "name": "Fake", "authenticated": True,
                                               "models": ["fast-example", "normal-example"],
                                               "capabilities": {"fast-example": {"fast": True},
                                                                "normal-example": {"fast": False}}}]})
        elif parts.path == "/api/config":
            if not self.require_auth():
                return
            with lock:
                self.send_json({"agent": {"service_tier": speeds.get(profile, "normal")}})
        else:
            super().do_GET()

    def do_POST(self):
        parts = urlsplit(self.path)
        if parts.path != "/api/model/set":
            return super().do_POST()
        if not self.require_auth():
            return
        body = self.read_body_json()
        profile = parse_qs(parts.query).get("profile", ["default"])[0]
        if body.get("scope") != "main" or body.get("model") not in {"fast-example", "normal-example"}:
            return self.send_json({"ok": False}, status=400)
        with lock:
            models[profile] = body["model"]
        self.send_json({"ok": True})

    def do_PUT(self):
        parts = urlsplit(self.path)
        if parts.path != "/api/config":
            return super().do_PUT()
        if not self.require_auth():
            return
        body = self.read_body_json()
        profile = parse_qs(parts.query).get("profile", ["default"])[0]
        speed = body.get("config", {}).get("agent", {}).get("service_tier")
        if speed not in {"normal", "fast"}:
            return self.send_json({"ok": False}, status=400)
        with lock:
            speeds[profile] = speed
        self.send_json({"ok": True})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    server = base.SafeThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    print(f"Synthetic profile-model fixture listening on loopback port {port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
