// Shared console plumbing: the API client, the DOM helper, overlays, and the shell buttons.
//
// Every page imports this and renders only its own content. Nothing here knows what a "tenant" is — the
// page scripts do — which is what keeps each page's logic in its own file.
//
// DOM nodes are built rather than assembled as HTML strings: tenant ids and key prefixes come from the
// server, and string assembly out of them is one server-side change away from an injection.

const API = '/admin/api';

// ---------------------------------------------------------------- DOM helper

export function h(tag, props = {}, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') el.className = value;
        else if (key === 'text') el.textContent = value;
        else if (key.startsWith('on')) el.addEventListener(key.slice(2).toLowerCase(), value);
        else el.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
        if (child === null || child === undefined || child === false) continue;
        el.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return el;
}

export const icon = (name) =>
    h('span', { class: 'material-symbols-outlined', 'aria-hidden': 'true', text: name });

// ---------------------------------------------------------------- api

export class ApiError extends Error {
    constructor(status, detail) {
        super(detail || `HTTP ${status}`);
        this.status = status;
        this.detail = detail;
    }
}

async function request(path, options = {}) {
    const response = await fetch(`${API}${path}`, {
        credentials: 'same-origin',
        headers: options.body ? { 'content-type': 'application/json' } : {},
        ...options,
    });

    if (response.status === 401) {
        // The session is gone. Every page does the same thing about it, so it happens here.
        window.location.assign('/admin/login');
        throw new ApiError(401, '会话已过期');
    }
    if (response.status === 204) return null;

    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) {
        throw new ApiError(response.status, (body && body.detail) || `HTTP ${response.status}`);
    }
    return body;
}

