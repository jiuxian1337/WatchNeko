"""The admin API: session-gated, and separate from tenant credentials."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.serve import build_service

PASSWORD = "correct horse"


def service():
    return build_service(
        Settings(
            database_path=":memory:",
            admin_username="root",
            admin_password=PASSWORD,
            admin_secret="test-secret",
        )
    )


def signed_in(client: TestClient) -> TestClient:
    response = client.post("/admin/api/login", json={"username": "root", "password": PASSWORD})
    assert response.status_code == 200, response.text
    return client


@pytest.fixture
def client() -> TestClient:
    return signed_in(TestClient(service().app))


def test_the_admin_api_refuses_an_unauthenticated_caller() -> None:
    client = TestClient(service().app)

    for path in ("/admin/api/tenants", "/admin/api/overview", "/admin/api/models"):
        assert client.get(path).status_code == 401


def test_a_wrong_password_does_not_sign_you_in() -> None:
    client = TestClient(service().app)

    response = client.post("/admin/api/login", json={"username": "root", "password": "wrong"})

    assert response.status_code == 401
    assert client.get("/admin/api/tenants").status_code == 401


def test_logging_in_reports_the_session(client: TestClient) -> None:
    assert client.get("/admin/api/session").json()["username"] == "root"


def test_logging_out_ends_the_session(client: TestClient) -> None:
    client.post("/admin/api/logout")

    assert client.get("/admin/api/tenants").status_code == 401


def test_a_tenant_can_be_created_and_its_key_shown_once(client: TestClient) -> None:
    response = client.post(
        "/admin/api/tenants", json={"tenant_id": "t1", "servers": ["s1"], "quota_per_minute": 500}
    )

    assert response.status_code == 201
    body = response.json()
    assert body["api_key"].startswith("wnk_")
    assert body["tenant"]["api_key_prefix"] == body["api_key"][:8]

    listed = client.get("/admin/api/tenants").json()["tenants"]
    assert [t["tenant_id"] for t in listed] == ["t1"]
    assert "api_key" not in listed[0], "the plaintext key must never come back in a listing"


def test_creating_a_duplicate_tenant_is_a_400(client: TestClient) -> None:
    client.post("/admin/api/tenants", json={"tenant_id": "t1"})

    response = client.post("/admin/api/tenants", json={"tenant_id": "t1"})

    assert response.status_code == 400


def test_a_created_key_actually_works_against_the_plugin_api(client: TestClient) -> None:
    key = client.post("/admin/api/tenants", json={"tenant_id": "t1", "servers": ["s1"]}).json()[
        "api_key"
    ]

    response = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": f"Bearer {key}", "x-server-id": "s1"},
    )

    # 404 is the cold start, i.e. authentication succeeded and the handler ran.
    assert response.status_code == 404


def test_rotating_returns_a_new_key_and_invalidates_the_old(client: TestClient) -> None:
    old_key = client.post("/admin/api/tenants", json={"tenant_id": "t1", "servers": ["s1"]}).json()[
        "api_key"
    ]

    new_key = client.post("/admin/api/tenants/t1/rotate").json()["api_key"]

    assert new_key != old_key
    old_attempt = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": f"Bearer {old_key}", "x-server-id": "s1"},
    )
    new_attempt = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": f"Bearer {new_key}", "x-server-id": "s1"},
    )
    assert old_attempt.status_code == 401
    assert new_attempt.status_code != 401


def test_revoking_cuts_the_key_off(client: TestClient) -> None:
    key = client.post("/admin/api/tenants", json={"tenant_id": "t1", "servers": ["s1"]}).json()[
        "api_key"
    ]

    client.post("/admin/api/tenants/t1/revoke")

    response = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": f"Bearer {key}", "x-server-id": "s1"},
    )
    assert response.status_code == 401


def test_updating_servers_and_quota_is_reflected(client: TestClient) -> None:
    client.post("/admin/api/tenants", json={"tenant_id": "t1", "servers": ["s1"]})

    body = client.patch(
        "/admin/api/tenants/t1", json={"servers": ["s1", "s2"], "quota_per_minute": 900}
    ).json()["tenant"]

    assert body["servers"] == ["s1", "s2"]
    assert body["quota_per_minute"] == 900


def test_deleting_a_tenant_removes_it(client: TestClient) -> None:
    client.post("/admin/api/tenants", json={"tenant_id": "t1"})

    assert client.delete("/admin/api/tenants/t1").status_code == 200
    assert client.get("/admin/api/tenants").json()["tenants"] == []


def test_unknown_tenants_are_404(client: TestClient) -> None:
    assert client.post("/admin/api/tenants/nope/rotate").status_code == 404
    assert client.patch("/admin/api/tenants/nope", json={}).status_code == 404
    assert client.delete("/admin/api/tenants/nope").status_code == 404


def test_the_overview_reports_metrics_and_model_state(client: TestClient) -> None:
    body = client.get("/admin/api/overview").json()

    assert "metrics" in body
    assert body["metrics"]["model_id"] == "none"
    assert body["tenants"]["total"] == 0
    assert body["retained_versions"] == 0


def test_the_model_list_is_empty_before_anything_is_trained(client: TestClient) -> None:
    body = client.get("/admin/api/models").json()

    assert body["models"] == []
    assert body["current"] is None


def test_rolling_back_with_no_earlier_model_is_a_conflict(client: TestClient) -> None:
    response = client.post("/admin/api/models/rollback")

    assert response.status_code == 409


def test_datasets_and_samples_are_reported_per_tenant(client: TestClient) -> None:
    client.post("/admin/api/tenants", json={"tenant_id": "t1"})

    datasets = client.get("/admin/api/datasets").json()["datasets"]
    samples = client.get("/admin/api/samples").json()["samples"]

    assert datasets[0]["tenant_id"] == "t1"
    assert datasets[0]["trainable"] is False
    assert samples[0]["samples"] == 0


def test_a_player_can_be_deleted_through_the_console(client: TestClient) -> None:
    response = client.delete("/admin/api/samples/t1/Steve")

    assert response.status_code == 200
    assert response.json()["deleted"] == 0


def test_retraining_reports_what_it_did(client: TestClient) -> None:
    response = client.post("/admin/api/retrain")

    assert response.status_code == 200
    assert response.json()["published"] is None


def test_the_admin_api_is_unreachable_with_a_tenant_key_alone(client: TestClient) -> None:
    """The two credentials are separate: a plugin key must not open the console."""
    key = client.post("/admin/api/tenants", json={"tenant_id": "t1", "servers": ["s1"]}).json()[
        "api_key"
    ]

    bare = TestClient(service().app)
    response = bare.get("/admin/api/tenants", headers={"authorization": f"Bearer {key}"})

    assert response.status_code == 401


# -- quota 0 means unlimited -----------------------------------------------------------------------


def test_a_tenant_can_be_created_unlimited(client: TestClient) -> None:
    response = client.post(
        "/admin/api/tenants", json={"tenant_id": "t-unlimited", "quota_per_minute": 0}
    )

    assert response.status_code == 201
    assert response.json()["tenant"]["quota_per_minute"] == 0


def test_a_tenant_can_be_switched_to_unlimited(client: TestClient) -> None:
    client.post("/admin/api/tenants", json={"tenant_id": "t1", "quota_per_minute": 100})

    body = client.patch("/admin/api/tenants/t1", json={"quota_per_minute": 0}).json()["tenant"]

    assert body["quota_per_minute"] == 0


def test_a_negative_quota_is_rejected_by_the_api(client: TestClient) -> None:
    response = client.post(
        "/admin/api/tenants", json={"tenant_id": "t1", "quota_per_minute": -1}
    )

    assert response.status_code == 422


def test_upload_history_shows_plain_text_player_names() -> None:
    service_instance = service()
    service_instance.store.record(
        "t1", "survival-1", "rs-1", "cheat",
        [{"sample_id": "a", "player_name": "Steve", "features": [0.0] * 22}],
    )
    client = signed_in(TestClient(service_instance.app))

    uploads = client.get("/admin/api/uploads").json()["uploads"]

    assert len(uploads) == 1
    assert uploads[0]["session_id"] == "rs-1"
    assert uploads[0]["players"] == ["Steve"]
    assert uploads[0]["remaining"] == 1


def test_an_upload_can_be_undone_through_the_console() -> None:
    service_instance = service()
    service_instance.store.record(
        "t1", "survival-1", "rs-1", "cheat",
        [{"sample_id": "a", "player_name": "Steve", "features": [0.0] * 22}],
    )
    client = signed_in(TestClient(service_instance.app))

    response = client.post("/admin/api/uploads/t1/rs-1/undo")

    assert response.status_code == 200
    assert response.json()["deleted"] == 1
    assert service_instance.store.sample_count("t1") == 0
    undone = client.get("/admin/api/uploads").json()["uploads"][0]
    assert undone["remaining"] == 0
    assert undone["undone_at"] is not None


def test_undoing_an_upload_needs_a_session() -> None:
    client = TestClient(service().app)

    assert client.post("/admin/api/uploads/t1/rs-1/undo").status_code == 401
    assert client.get("/admin/api/uploads").status_code == 401
