"""Probe whether the Google Photos **Ambient API** is usable by this project
without Partner Program acceptance.

The docs contradict each other on whether Ambient access is hard-gated behind the
partner program or only at public-launch time. This runs the OAuth 2.0 device flow
(the "TVs and Limited Input devices" flow the Ambient API documents) for the
``photosambient.mediaitems`` scope, then makes a real authenticated call and
classifies the result:

- device-code request rejected (``invalid_scope``) -> scope not grantable to us
- API call 403 PERMISSION_DENIED -> partner-gated in practice; stick with Picker
- API call 400 INVALID_ARGUMENT -> we ARE authorized (just a bad request body) -> Ambient is usable
- API call 200 -> usable

Run with a **TVs and Limited Input devices** OAuth client's client_secret.json.
"""
from __future__ import annotations

import json
import os
import time
import uuid

import requests

from .oauth import ClientConfig, OAuthError, load_client_secret

DEVICE_CODE_URI = "https://oauth2.googleapis.com/device/code"
TOKEN_URI = "https://oauth2.googleapis.com/token"
DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
AMBIENT_SCOPE = "https://www.googleapis.com/auth/photosambient.mediaitems"
AMBIENT_BASE = "https://photosambient.googleapis.com/v1"


def request_device_code(client_id: str, *, session=None) -> dict:
    http = session or requests
    resp = http.post(
        DEVICE_CODE_URI,
        data={"client_id": client_id, "scope": AMBIENT_SCOPE},
        timeout=30,
    )
    payload = resp.json()
    if resp.status_code != 200:
        # invalid_scope here is the strongest "partner-gated at OAuth level" signal.
        raise OAuthError(
            f"device-code request failed ({resp.status_code}): "
            f"{payload.get('error', resp.text)} — "
            f"{payload.get('error_description', '')}"
        )
    return payload


def poll_for_token(
    client: ClientConfig, device_code: str, interval: int, *, session=None, sleep=time.sleep
) -> dict:
    http = session or requests
    while True:
        resp = http.post(
            TOKEN_URI,
            data={
                "client_id": client.client_id,
                "client_secret": client.client_secret,
                "device_code": device_code,
                "grant_type": DEVICE_GRANT,
            },
            timeout=30,
        )
        payload = resp.json()
        if resp.status_code == 200:
            return payload
        error = payload.get("error")
        if error == "authorization_pending":
            sleep(interval)
            continue
        if error == "slow_down":
            interval += 5
            sleep(interval)
            continue
        raise OAuthError(f"token poll failed: {error} — {payload.get('error_description', '')}")


def call_ambient(access_token: str, *, session=None) -> tuple[int, str]:
    """Make a real authenticated Ambient call. Returns (status_code, body)."""
    http = session or requests
    resp = http.post(
        f"{AMBIENT_BASE}/devices",
        headers={"Authorization": f"Bearer {access_token}"},
        json={},
        timeout=30,
    )
    return resp.status_code, resp.text


def classify(status: int, body: str) -> str:
    """Turn an Ambient API response into a verdict about access."""
    lowered = body.lower()
    if status == 200:
        return "USABLE: Ambient API returned 200. Authorized."
    if status == 403 or "permission_denied" in lowered:
        return (
            "GATED: 403 PERMISSION_DENIED. Ambient is partner-gated. "
            "Stick with the Picker API approach."
        )
    if status == 400 or "invalid_argument" in lowered:
        # IMPORTANT: a 400 does NOT prove access. Google validates the request body
        # BEFORE the permission check, so an invalid body can mask a 403 that a valid
        # request would hit. Confirmed 2026-06-05: empty devices.create -> 400, but a
        # valid devices.create -> 403 PERMISSION_DENIED (partner program).
        return (
            "INCONCLUSIVE: 400 INVALID_ARGUMENT only means the body was malformed; "
            "validation runs before the permission check. Re-test with a fully valid "
            "request (e.g. devices.create with a displayName) to get the real verdict."
        )
    if status == 401:
        return "AUTH PROBLEM: 401 — token/scope issue, not a clean access verdict."
    return f"UNKNOWN: status {status}. Inspect the body below."


def _verification_url(dc: dict) -> str:
    return dc.get("verification_url") or dc.get("verification_uri") or "(missing)"


def refresh_access_token(client: ClientConfig, refresh_token: str, *, session=None) -> str:
    """Mint a fresh access token from a stored refresh token."""
    http = session or requests
    resp = http.post(
        TOKEN_URI,
        data={
            "client_id": client.client_id,
            "client_secret": client.client_secret,
            "refresh_token": refresh_token,
            "grant_type": "refresh_token",
        },
        timeout=30,
    )
    payload = resp.json()
    if resp.status_code != 200:
        raise OAuthError(f"token refresh failed ({resp.status_code}): {payload}")
    return payload["access_token"]


