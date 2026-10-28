"""Tenant authentication for the cloud service.

A tenant is one server owner; its API key authenticates the tenant and its server ids say which of that
owner's servers may talk to us. Both travel as headers the plugin already sends on every request, so
authentication costs no extra round trip.

Everything here is a pure function of the headers plus the registry, which is what makes it testable without
a network or a running app.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from fastapi import HTTPException

AUTHORIZATION_SCHEME = "Bearer"


@dataclass(frozen=True)
class Tenant:
    """One server owner: the unit of quota and sample ownership."""

    tenant_id: str
    api_key: str
    servers: frozenset[str] = field(default_factory=frozenset)


class TenantRegistry:
    """The tenants this deployment knows about."""

    def __init__(self, tenants: list[Tenant] | None = None) -> None:
        self._by_key: dict[str, Tenant] = {tenant.api_key: tenant for tenant in (tenants or [])}

    def __bool__(self) -> bool:
        return bool(self._by_key)

    def authenticate(self, authorization: str | None, server_id: str | None) -> str:
        """Return the tenant id behind these headers, or raise the status the contract expects.

        401 means "we cannot tell who you are"; 403 means "we know who you are and you may not do this".
        Keeping the two apart matters operationally: a 401 is a misconfigured key, a 403 is a server that
        does not belong to the tenant the key names.
        """
        if not authorization:
            raise HTTPException(status_code=401, detail="missing authorization")

        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != AUTHORIZATION_SCHEME.lower() or not token:
            raise HTTPException(status_code=401, detail="malformed authorization")

        tenant = self._by_key.get(token)
        if tenant is None:
            raise HTTPException(status_code=401, detail="unknown api key")

        if not server_id:
            raise HTTPException(status_code=401, detail="missing x-server-id")

        if tenant.servers and server_id not in tenant.servers:
            raise HTTPException(status_code=403, detail="server does not belong to this tenant")

        return tenant.tenant_id
