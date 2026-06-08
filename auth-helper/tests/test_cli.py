import threading
import urllib.request

import pytest

from portal_auth import cli
from portal_auth.oauth import ClientConfig, OAuthError

CLIENT = ClientConfig("cid", "secret")


def _drive_callback(port, query, captured_url):
    """Wait for the auth URL to be 'opened', then hit the loopback callback."""

    def open_browser(url):
        captured_url.append(url)
        # Fire the callback from another thread so handle_request() can serve it.
        def hit():
            try:
                urllib.request.urlopen(
                    f"http://127.0.0.1:{port}/?{query}", timeout=5
                ).read()
            except Exception:
                pass

        threading.Thread(target=hit, daemon=True).start()

    return open_browser


def test_run_loopback_flow_happy_path(monkeypatch):
    port = cli.free_port()
    captured_url = []

    # Force a deterministic state and stub the token exchange.
    monkeypatch.setattr(cli, "new_state", lambda: "fixed-state")
    monkeypatch.setattr(
        cli, "exchange_code", lambda code, client, uri: {"refresh_token": "RT-123"}
    )

    open_browser = _drive_callback(port, "code=AUTH_CODE&state=fixed-state", captured_url)
    rt = cli.run_loopback_flow(CLIENT, port, open_browser=open_browser)

    assert rt == "RT-123"
    assert captured_url and captured_url[0].startswith("https://accounts.google.com")


def test_run_loopback_flow_state_mismatch(monkeypatch):
    port = cli.free_port()
    monkeypatch.setattr(cli, "new_state", lambda: "expected")
    open_browser = _drive_callback(port, "code=c&state=WRONG", [])
    with pytest.raises(OAuthError, match="state mismatch"):
        cli.run_loopback_flow(CLIENT, port, open_browser=open_browser)


def test_run_loopback_flow_user_denied(monkeypatch):
    port = cli.free_port()
    monkeypatch.setattr(cli, "new_state", lambda: "expected")
    open_browser = _drive_callback(port, "error=access_denied&state=expected", [])
    with pytest.raises(OAuthError, match="authorization denied"):
        cli.run_loopback_flow(CLIENT, port, open_browser=open_browser)


def test_free_port_returns_usable_port():
    p = cli.free_port()
    assert 1024 < p < 65536
