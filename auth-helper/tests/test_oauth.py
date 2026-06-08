import json
import os
import stat
import urllib.parse

import pytest

from portal_auth.oauth import (
    SCOPE,
    ClientConfig,
    OAuthError,
    build_auth_url,
    exchange_code,
    load_client_secret,
    write_token_config,
)


class DummyResponse:
    def __init__(self, status_code, payload=None, text=""):
        self.status_code = status_code
        self._payload = payload or {}
        self.text = text

    def json(self):
        return self._payload


class DummySession:
    """Captures the POST args and returns a canned response."""

    def __init__(self, response):
        self._response = response
        self.calls = []

    def post(self, url, data=None, timeout=None):
        self.calls.append({"url": url, "data": data, "timeout": timeout})
        return self._response


CLIENT = ClientConfig("cid.apps.googleusercontent.com", "secret-xyz")


def test_build_auth_url_has_required_params():
    url = build_auth_url(CLIENT.client_id, "http://127.0.0.1:9000/", "st4te")
    q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    assert q["client_id"] == [CLIENT.client_id]
    assert q["redirect_uri"] == ["http://127.0.0.1:9000/"]
    assert q["response_type"] == ["code"]
    assert q["scope"] == [SCOPE]
    assert q["access_type"] == ["offline"]
    assert q["prompt"] == ["consent"]
    assert q["state"] == ["st4te"]


def test_load_client_secret_installed(tmp_path):
    p = tmp_path / "cs.json"
    p.write_text(json.dumps({"installed": {"client_id": "a", "client_secret": "b"}}))
    cfg = load_client_secret(str(p))
    assert cfg == ClientConfig("a", "b")


def test_load_client_secret_web(tmp_path):
    p = tmp_path / "cs.json"
    p.write_text(json.dumps({"web": {"client_id": "w", "client_secret": "s"}}))
    assert load_client_secret(str(p)) == ClientConfig("w", "s")


def test_load_client_secret_rejects_unknown_shape(tmp_path):
    p = tmp_path / "cs.json"
    p.write_text(json.dumps({"something_else": {}}))
    with pytest.raises(OAuthError, match="installed.*web"):
        load_client_secret(str(p))


def test_load_client_secret_missing_field(tmp_path):
    p = tmp_path / "cs.json"
    p.write_text(json.dumps({"installed": {"client_id": "a"}}))
    with pytest.raises(OAuthError, match="missing field"):
        load_client_secret(str(p))


def test_exchange_code_success_posts_correct_params():
    session = DummySession(DummyResponse(200, {"refresh_token": "rt", "access_token": "at"}))
    out = exchange_code("the-code", CLIENT, "http://127.0.0.1:9000/", session=session)
    assert out["refresh_token"] == "rt"
    sent = session.calls[0]["data"]
    assert sent["code"] == "the-code"
    assert sent["client_id"] == CLIENT.client_id
    assert sent["client_secret"] == CLIENT.client_secret
    assert sent["grant_type"] == "authorization_code"
    assert sent["redirect_uri"] == "http://127.0.0.1:9000/"


def test_exchange_code_http_error_raises():
    session = DummySession(DummyResponse(400, text="invalid_grant"))
    with pytest.raises(OAuthError, match="token exchange failed"):
        exchange_code("bad", CLIENT, "http://127.0.0.1:9000/", session=session)


def test_exchange_code_without_refresh_token_raises():
    session = DummySession(DummyResponse(200, {"access_token": "at"}))
    with pytest.raises(OAuthError, match="no refresh_token"):
        exchange_code("c", CLIENT, "http://127.0.0.1:9000/", session=session)


def test_write_token_config_content_and_perms(tmp_path):
    out = tmp_path / "token.json"
    write_token_config(str(out), CLIENT, "the-refresh-token")
    data = json.loads(out.read_text())
    assert data["client_id"] == CLIENT.client_id
    assert data["client_secret"] == CLIENT.client_secret
    assert data["refresh_token"] == "the-refresh-token"
    assert data["scope"] == SCOPE
    assert data["token_uri"].startswith("https://")
    mode = stat.S_IMODE(os.stat(out).st_mode)
    assert mode == 0o600