def create_device(access_token: str, display_name: str, *, session=None) -> tuple[int, str]:
    http = session or requests
    resp = http.post(
        f"{AMBIENT_BASE}/devices",
        params={"requestId": str(uuid.uuid4())},
        headers={"Authorization": f"Bearer {access_token}"},
        json={"displayName": display_name},
        timeout=30,
    )
    return resp.status_code, resp.text


def get_device(access_token: str, device_id: str, *, session=None) -> tuple[int, str]:
    http = session or requests
    resp = http.get(
        f"{AMBIENT_BASE}/devices/{device_id}",
        headers={"Authorization": f"Bearer {access_token}"},
        timeout=30,
    )
    return resp.status_code, resp.text


def list_media_items(
    access_token: str, device_id: str, *, page_size: int = 100, session=None
) -> tuple[int, str]:
    http = session or requests
    resp = http.get(
        f"{AMBIENT_BASE}/mediaItems",
        params={"deviceId": device_id, "pageSize": page_size},
        headers={"Authorization": f"Bearer {access_token}"},
        timeout=30,
    )
    return resp.status_code, resp.text


STATE_FILE = "ambient-token.json"  # gitignored via *token*.json


def load_state(path: str = STATE_FILE) -> dict:
    try:
        with open(path) as fh:
            return json.load(fh)
    except OSError:
        return {}


def save_state(state: dict, path: str = STATE_FILE) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(state, fh, indent=2)


def main(argv=None) -> int:
    import argparse

    ap = argparse.ArgumentParser(
        description="Prototype the Google Photos Ambient API flow (device-flow auth, "
        "device registration, media listing) to capture real schemas before porting to Kotlin."
    )
    ap.add_argument(
        "--client-secret",
        required=True,
        help="client_secret.json for a 'TVs and Limited Input devices' OAuth client",
    )
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("auth-start", help="Request a device code and print it (non-blocking).")
    sub.add_parser("auth-finish", help="After approving, save the refresh token.")
    cd = sub.add_parser("create-device", help="Register an ambient device; prints settingsUri.")
    cd.add_argument("--name", required=True, help="Device display name, e.g. 'Portal Mini'")
    sub.add_parser("status", help="Poll the device; shows whether media sources are set.")
    sub.add_parser("list", help="List + download-test the device's ambient media items.")
    args = ap.parse_args(argv)

    client = load_client_secret(args.client_secret)
    state = load_state()

    if args.cmd == "auth-start":
        dc = request_device_code(client.client_id)
        state.update(device_code=dc["device_code"], interval=int(dc.get("interval", 5)))
        save_state(state)
        print("=== Approve on another device ===")
        print(f"  1. Go to: {_verification_url(dc)}")
        print(f"  2. Enter code: {dc.get('user_code')}")
        print("\nApprove it, then run: auth-finish")
        return 0

    if args.cmd == "auth-finish":
        if "device_code" not in state:
            print("Run auth-start first.")
            return 1
        tokens = poll_for_token(client, state["device_code"], int(state.get("interval", 5)))
        state["refresh_token"] = tokens["refresh_token"]
        state.pop("device_code", None)
        save_state(state)
        print("Authorized. Refresh token saved to", STATE_FILE)
        return 0

    # Remaining commands need a refresh token.
    if "refresh_token" not in state:
        print("Not authorized yet — run auth-start then auth-finish.")
        return 1
    access = refresh_access_token(client, state["refresh_token"])

    if args.cmd == "create-device":
        status, body = create_device(access, args.name)
        print(f"--- devices.create (status {status}) ---\n{body}\n")
        try:
            obj = json.loads(body)
            device_id = obj.get("deviceId") or obj.get("id")
            settings = obj.get("settingsUri") or obj.get("settingsUrl")
            if device_id:
                state["device_id"] = device_id
                save_state(state)
            if settings:
                print(f"Open this on your phone to choose albums:\n  {settings}")
        except json.JSONDecodeError:
            pass
        return 0 if status == 200 else 1

    device_id = state.get("device_id")
    if not device_id:
        print("No device yet — run create-device first.")
        return 1

    if args.cmd == "status":
        status, body = get_device(access, device_id)
        print(f"--- devices.get (status {status}) ---\n{body}")
        return 0

    if args.cmd == "list":
        status, body = list_media_items(access, device_id)
        print(f"--- mediaItems.list (status {status}) ---\n{body[:3000]}\n")
        try:
            items = json.loads(body).get("mediaItems", [])
        except json.JSONDecodeError:
            items = []
        if items:
            first = items[0]
            base = first.get("baseUrl") or first.get("mediaFile", {}).get("baseUrl")
            if base:
                r = requests.get(
                    base + "=d", headers={"Authorization": f"Bearer {access}"}, timeout=30
                )
                print(
                    f"download test: {r.status_code} "
                    f"{r.headers.get('content-type')} {len(r.content)} bytes"
                )
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
