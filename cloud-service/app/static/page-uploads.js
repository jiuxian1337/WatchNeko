import { api, confirmDialog, h, startPage, toast } from './console.js';

// Upload history: one row per recording session, with the plain-text player names it contains and a
// button that deletes that batch. Undo leaves the row behind (remaining drops to 0) so the history still
// says the upload happened — deleting the evidence of an undo would make the list lie.

function formatTime(iso) {
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? iso : date.toLocaleString('zh-CN', { hour12: false });
}

async function load() {
    const { uploads } = await api.uploads();
    const container = document.getElementById('upload-table');

    if (uploads.length === 0) {
        container.replaceChildren(h('p', { class: 'empty', text: '还没有任何上传。' }));
        return;
    }

    const rows = uploads.map((upload) => {
        const undone = Boolean(upload.undone_at);
        const players = upload.players || [];

        return h(
            'tr',
            {},
            h('td', {}, h('span', { class: 'mono', text: upload.tenant_id })),
            h('td', {}, h('span', { class: 'mono', text: upload.session_id })),
            h('td', {}, h('span', { class: 'mono', text: upload.server_id })),
            h('td', { text: formatTime(upload.created_at) }),
            h(
                'td',
                {},
                upload.label === 'cheat'
                    ? h('span', { class: 'chip chip--warn', text: 'cheat' })
                    : h('span', { class: 'chip chip--ok', text: 'legit' }),
            ),
            h('td', { text: players.length === 0 ? '—' : players.join(', ') }),
            h('td', { text: upload.remaining }),
            h('td', { text: upload.rejected }),
            h(
                'td',
                {},
                undone
                    ? h('span', { class: 'chip', text: '已撤销' })
                    : h('button', {
                          class: 'btn btn--text btn--small',
                          type: 'button',
                          text: '撤销',
                          onclick: () => undo(upload),
                      }),
            ),
        );
    });

    container.replaceChildren(
        h(
            'table',
            {},
            h(
                'thead',
                {},
                h(
                    'tr',
                    {},
                    h('th', { text: '租户' }),
                    h('th', { text: '录制会话' }),
                    h('th', { text: '服务器' }),
                    h('th', { text: '时间' }),
                    h('th', { text: '标注' }),
                    h('th', { text: '玩家' }),
                    h('th', { text: '样本' }),
                    h('th', { text: '重复' }),
                    h('th', { text: '操作' }),
                ),
            ),
            h('tbody', {}, rows),
        ),
    );
}

async function undo(upload) {
    const players = (upload.players || []).join(', ') || '（无）';
    const ok = await confirmDialog(
        '撤销这次上传？',
        `将删除会话 ${upload.session_id} 的 ${upload.remaining} 个样本（玩家：${players}）。`
            + '已训练的模型不受影响，下一次训练不会再使用这些样本。',
        '撤销',
        true,
    );
    if (!ok) return;

    try {
        const result = await api.undoUpload(upload.tenant_id, upload.session_id);
        toast(`已删除 ${result.deleted} 个样本`);
        await load();
    } catch (error) {
        toast(error.detail || '撤销失败', 'error');
    }
}

startPage(load);