export const api = {
    login: (username, password) =>
        request('/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
    logout: () => request('/logout', { method: 'POST' }),
    overview: () => request('/overview'),
    tenants: () => request('/tenants'),
    createTenant: (payload) => request('/tenants', { method: 'POST', body: JSON.stringify(payload) }),
    updateTenant: (id, payload) =>
        request(`/tenants/${encodeURIComponent(id)}`, {
            method: 'PATCH',
            body: JSON.stringify(payload),
        }),
    rotateTenant: (id) => request(`/tenants/${encodeURIComponent(id)}/rotate`, { method: 'POST' }),
    revokeTenant: (id) => request(`/tenants/${encodeURIComponent(id)}/revoke`, { method: 'POST' }),
    deleteTenant: (id) => request(`/tenants/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    datasets: () => request('/datasets'),
    samples: () => request('/samples'),
    uploads: () => request('/uploads'),
    undoUpload: (tenantId, sessionId) =>
        request(
            `/uploads/${encodeURIComponent(tenantId)}/${encodeURIComponent(sessionId)}/undo`,
            { method: 'POST' },
        ),
    models: () => request('/models'),
    rollback: () => request('/models/rollback', { method: 'POST' }),
    retrain: () => request('/retrain', { method: 'POST' }),
};

// ---------------------------------------------------------------- overlays

const overlayRoot = document.getElementById('overlay-root');
let snackbarTimer = null;

export function toast(message, kind = 'info') {
    document.querySelectorAll('.snackbar').forEach((node) => node.remove());
    const node = h('div', { class: 'snackbar', role: 'status' }, message);
    if (kind === 'error') {
        node.style.background = 'var(--md-sys-color-error)';
        node.style.color = 'var(--md-sys-color-on-error)';
    }
    overlayRoot.append(node);
    clearTimeout(snackbarTimer);
    snackbarTimer = setTimeout(() => node.remove(), 4200);
}

export function openDialog({ title, body, actions = [{ label: '关闭', value: null }], onOpen }) {
    return new Promise((resolve) => {
        const close = (value) => {
            scrim.remove();
            document.removeEventListener('keydown', onKey);
            resolve(value);
        };

        const onKey = (event) => {
            if (event.key === 'Escape') close(null);
        };

        const actionNodes = actions.map((action) =>
            h(
                'button',
                {
                    class: `btn btn--${action.variant || 'text'}`,
                    type: 'button',
                    onclick: () => close(action.value),
                },
                action.label,
            ),
        );

        const dialog = h(
            'div',
            { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
            h('h2', { class: 'dialog__title', text: title }),
            h('div', { class: 'dialog__body' }, body),
            h('div', { class: 'dialog__actions' }, actionNodes),
        );

        const scrim = h(
            'div',
            {
                class: 'scrim',
                onclick: (event) => {
                    if (event.target === scrim) close(null);
                },
            },
            dialog,
        );

        overlayRoot.append(scrim);
        document.addEventListener('keydown', onKey);
        if (onOpen) onOpen(dialog, close);
        const focusable = dialog.querySelector('input, select, button');
        if (focusable) focusable.focus();
    });
}

export async function confirmDialog(title, message, confirmLabel, danger = false) {
    const value = await openDialog({
        title,
        body: h('p', { text: message }),
        actions: [
            { label: '取消', value: false },
            { label: confirmLabel, value: true, variant: danger ? 'danger' : 'filled' },
        ],
    });
    return value === true;
}

/**
 * Copies text, with fallbacks.
 *
 * `navigator.clipboard` only exists in a secure context — HTTPS or localhost. A console reached over
 * `http://<ip>:8000` is not secure, so the modern API is simply absent there and the first attempt throws.
 * `execCommand('copy')` still works in that situation, deprecated as it is; and if even that fails, the text
 * gets selected so the operator can press Ctrl+C. A copy button that only works over HTTPS is a copy button
 * that does not work on the deployment it ships with.
 */
async function copyText(text, sourceNode) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch {
            // Permission denied or insecure context: fall through.
        }
    }

    try {
        const area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.top = '-1000px';
        area.style.opacity = '0';
        document.body.append(area);
        area.select();
        const copied = document.execCommand('copy');
        area.remove();
        if (copied) return true;
    } catch {
        // Fall through to selecting the text.
    }

    if (sourceNode) {
        const range = document.createRange();
        range.selectNodeContents(sourceNode);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
    }
    return false;
}

/** Shows a freshly minted API key. It is never retrievable again, so the dialog says so. */
export async function revealKey(tenantId, apiKey) {
    const secretNode = h('div', { class: 'secret', text: apiKey });

    const copyButton = h(
        'button',
        {
            class: 'btn btn--tonal',
            type: 'button',
            onclick: async () => {
                const copied = await copyText(apiKey, secretNode);
                if (copied) {
                    toast('已复制到剪贴板');
                } else {
                    toast('浏览器禁止自动复制，密钥已选中，请按 Ctrl+C', 'error');
                }
            },
        },
        icon('content_copy'),
        '复制',
    );

    await openDialog({
        title: `${tenantId} 的 API key`,
        body: h(
            'div',
            {},
            h('p', { text: '这是该密钥唯一一次以明文显示。关闭后只能重新轮换，无法再查看。' }),
            secretNode,
            h('p', { class: 'card__hint', text: '填入插件 config.yml 的 AnalysisA.cloud.api-key。' }),
            copyButton,
        ),
        actions: [{ label: '我已保存', value: true, variant: 'filled' }],
    });
}

// ---------------------------------------------------------------- shared bits

export function metricCard(label, value, hint) {
    return h(
        'div',
        { class: 'card' },
        h('span', { class: 'card__label', text: label }),
        h('span', { class: 'card__value', text: value }),
        hint ? h('span', { class: 'card__hint', text: hint }) : null,
    );
}

export function shortTime(iso) {
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

export function emptyRow(message, columns) {
    return h('p', { class: 'empty', text: message });
}

/** Runs a page's loader with the shell's buttons wired to it. */
export function startPage(load) {
    const run = async () => {
        try {
            await load();
        } catch (error) {
            if (error instanceof ApiError && error.status === 401) return; // already redirected
            const content = document.getElementById('content');
            if (content) {
                content.replaceChildren(
                    h(
                        'div',
                        { class: 'card card--outlined' },
                        h('p', { class: 'empty', text: `加载失败：${error.detail || error.message}` }),
                    ),
                );
            }
        }
    };

    document.getElementById('refresh-button')?.addEventListener('click', run);
    document.getElementById('logout-button')?.addEventListener('click', async () => {
        try {
            await api.logout();
        } catch {
            // Best effort: the cookie is cleared server-side or it expires.
        }
        window.location.assign('/admin/login');
    });

    run();
}
