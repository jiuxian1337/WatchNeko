"""The endpoints of the plugin's contract, as a FastAPI application.

Inference and the contract shapes arrived in #11; storage, authentication and retention are #13's. Nothing
here changes what the shared corpus in ``contract/fixtures`` pins.

The registry, scorer, tenant list and store are all constructor arguments rather than globals, so every
policy this service enforces can be exercised from a test without patching module state.
"""

from __future__ import annotations

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.staticfiles import StaticFiles
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse

from app.auth import Tenant, TenantRegistry
from app.model import ModelRegistry, Scorer, StubScorer, classify
from app.metrics import Metrics
from app.quota import QuotaLimiter
from app.schemas import (
    CONTRACT_VERSION,
    ErrorResponse,
    InferenceRequest,
    InferenceResponse,
    ModelMetadata,
    SampleUploadRequest,
    SampleUploadResponse,
    Verdict,
)
from app.store import SampleStore


def _quota_exceeded() -> JSONResponse:
    return JSONResponse(
        status_code=429,
        content=ErrorResponse(
            contract_version=CONTRACT_VERSION, error="quota_exceeded"
        ).model_dump(),
    )


def _no_model() -> JSONResponse:
    return JSONResponse(
        status_code=404,
        content=ErrorResponse(contract_version=CONTRACT_VERSION, error="no_model").model_dump(),
    )


