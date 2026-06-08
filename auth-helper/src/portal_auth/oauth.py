"""Core OAuth logic for minting a Google Photos Picker refresh token.

Pure, side-effect-light functions so the flow is unit-testable without a browser
or live network (the network call in ``exchange_code`` accepts an injected session).
"""
from __future__ import annotations

import json
import os
import secrets
import urllib.parse
from dataclasses import dataclass

import requests

AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"


@dataclass(frozen=True)
class ClientConfig:
    client_id: str
    client_secret: str


class OAuthError(RuntimeError):
    """Raised for any recoverable failure in the OAuth flow."""


def load_client_secret(path: str) -> ClientConfig:
    """Parse a Google ``client_secret.json`` (Desktop or Web client)."""
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    node = data.get("installed") or data.get("web")
    if not node:
        raise OAuthError(
            "client_secret.json must contain an 'installed' (Desktop) or 'web' client"
        )
    try:
        return ClientConfig(node["client_id"], node["client_secret"])
    except KeyError as exc:
        raise OAuthError(f"client_secret.json missing field: {exc}") from exc


def new_state() -> str:
    """A random CSRF state token for the auth request."""
    return secrets.token_urlsafe(24)


def build_auth_url(client_id: str, redirect_uri: str, state: str) -> str:
    """Build the Google authorization URL for the Picker scope.

    ``access_type=offline`` + ``prompt=consent`` ensures Google returns a
    refresh token (and re-issues one even if the user previously consented).
    """
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPE,
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return AUTH_URI + "?" + urllib.parse.urlencode(params)


def exchange_code(
    code: str,
    client: ClientConfig,
    redirect_uri: str,
    *,
    session: requests.Session | None = None,
) -> dict:
    """Exchange an authorization code for tokens. Returns the token payload."""
    http = session or requests
    resp = http.post(
        TOKEN_URI,
        data={
            "code": code,
            "client_id": client.client_id,
            "client_secret": client.client_secret,
            "redirect_uri": redirect_uri,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    if resp.status_code != 200:
        raise OAuthError(f"token exchange failed ({resp.status_code}): {resp.text}")
    payload = resp.json()
    if "refresh_token" not in payload:
        raise OAuthError(
            "no refresh_token in response — revoke the prior grant at "
            "myaccount.google.com/permissions, or ensure access_type=offline "
            "and prompt=consent"
        )
    return payload


def write_token_config(path: str, client: ClientConfig, refresh_token: str) -> None:
    """Write the sideload config. Mode 0600 — it grants access to picked photos."""
    config = {
        "client_id": client.client_id,
        "client_secret": client.client_secret,
        "refresh_token": refresh_token,
        "token_uri": TOKEN_URI,
        "scope": SCOPE,
    }
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(config, fh, indent=2)
        fh.write("\n")
