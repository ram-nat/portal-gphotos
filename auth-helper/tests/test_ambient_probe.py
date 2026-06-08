import pytest

from portal_auth.ambient_probe import (
    classify,
    poll_for_token,
    request_device_code,
)
from portal_auth.oauth import ClientConfig, OAuthError

CLIENT = ClientConfig("cid", "secret")


class Resp:
    def __init__(self, status_code, payload=None, text=""):
        self.status_code = status_code
        self._payload = payload or {}
        self.text = text

    def json(self):
        return self._payload


class SeqSession:
    """Returns queued responses in order on successive .post calls."""

    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = []

    def post(self, url, **kwargs):
        self.calls.append({"url": url, **kwargs})
        return self._responses.pop(0)


def test_classify_403_is_gated():
    assert classify(403, '{"error":{"status":"PERMISSION_DENIED"}}').startswith("GATED")


def test_classify_400_is_inconclusive():
    # A 400 must NOT be read as authorized — validation precedes the permission check.
    assert classify(400, '{"error":{"status":"INVALID_ARGUMENT"}}').startswith("INCONCLUSIVE")


def test_classify_200_is_usable():
    assert classify(200, "{}").startswith("USABLE")


def test_classify_401_is_auth_problem():
    assert classify(401, "unauthorized").startswith("AUTH PROBLEM")


def test_request_device_code_success():
    session = SeqSession([Resp(200, {"device_code": "dc", "user_code": "ABCD", "interval": 5})])
    out = request_device_code(CLIENT.client_id, session=session)
    assert out["user_code"] == "ABCD"
    assert session.calls[0]["data"]["scope"].endswith("photosambient.mediaitems")


def test_request_device_code_invalid_scope_raises():
    session = SeqSession([Resp(400, {"error": "invalid_scope", "error_description": "bad"})])
    with pytest.raises(OAuthError, match="invalid_scope"):
        request_device_code(CLIENT.client_id, session=session)


def test_poll_for_token_pending_then_success():
    session = SeqSession(
        [
            Resp(428, {"error": "authorization_pending"}),
            Resp(200, {"access_token": "AT", "expires_in": 3600}),
        ]
    )
    slept = []
    out = poll_for_token(CLIENT, "dc", 1, session=session, sleep=slept.append)
    assert out["access_token"] == "AT"
    assert slept == [1]  # waited once between the pending and success


def test_poll_for_token_hard_error_raises():
    session = SeqSession([Resp(400, {"error": "access_denied", "error_description": "no"})])
    with pytest.raises(OAuthError, match="access_denied"):
        poll_for_token(CLIENT, "dc", 1, session=session, sleep=lambda _: None)
