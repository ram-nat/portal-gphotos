"""One-time OAuth helper for the Portal Photos app.

Runs a loopback OAuth flow on a machine with a browser, mints a Google Photos
Picker refresh token, and writes a token config to sideload onto the Portal.
"""

__all__ = ["oauth", "cli"]
