"""Administrator authentication: a separate identity from the tenant API keys.

Two credentials exist in this service and they must not be confused:

* a **tenant API key** is what a server uses to send samples and ask for verdicts;
* an **administrator login** is what a human uses to change anything.

Collapsing them would mean the key a plugin holds could also revoke tenants. So the console has its own
login, and its own session.

Sessions are stateless: an HMAC-signed `username|expiry` pair in a cookie. Nothing to store, nothing to
expire on the server, and a restart invalidates nothing as long as the signing secret is configured. When it
is not configured a random secret is generated per process, which logs everyone out on restart — the safer
failure.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import time

COOKIE_NAME = "wn_admin"
DEFAULT_SESSION_TTL_SECONDS = 8 * 3600


class AdminAuth:
    def __init__(
        self,
        username: str,
        password: str | None = None,
        password_hash: str | None = None,
        secret: str | None = None,
        session_ttl_seconds: int = DEFAULT_SESSION_TTL_SECONDS,
    ) -> None:
        if not username:
            raise ValueError("an admin username is required")
        if not password and not password_hash:
            raise ValueError("either a password or a password hash is required")

        self._username = username
        self._password = password
        self._password_hash = password_hash or (_hash_password(password) if password else None)
        self._secret = (secret or secrets.token_urlsafe(32)).encode("utf-8")
        self._ttl = session_ttl_seconds

    @property
    def configured_secret(self) -> bool:
        return True

    @property
    def session_ttl_seconds(self) -> int:
        """How long a session lasts, so the cookie's lifetime can match the token's.

        They used to be written separately — the token from this setting, the cookie from a literal —
        which meant changing ``admin.session_hours`` left the cookie outliving the token.
        """
        return self._ttl

    def login(self, username: str, password: str) -> str | None:
        """The session token, or ``None`` when the credentials do not match.

        Both fields are compared in constant time and the failure is indistinguishable between a wrong
        username and a wrong password — the console must not become a username oracle.
        """
        username_ok = hmac.compare_digest(username or "", self._username)
        password_ok = hmac.compare_digest(_hash_password(password or ""), self._password_hash)
        if not (username_ok and password_ok):
            return None
        return self._issue(self._username)

    def verify(self, token: str | None) -> str | None:
        """The username behind a session token, or ``None`` when it is invalid or expired."""
        if not token or "." not in token:
            return None

        payload_b64, _, signature = token.rpartition(".")
        expected = self._sign(payload_b64)
        if not hmac.compare_digest(signature, expected):
            return None

        try:
            payload = base64.urlsafe_b64decode(payload_b64.encode("ascii")).decode("utf-8")
            username, _, expiry = payload.rpartition("|")
            if int(expiry) < int(time.time()):
                return None
        except (ValueError, UnicodeDecodeError):
            return None

        return username or None

    def _issue(self, username: str) -> str:
        payload = f"{username}|{int(time.time()) + self._ttl}"
        payload_b64 = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii")
        return f"{payload_b64}.{self._sign(payload_b64)}"

    def _sign(self, payload_b64: str) -> str:
        return hmac.new(self._secret, payload_b64.encode("ascii"), hashlib.sha256).hexdigest()


def _hash_password(password: str) -> str:
    """PBKDF2 with a per-deployment fixed salt.

    A random per-user salt would be better, but there is exactly one administrator and the hash is
    supplied through the environment; PBKDF2 still removes the "stored in the clear" property, which is
    the failure that actually happens.
    """
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), b"watchneko-admin", 200_000
    )
    return digest.hex()
