"""The admin API the console talks to.

Every route here is behind an administrator session, and none of them are reachable with a tenant API key —
see `admin_auth`. That separation is the point of this module existing at all.

Payloads are deliberately flat dicts rather than deeply nested objects: the console renders them directly,
and a shape that needs unwrapping on the client is a shape that will be unwrapped slightly differently in
two places.
"""

from __future__ import annotations

from fastapi import APIRouter, Cookie, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field

from app.admin_auth import COOKIE_NAME, AdminAuth
from app.dataset import build_dataset
from app.metrics import Metrics
from app.model import ModelRegistry
from app.ops import OpsCycle
from app.publish import ModelPublisher
from app.store import SampleStore
from app.tenants import DEFAULT_QUOTA_PER_MINUTE, TenantStore


class LoginPayload(BaseModel):
    username: str = Field(min_length=1)
    password: str = Field(min_length=1)


class CreateTenantPayload(BaseModel):
    tenant_id: str = Field(min_length=1, max_length=64)
    servers: list[str] = Field(default_factory=list)
    #: Omitted means "use the deployment default from config.yaml"; 0 means unlimited.
    quota_per_minute: int | None = Field(default=None, ge=0)


class UpdateTenantPayload(BaseModel):
    servers: list[str] | None = None
    quota_per_minute: int | None = Field(default=None, ge=0)


def _tenant_dict(record) -> dict:
    return {
        "tenant_id": record.tenant_id,
        "api_key_prefix": record.api_key_prefix,
        "servers": list(record.servers),
        "quota_per_minute": record.quota_per_minute,
        "created_at": record.created_at,
        "revoked_at": record.revoked_at,
        "last_used_at": record.last_used_at,
        "revoked": record.is_revoked,
    }


