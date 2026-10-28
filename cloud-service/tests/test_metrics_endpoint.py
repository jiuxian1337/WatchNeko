"""The scrape target itself."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.metrics import Metrics
from app.main import create_app

HEADERS = {"authorization": "Bearer k", "x-server-id": "s", "content-type": "application/json"}


def inference_body(count: int = 2) -> dict:
    return {
        "contract_version": 1,
        "samples": [
            {"sample_id": f"s{i}", "player_name": "h", "features": [0.0] * 22} for i in range(count)
        ],
    }


def test_metrics_are_served_as_prometheus_text() -> None:
    client = TestClient(create_app())

    response = client.get("/metrics")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert "# TYPE watchneko_samples_total" in response.text


def test_served_inference_shows_up_in_the_metrics() -> None:
    metrics = Metrics()
    client = TestClient(create_app(metrics=metrics))

    client.post("/v1/inference", json=inference_body(3), headers=HEADERS)

    rendered = client.get("/metrics").text
    assert 'watchneko_samples_total{tenant="local"} 3' in rendered
    assert "watchneko_requests_total 1" in rendered


def test_metrics_do_not_require_authentication() -> None:
    """The operator's scraper carries no tenant key, and the numbers contain no player data."""
    client = TestClient(create_app())

    assert client.get("/metrics").status_code == 200


def test_the_live_model_version_appears_in_the_metrics() -> None:
    client = TestClient(create_app())

    rendered = client.get("/metrics").text

    assert "watchneko_model_info" in rendered
    assert 'model_id="' in rendered
