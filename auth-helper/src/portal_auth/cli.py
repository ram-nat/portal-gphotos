"""Loopback OAuth runner and CLI entry point."""
from __future__ import annotations

import argparse
import http.server
import socket
import urllib.parse

from .oauth import (
    ClientConfig,
    OAuthError,
    build_auth_url,
    exchange_code,
    load_client_secret,
    new_state,
    write_token_config,
)

PACKAGE = "com.ramnat.portalgphotos"


def free_port() -> int:
    """Pick an unused loopback port."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class _CallbackHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 (http.server API)
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/":
            self.send_response(404)
            self.end_headers()
            return
        q = urllib.parse.parse_qs(parsed.query)
        self.server.oauth_result = {  # type: ignore[attr-defined]
            "code": q.get("code", [None])[0],
            "state": q.get("state", [None])[0],
            "error": q.get("error", [None])[0],
        }
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(
            b"<h1>Portal Photos</h1><p>Authorization received. "
            b"You can close this tab and return to the terminal.</p>"
        )

    def log_message(self, *args) -> None:  # silence the default stderr logging
        pass


def run_loopback_flow(client: ClientConfig, port: int, *, open_browser=None) -> str:
    """Drive the loopback OAuth flow and return a refresh token.

    ``open_browser`` is injectable for tests; defaults to ``webbrowser.open``.
    """
    if open_browser is None:
        import webbrowser

        open_browser = webbrowser.open

    redirect_uri = f"http://127.0.0.1:{port}/"
    state = new_state()
    url = build_auth_url(client.client_id, redirect_uri, state)

    server = http.server.HTTPServer(("127.0.0.1", port), _CallbackHandler)
    server.oauth_result = {}  # type: ignore[attr-defined]
    print("Opening your browser to authorize Google Photos access...")
    print(f"If it doesn't open automatically, visit:\n\n  {url}\n")
    open_browser(url)
    server.handle_request()  # serve exactly one callback request
    server.server_close()

    result = server.oauth_result  # type: ignore[attr-defined]
    if result.get("error"):
        raise OAuthError(f"authorization denied: {result['error']}")
    if result.get("state") != state:
        raise OAuthError("state mismatch — possible CSRF, aborting")
    if not result.get("code"):
        raise OAuthError("no authorization code in callback")

    tokens = exchange_code(result["code"], client, redirect_uri)
    return tokens["refresh_token"]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Mint a Google Photos Picker refresh token for the Portal Photos app."
    )
    ap.add_argument(
        "--client-secret",
        required=True,
        help="Path to the Desktop OAuth client_secret.json downloaded from Google Cloud",
    )
    ap.add_argument(
        "--output",
        default="portal-photos-token.json",
        help="Where to write the token config (default: ./portal-photos-token.json)",
    )
    ap.add_argument(
        "--port",
        type=int,
        default=0,
        help="Loopback callback port (0 = auto-pick a free port)",
    )
    args = ap.parse_args(argv)

    try:
        client = load_client_secret(args.client_secret)
        port = args.port or free_port()
        refresh_token = run_loopback_flow(client, port)
    except OAuthError as exc:
        print(f"ERROR: {exc}")
        return 1

    write_token_config(args.output, client, refresh_token)
    print(f"\nWrote token config to {args.output} (mode 0600).")
    print("\nPush it onto the Portal (app must be installed first):")
    print(
        f"  adb push {args.output} "
        f"/sdcard/Android/data/{PACKAGE}/files/token.json"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
