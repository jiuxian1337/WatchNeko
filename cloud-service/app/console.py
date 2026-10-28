"""The console's pages: one real document per view, rendered server-side.

Each view is its own URL and its own HTML file, so the browser fetches only what it shows and a reload
lands back where it was. The shared chrome lives in a single `base.html` — duplicating the navigation rail
across five documents would be five places to forget a change.

The session guard runs here rather than in the browser: an unauthenticated request for `/admin/tenants`
never receives the page at all, it receives a redirect. A client-side guard would still ship the markup to
someone who should not have it.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Cookie, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates

from app.admin_auth import COOKIE_NAME, AdminAuth

#: The destinations in the navigation rail, in order.
NAV = (
    {"id": "dashboard", "label": "仪表盘", "icon": "dashboard"},
    {"id": "tenants", "label": "租户", "icon": "key"},
    {"id": "uploads", "label": "上传记录", "icon": "upload"},
    {"id": "datasets", "label": "数据集", "icon": "database"},
    {"id": "models", "label": "模型", "icon": "model_training"},
)

#: Page id -> document title. The ids are the URLs.
PAGES = {
    "dashboard": "仪表盘",
    "tenants": "租户",
    "uploads": "上传记录",
    "datasets": "数据集",
    "models": "模型",
}


def create_console_router(*, auth: AdminAuth, template_directory: str) -> APIRouter:
    router = APIRouter(prefix="/admin", include_in_schema=False)
    templates = Jinja2Templates(directory=template_directory)

    def signed_in(token: str | None) -> bool:
        return auth.verify(token) is not None

    @router.get("", response_class=HTMLResponse)
    @router.get("/", response_class=HTMLResponse)
    def root(wn_admin: str | None = Cookie(default=None)):
        """Landing point: the dashboard when signed in, the login page otherwise."""
        target = "/admin/dashboard" if signed_in(wn_admin) else "/admin/login"
        return RedirectResponse(target, status_code=303)

    @router.get("/login", response_class=HTMLResponse)
    def login_page(request: Request, wn_admin: str | None = Cookie(default=None)):
        if signed_in(wn_admin):
            return RedirectResponse("/admin/dashboard", status_code=303)
        return templates.TemplateResponse(request, "login.html", {})

    @router.get("/{page}", response_class=HTMLResponse)
    def page(request: Request, page: str, wn_admin: str | None = Cookie(default=None)):
        if page not in PAGES:
            raise HTTPException(status_code=404, detail="no such page")
        if not signed_in(wn_admin):
            return RedirectResponse("/admin/login", status_code=303)

        return templates.TemplateResponse(
            request,
            f"{page}.html",
            {"nav": NAV, "active": page, "title": PAGES[page]},
        )

    return router


def template_directory() -> str | None:
    """Where the page templates live, or ``None`` when the package was installed without them."""
    candidate = Path(__file__).parent / "templates"
    return str(candidate) if candidate.is_dir() else None
