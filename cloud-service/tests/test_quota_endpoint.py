"""Quota at the endpoint: over the limit is a 429, and a 429 is not evidence."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.auth import Tenant
from app.main import create_app
from app.quota import QuotaLimiter

SURVIVAL = Tenant(tenant_id="t-survival", api_key="key-survival", servers=frozenset({"survival-1"}))
HEADERS = {"authorization": "Bearer key-survival", "x-server-id": "survival-1"}


def inference_body(count: int) -> dict:
    return {
        "contract_version": 1,
        "samples": [
            {"sample_id": f"s{i}", "player_name": "Steve", "features": [0.0] * 22}
            for i in range(count)
        ],
    }


def test_a_request_within_quota_gets_verdicts() -> None:
    client = TestClient(create_app(tenants=[SURVIVAL], quota=QuotaLimiter(samples_per_minute=10)))

    response = client.post("/v1/inference", json=inference_body(5), headers=HEADERS)

    assert response.status_code == 200
    assert len(response.json()["verdicts"]) == 5


def test_a_request_over_quota_is_refused_with_the_error_envelope() -> None:
    client = TestClient(create_app(tenants=[SURVIVAL], quota=QuotaLimiter(samples_per_minute=10)))

    response = client.post("/v1/inference", json=inference_body(11), headers=HEADERS)

    assert response.status_code == 429
    assert response.json()["error"] == "quota_exceeded"
    assert "verdicts" not in response.json(), "a refused request must not carry verdicts"


def test_a_refused_request_does_not_spend_quota() -> None:
    quota = QuotaLimiter(samples_per_minute=10)
    client = TestClient(create_app(tenants=[SURVIVAL], quota=quota))

    client.post("/v1/inference", json=inference_body(11), headers=HEADERS)

    assert quota.used("t-survival") == 0
    assert client.post("/v1/inference", json=inference_body(10), headers=HEADERS).status_code == 200


def test_quota_is_counted_per_tenant() -> None:
    other = Tenant(tenant_id="t-other", api_key="key-other", servers=frozenset({"other-1"}))
    client = TestClient(
        create_app(tenants=[SURVIVAL, other], quota=QuotaLimiter(samples_per_minute=10))
    )

    client.post("/v1/inference", json=inference_body(10), headers=HEADERS)
    blocked = client.post("/v1/inference", json=inference_body(1), headers=HEADERS)
    allowed = client.post(
        "/v1/inference",
        json=inference_body(10),
        headers={"authorization": "Bearer key-other", "x-server-id": "other-1"},
    )

    assert blocked.status_code == 429
    assert allowed.status_code == 200


def test_no_quota_configured_means_no_limit() -> None:
    client = TestClient(create_app(tenants=[SURVIVAL]))

    assert client.post("/v1/inference", json=inference_body(500), headers=HEADERS).status_code == 200


# -- unlimited quota, end to end -------------------------------------------------------------------
#
# A tenant row always carries a quota, so an authenticated request is always checked against that row —
# the limiter's own default only matters for callers with no tenant record at all.


def service_with_quota(default_quota: int):
    from app.config import Settings
    from app.serve import build_service

    return build_service(Settings(database_path=":memory:", quota_per_minute=default_quota))


def post_samples(service, key: str, count: int):
    return TestClient(service.app).post(
        "/v1/inference",
        json=inference_body(count),
        headers={"authorization": f"Bearer {key}", "x-server-id": "s1"},
    )


def test_a_tenant_with_an_unlimited_quota_is_never_refused() -> None:
    service = service_with_quota(10)
    _, key = service.tenants.create("t1", servers=["s1"], quota_per_minute=0)

    response = post_samples(service, key, 500)

    # 404 is the cold start: the request was accepted and reached the model lookup.
    assert response.status_code != 429


def test_a_tenant_with_a_finite_quota_is_refused_past_it() -> None:
    service = service_with_quota(6000)
    _, key = service.tenants.create("t1", servers=["s1"], quota_per_minute=5)

    assert post_samples(service, key, 5).status_code != 429
    assert post_samples(service, key, 1).status_code == 429


def test_a_default_of_zero_makes_new_tenants_unlimited() -> None:
    from app.config import Settings
    from app.serve import build_service

    service = build_service(
        Settings(
            database_path=":memory:",
            quota_per_minute=0,
            admin_username="root",
            admin_password="pw",
            admin_secret="s",
        )
    )
    client = TestClient(service.app)
    client.post("/admin/api/login", json={"username": "root", "password": "pw"})

    created = client.post("/admin/api/tenants", json={"tenant_id": "t1", "servers": ["s1"]})

    assert created.status_code == 201
    assert created.json()["tenant"]["quota_per_minute"] == 0
