"""The console is a set of real pages, and the guard is server-side."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.serve import build_service

PASSWORD = "pw"


def client() -> TestClient:
    return TestClient(
        build_service(
            Settings(
                    database_path=":memory:",
                admin_username="root",
                admin_password=PASSWORD,
                admin_secret="s",
            )
        ).app,
        follow_redirects=False,
    )


@pytest.fixture
def signed_in() -> TestClient:
    http = client()
    response = http.post("/admin/api/login", json={"username": "root", "password": PASSWORD})
    assert response.status_code == 200
    return http


# -- the guard -------------------------------------------------------------------------------------


def test_the_landing_page_redirects_to_login_when_signed_out() -> None:
    response = client().get("/admin/")

    assert response.status_code == 303
    assert response.headers["location"] == "/admin/login"


def test_the_landing_page_redirects_to_the_dashboard_when_signed_in(signed_in: TestClient) -> None:
    response = signed_in.get("/admin/")

    assert response.status_code == 303
    assert response.headers["location"] == "/admin/dashboard"


def test_a_page_is_never_sent_to_someone_signed_out() -> None:
    """Not a client-side guard: the markup must not reach the browser at all."""
    http = client()

    for page in ("dashboard", "tenants", "uploads", "datasets", "models"):
        response = http.get(f"/admin/{page}")
        assert response.status_code == 303
        assert response.headers["location"] == "/admin/login"
        assert "WatchNeko 控制台" not in response.text


def test_the_login_page_is_reachable_without_a_session() -> None:
    response = client().get("/admin/login")

    assert response.status_code == 200
    assert 'id="login-form"' in response.text


def test_the_login_page_redirects_away_once_signed_in(signed_in: TestClient) -> None:
    response = signed_in.get("/admin/login")

    assert response.status_code == 303
    assert response.headers["location"] == "/admin/dashboard"


def test_an_unknown_page_is_a_404(signed_in: TestClient) -> None:
    assert signed_in.get("/admin/nope").status_code == 404


# -- one document per view -------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("page", "marker"),
    [
        ("dashboard", 'id="metric-grid"'),
        ("tenants", 'id="create-tenant-button"'),
        ("uploads", 'id="upload-table"'),
        ("datasets", 'id="dataset-table"'),
        ("models", 'id="model-table"'),
    ],
)
def test_each_view_is_its_own_document(signed_in: TestClient, page: str, marker: str) -> None:
    response = signed_in.get(f"/admin/{page}")

    assert response.status_code == 200
    assert marker in response.text
    # The page loads exactly one page script, not a bundle that renders every view.
    assert f"page-{page}.js" in response.text
    for other in {"dashboard", "tenants", "uploads", "datasets", "models"} - {page}:
        assert f"page-{other}.js" not in response.text


def test_each_page_marks_itself_in_the_navigation(signed_in: TestClient) -> None:
    response = signed_in.get("/admin/tenants")

    assert 'href="/admin/tenants"' in response.text
    assert 'aria-current="page"' in response.text


def test_the_shared_shell_is_not_duplicated_in_the_pages(signed_in: TestClient) -> None:
    """The rail and top bar come from one template, so they read identically everywhere."""
    pages = [signed_in.get(f"/admin/{page}").text for page in ("dashboard", "tenants", "uploads", "models")]

    for html in pages:
        assert 'class="rail"' in html
        assert 'id="logout-button"' in html
        assert '/admin/assets/console.js' in html


# -- assets ----------------------------------------------------------------------------------------


def test_the_assets_are_served() -> None:
    http = client()

    assert http.get("/admin/assets/md3.css").status_code == 200
    assert http.get("/admin/assets/console.js").status_code == 200
    for page in ("login", "dashboard", "tenants", "uploads", "datasets", "models"):
        assert http.get(f"/admin/assets/page-{page}.js").status_code == 200


def test_the_css_uses_md3_tokens() -> None:
    css = client().get("/admin/assets/md3.css").text

    for token in (
        "--md-sys-color-primary",
        "--md-sys-color-surface-container-high",
        "--md-sys-shape-corner-extra-large",
        "--md-sys-typescale-title-large",
    ):
        assert token in css