def create_admin_router(
    *,
    auth: AdminAuth,
    tenants: TenantStore,
    metrics: Metrics,
    store: SampleStore,
    publisher: ModelPublisher,
    registry: ModelRegistry,
    ops: OpsCycle,
    default_quota_per_minute: int = DEFAULT_QUOTA_PER_MINUTE,
) -> APIRouter:
    router = APIRouter(prefix="/admin/api", tags=["admin"])

    def require_admin(wn_admin: str | None = Cookie(default=None)) -> str:
        username = auth.verify(wn_admin)
        if username is None:
            raise HTTPException(status_code=401, detail="not signed in")
        return username

    # -- session ---------------------------------------------------------------------------------

    @router.post("/login")
    def login(payload: LoginPayload, response: Response, request: Request) -> dict:
        token = auth.login(payload.username, payload.password)
        if token is None:
            raise HTTPException(status_code=401, detail="invalid credentials")
        response.set_cookie(
            COOKIE_NAME,
            token,
            httponly=True,
            samesite="strict",
            # Marked Secure when the request itself arrived over TLS, so the cookie is not sent back in
            # the clear. Not unconditional: a service behind a plain-HTTP reverse proxy would then never
            # receive the cookie at all and the console would be unusable.
            secure=request.url.scheme == "https",
            path="/",
            max_age=auth.session_ttl_seconds,
        )
        return {"username": payload.username}

    @router.post("/logout")
    def logout(response: Response) -> dict:
        response.delete_cookie(COOKIE_NAME, path="/")
        return {"signed_out": True}

    @router.get("/session")
    def session(username: str = Depends(require_admin)) -> dict:
        return {"username": username}

    # -- dashboard -------------------------------------------------------------------------------

    @router.get("/overview")
    def overview(username: str = Depends(require_admin)) -> dict:
        snapshot = metrics.snapshot()
        current = publisher.current()
        return {
            "metrics": snapshot,
            "model": None
            if current is None
            else {
                "model_id": current.model_id,
                "dataset_id": current.dataset_id,
                "trainer": current.trainer_name,
                "thresholds": {
                    "cheat_like": current.thresholds.cheat_like,
                    "legit_like": current.thresholds.legit_like,
                },
            },
            "tenants": {
                "total": len(tenants.list()),
                "active": len([t for t in tenants.list() if not t.is_revoked]),
            },
            "retained_versions": publisher.retained_versions(),
        }

    # -- tenants ---------------------------------------------------------------------------------

    @router.get("/tenants")
    def list_tenants(username: str = Depends(require_admin)) -> dict:
        return {"tenants": [_tenant_dict(record) for record in tenants.list()]}

    @router.post("/tenants", status_code=201)
    def create_tenant(
        payload: CreateTenantPayload, username: str = Depends(require_admin)
    ) -> dict:
        try:
            record, key = tenants.create(
                tenant_id=payload.tenant_id,
                servers=payload.servers,
                quota_per_minute=(
                    default_quota_per_minute
                    if payload.quota_per_minute is None
                    else payload.quota_per_minute
                ),
            )
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        # The plaintext key is returned exactly once; the console shows it in a dialog and never again.
        return {"tenant": _tenant_dict(record), "api_key": key}

    @router.patch("/tenants/{tenant_id}")
    def update_tenant(
        tenant_id: str, payload: UpdateTenantPayload, username: str = Depends(require_admin)
    ) -> dict:
        try:
            record = tenants.update(
                tenant_id,
                servers=payload.servers,
                quota_per_minute=payload.quota_per_minute,
            )
        except KeyError as error:
            raise HTTPException(status_code=404, detail="no such tenant") from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return {"tenant": _tenant_dict(record)}

    @router.post("/tenants/{tenant_id}/rotate")
    def rotate_key(tenant_id: str, username: str = Depends(require_admin)) -> dict:
        try:
            record, key = tenants.rotate(tenant_id)
        except KeyError as error:
            raise HTTPException(status_code=404, detail="no such tenant") from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return {"tenant": _tenant_dict(record), "api_key": key}

    @router.post("/tenants/{tenant_id}/revoke")
    def revoke_tenant(tenant_id: str, username: str = Depends(require_admin)) -> dict:
        try:
            record = tenants.revoke(tenant_id)
        except KeyError as error:
            raise HTTPException(status_code=404, detail="no such tenant") from error
        return {"tenant": _tenant_dict(record)}

    @router.delete("/tenants/{tenant_id}")
    def delete_tenant(tenant_id: str, username: str = Depends(require_admin)) -> dict:
        if tenants.get(tenant_id) is None:
            raise HTTPException(status_code=404, detail="no such tenant")
        tenants.delete(tenant_id)
        return {"deleted": tenant_id}

    # -- data ------------------------------------------------------------------------------------

    @router.get("/datasets")
    def list_datasets(username: str = Depends(require_admin)) -> dict:
        rows = []
        for record in tenants.list():
            dataset = build_dataset(store, record.tenant_id)
            rows.append(
                {
                    "tenant_id": record.tenant_id,
                    "dataset_id": dataset.dataset_id,
                    "samples": len(dataset.samples),
                    "cheat": dataset.cheat_count,
                    "legit": dataset.legit_count,
                    "trainable": dataset.is_trainable(),
                }
            )
        return {"datasets": rows}

    @router.get("/samples")
    def list_samples(username: str = Depends(require_admin)) -> dict:
        rows = []
        for record in tenants.list():
            rows.append(
                {
                    "tenant_id": record.tenant_id,
                    "samples": store.sample_count(record.tenant_id),
                    "sessions": len(store.session_ids(record.tenant_id)),
                    "players": len(store.player_names(record.tenant_id)),
                }
            )
        return {"samples": rows}

    @router.delete("/samples/{tenant_id}/{player_name}")
    def delete_player(tenant_id: str, player_name: str, username: str = Depends(require_admin)) -> dict:
        removed = store.delete_player(tenant_id=tenant_id, player_name=player_name)
        return {"tenant_id": tenant_id, "player_name": player_name, "deleted": removed}

    # -- upload history --------------------------------------------------------------------------

    @router.get("/uploads")
    def list_uploads(username: str = Depends(require_admin)) -> dict:
        """Every upload across every tenant, newest first, with the player names inside it.

        The names come back on the session row rather than from a per-row query over the samples: a batch
        that has been undone has no samples left, and the history still has to say who was in it.
        """
        return {"uploads": store.uploads()}

    @router.post("/uploads/{tenant_id}/{session_id}/undo")
    def undo_upload(
        tenant_id: str, session_id: str, username: str = Depends(require_admin)
    ) -> dict:
        """Delete one upload's samples.

        Only the samples go. The session row stays as a tombstone so the history still records that the
        upload happened and was undone, and any model already trained on the data is left alone: the
        next retrain simply will not see it.
        """
        removed = store.undo_upload(tenant_id, session_id)
        return {
            "tenant_id": tenant_id,
            "session_id": session_id,
            "deleted": removed,
        }

    # -- models ----------------------------------------------------------------------------------

    @router.get("/models")
    def list_models(username: str = Depends(require_admin)) -> dict:
        return {
            "models": [
                {
                    "model_id": record.model.model_id,
                    "dataset_id": record.model.dataset_id,
                    "trainer": record.model.trainer_name,
                    "rolled_back": record.rolled_back,
                    "cheat_like": record.model.thresholds.cheat_like,
                    "legit_like": record.model.thresholds.legit_like,
                }
                for record in reversed(publisher.history)
            ],
            "current": None if publisher.current() is None else publisher.current().model_id,
        }

    @router.post("/models/rollback")
    def rollback(username: str = Depends(require_admin)) -> dict:
        restored = ops.rollback_now()
        if restored is None:
            raise HTTPException(
                status_code=409, detail="no earlier model is retained, so there is nothing to roll back to"
            )
        return {"restored": restored.model_id, "dataset_id": restored.dataset_id}

    @router.post("/retrain")
    def retrain(username: str = Depends(require_admin)) -> dict:
        active = [record.tenant_id for record in tenants.list() if not record.is_revoked]
        outcome = ops.run_once(active)
        return {
            "published": None if outcome.published is None else outcome.published.model_id,
            "rolled_back": outcome.rolled_back,
            "rollback_reason": outcome.rollback_reason,
            "purged": outcome.purged,
            "skipped": outcome.skipped,
        }

    return router