def create_app(
    registry: ModelRegistry | None = None,
    scorer: Scorer | None = None,
    tenants: list[Tenant] | None = None,
    store: SampleStore | None = None,
    quota: QuotaLimiter | None = None,
    metrics: Metrics | None = None,
    tenant_store=None,
    admin_router=None,
    console_router=None,
    assets_directory: str | None = None,
) -> FastAPI:
    """Build the application.

    ``tenants`` being ``None`` means authentication is not configured, and every caller is treated as one
    local tenant. That is the development posture only: a deployment that accepts uploads from other people
    must configure tenants, because otherwise anyone who can reach the port can write into the dataset.
    """
    active_registry = registry if registry is not None else ModelRegistry.with_default_model()
    if scorer is not None and active_registry.current() is not None:
        # An explicitly supplied scorer becomes the registry's scorer, so there is exactly one place that
        # decides what answers.
        active_registry.set(active_registry.current(), scorer)
    tenant_registry = TenantRegistry(tenants) if tenants is not None else None
    sample_store = store if store is not None else SampleStore()
    active_quota = quota
    active_metrics = metrics if metrics is not None else Metrics()

    app = FastAPI(title="WatchNeko cloud", version=str(CONTRACT_VERSION))
    app.state.store = sample_store

    def authenticate(
        authorization: str | None = Header(default=None),
        x_server_id: str | None = Header(default=None),
    ) -> str:
        """Resolve the calling tenant.

        A persisted tenant store wins over the in-memory list, because the store is what the console edits:
        rotating a key there must take effect on the next request, not at the next restart.
        """
        if tenant_store is not None:
            scheme, _, token = (authorization or "").partition(" ")
            if scheme.lower() != "bearer" or not token or not x_server_id:
                raise HTTPException(status_code=401, detail="missing credentials")
            tenant_id = tenant_store.authenticate(token, x_server_id)
            if tenant_id is None:
                raise HTTPException(status_code=401, detail="unknown or revoked credentials")
            return tenant_id

        if tenant_registry is None:
            return "local"
        return tenant_registry.authenticate(authorization, x_server_id)

    @app.exception_handler(Exception)
    async def unhandled(request: Request, exc: Exception) -> JSONResponse:
        """Record the failure, then answer in the contract's error shape.

        Without this the error rate can only ever count retrain failures, which would leave the rollback
        decision blind to the failure mode it most needs to see: requests that stop being answerable.
        """
        active_metrics.record_error()
        return JSONResponse(
            status_code=500,
            content=ErrorResponse(
                contract_version=CONTRACT_VERSION, error="internal_error"
            ).model_dump(),
        )

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError) -> JSONResponse:
        # The contract says every non-2xx body carries the error envelope. FastAPI's default 422 body does
        # not, so it is translated here rather than documented as an exception to the rule.
        return JSONResponse(
            status_code=422,
            content=ErrorResponse(
                contract_version=CONTRACT_VERSION, error="invalid_request"
            ).model_dump(),
        )

    @app.get(
        "/v1/model",
        response_model=ModelMetadata,
        responses={404: {"model": ErrorResponse}, 401: {"model": ErrorResponse}},
    )
    def model_metadata(tenant_id: str = Depends(authenticate)):
        model = active_registry.current()
        if model is None:
            return _no_model()
        return ModelMetadata(
            contract_version=CONTRACT_VERSION,
            model_id=model.model_id,
            dataset_id=model.dataset_id,
            feature_dimensions=model.feature_dimensions,
            thresholds=model.thresholds,
        )

    @app.post(
        "/v1/inference",
        response_model=InferenceResponse,
        responses={404: {"model": ErrorResponse}, 401: {"model": ErrorResponse}},
    )
    def infer(payload: InferenceRequest, tenant_id: str = Depends(authenticate)):
        active_metrics.record_request()

        if active_quota is not None and not active_quota.try_consume(tenant_id, len(payload.samples)):
            # Over quota is not evidence of anything: the plugin treats this exactly like an outage and
            # stops sending, so no verdict is produced and no violation can result.
            return _quota_exceeded()

        model = active_registry.current()
        if model is None:
            # The plugin never gets here: a cold start disables the check before it sends anything.
            return _no_model()

        # The scorer comes from the registry, not from a fixed field: publication and rollback both move it,
        # so what answers is always the model the metrics name.
        scorer = active_registry.scorer()

        verdicts = []
        for sample in payload.samples:
            score = scorer.score(sample.features)
            verdicts.append(
                Verdict(
                    sample_id=sample.sample_id,
                    score=score,
                    verdict=classify(score, model.thresholds),
                )
            )

        active_metrics.record_samples(tenant_id, len(payload.samples))
        active_metrics.record_verdicts([verdict.verdict.value for verdict in verdicts])

        return InferenceResponse(
            contract_version=CONTRACT_VERSION,
            model_id=model.model_id,
            verdicts=verdicts,
        )

    @app.get("/metrics", response_class=PlainTextResponse, include_in_schema=False)
    def prometheus_metrics() -> PlainTextResponse:
        """Prometheus scrape target.

        Unauthenticated on purpose: it is scraped by the operator's own monitoring, carries no player data,
        and putting the tenant key in front of it would only mean duplicating the key into a second place.
        """
        return PlainTextResponse(active_metrics.render())

    @app.post(
        "/v1/samples",
        response_model=SampleUploadResponse,
        responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}},
    )
    def upload_samples(
        payload: SampleUploadRequest,
        tenant_id: str = Depends(authenticate),
        x_server_id: str | None = Header(default=None),
    ) -> SampleUploadResponse:
        accepted, rejected = sample_store.record(
            tenant_id=tenant_id,
            server_id=x_server_id or "local",
            session_id=payload.recording_session_id,
            label=payload.label.value,
            samples=[sample.model_dump() for sample in payload.samples],
        )

        return SampleUploadResponse(
            contract_version=CONTRACT_VERSION,
            recording_session_id=payload.recording_session_id,
            accepted=accepted,
            rejected=rejected,
        )

    @app.delete("/v1/samples/{player_name}")
    def delete_player_samples(
        player_name: str,
        tenant_id: str = Depends(authenticate),
    ) -> dict:
        """Delete every sample belonging to one player, within the caller's own tenant.

        Scoped to the authenticated tenant on purpose: a server owner fulfilling a player's deletion
        request should not be able to reach into anyone else's data.
        """
        removed = sample_store.delete_player(tenant_id=tenant_id, player_name=player_name)
        return {
            "contract_version": CONTRACT_VERSION,
            "player_name": player_name,
            "deleted": removed,
        }

    if admin_router is not None:
        app.include_router(admin_router)

    if assets_directory is not None:
        # Assets live under their own prefix so the page routes can own /admin/{page} without a mount
        # swallowing them. Registered before the console router for the same reason.
        app.mount("/admin/assets", StaticFiles(directory=assets_directory), name="admin-assets")

    if console_router is not None:
        app.include_router(console_router)

    return app
