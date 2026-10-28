"""Upload end to end: authenticated, stored, and traceable to the session that produced it."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.auth import Tenant
from app.main import create_app
from app.store import SampleStore

SURVIVAL = Tenant(tenant_id="t-survival", api_key="key-survival", servers=frozenset({"survival-1"}))
HEADERS = {"authorization": "Bearer key-survival", "x-server-id": "survival-1"}


def upload_body(session_id: str = "rs-0001", label: str = "cheat", sample_ids=("a", "b")) -> dict:
    return {
        "contract_version": 1,
        "recording_session_id": session_id,
        "label": label,
        "samples": [
            {"sample_id": sample_id, "player_name": "Steve", "features": [0.0] * 22}
            for sample_id in sample_ids
        ],
    }


def client_with(store: SampleStore, tenants=None) -> TestClient:
    return TestClient(create_app(tenants=tenants, store=store))


def test_an_authenticated_upload_lands_in_the_store() -> None:
    store = SampleStore()
    client = client_with(store, [SURVIVAL])

    response = client.post("/v1/samples", json=upload_body(), headers=HEADERS)

    assert response.status_code == 200
    assert response.json()["accepted"] == 2
    assert store.sample_count("t-survival") == 2


def test_the_uploaded_label_reaches_storage() -> None:
    store = SampleStore()
    client = client_with(store, [SURVIVAL])

    client.post("/v1/samples", json=upload_body(session_id="rs-legit", label="legit"), headers=HEADERS)

    assert store.session_label("t-survival", "rs-legit") == "legit"


def test_an_unauthenticated_upload_is_refused_and_writes_nothing() -> None:
    store = SampleStore()
    client = client_with(store, [SURVIVAL])

    response = client.post(
        "/v1/samples", json=upload_body(), headers={"authorization": "Bearer wrong"}
    )

    assert response.status_code == 401
    assert store.sample_count() == 0, "a rejected upload must not touch the dataset"


def test_a_server_outside_the_tenant_cannot_upload() -> None:
    store = SampleStore()
    client = client_with(store, [SURVIVAL])

    response = client.post(
        "/v1/samples",
        json=upload_body(),
        headers={"authorization": "Bearer key-survival", "x-server-id": "not-mine"},
    )

    assert response.status_code == 403
    assert store.sample_count() == 0


def test_a_replayed_upload_reports_the_rejections() -> None:
    store = SampleStore()
    client = client_with(store, [SURVIVAL])

    client.post("/v1/samples", json=upload_body(), headers=HEADERS)
    response = client.post("/v1/samples", json=upload_body(), headers=HEADERS)

    assert response.json()["accepted"] == 0
    assert response.json()["rejected"] == 2
    assert store.sample_count("t-survival") == 2


def test_inference_is_authenticated_too() -> None:
    client = client_with(SampleStore(), [SURVIVAL])

    response = client.post(
        "/v1/inference",
        json={
            "contract_version": 1,
            "samples": [{"sample_id": "a", "player_name": "h", "features": [0.0] * 22}],
        },
        headers={"authorization": "Bearer wrong", "x-server-id": "survival-1"},
    )

    assert response.status_code == 401


def test_a_tenant_can_delete_one_players_samples() -> None:
    store = SampleStore()
    client = client_with(store, [SURVIVAL])
    client.post("/v1/samples", json=upload_body(), headers=HEADERS)

    response = client.delete("/v1/samples/Steve", headers=HEADERS)

    assert response.status_code == 200
    assert response.json()["deleted"] == 2
    assert store.sample_count("t-survival") == 0


def test_deleting_requires_authentication() -> None:
    store = SampleStore()
    client = client_with(store, [SURVIVAL])
    client.post("/v1/samples", json=upload_body(), headers=HEADERS)

    response = client.delete("/v1/samples/Steve", headers={"authorization": "Bearer wrong"})

    assert response.status_code == 401
    assert store.sample_count("t-survival") == 2, "an unauthenticated delete must not remove anything"
