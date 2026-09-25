const workspacePath = window.WORKSPACE_PATH || '';
document.getElementById('ws-name').textContent = '— ' + workspacePath.split('/').filter(Boolean).pop();

const editorHost = document.getElementById('editor');
const emptyEl = document.getElementById('editor-empty');
const KIND_ICON = { html: '📄', css: '🎨', json: '📊', img: '🖼️' };
const EDITABLE_KINDS = ['html', 'css', 'json'];

// ====== STATE ======
let pagedEnabled = true;
let popoutWin = null;
let lastPreviewDoc = null;
let lastPreviewInfo = null;
let previewDirty = true;
let previewSeq = 0;               // contatore per scartare risposte di preview fuori ordine
let view = null;                  // EditorView CM6 (uno, con stato per tab)

let treeRoot = null;
const fileKinds = new Map();      // relPath -> 'html' | 'css' | 'json'
const openTabs = [];              // relPath[] nell'ordine dei tab
const tabStates = new Map();      // relPath -> EditorState (doc, selection, history)
const tabScroll = new Map();      // relPath -> scrollTop
let activeTab = null;
let lastActiveTemplate = null;
const buffers = new Map();        // relPath -> contenuto corrente (solo tab aperti)
const savedContents = new Map();  // relPath -> contenuto su disco
const collapsedDirs = new Set();
const releaseTabInfo = new Map();  // relPath release -> { document, version, path } (tab sola lettura)
const imageTabs = new Map();       // relPath immagine -> src (tab viewer, sola lettura)

// ====== HELPERS ======
function basename(p) { return p.split('/').pop(); }
function swapExt(p, ext) { return p.replace(/\.[a-z0-9]+$/i, '') + ext; }
function kindOfPath(p) {
    const k = fileKinds.get(p);
    if (k) return k;
    const m = p.match(/\.([a-z0-9]+)$/i);
    return m ? m[1].toLowerCase() : '';
}
function isModified(path) {
    if (releaseTabInfo.has(path) || imageTabs.has(path)) return false; // sola lettura = mai dirty
    return buffers.has(path) && buffers.get(path) !== savedContents.get(path);
}
function flushActive() {
    if (activeTab && view && !imageTabs.has(activeTab)) buffers.set(activeTab, view.state.doc.toString());
}
function dirtyFilesMap() {
    flushActive();
    const map = {};
    for (const p of openTabs) if (isModified(p)) map[p] = buffers.get(p);
    return map;
}
async function apiGet(url) {
    const resp = await fetch(url, { credentials: 'same-origin' });
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    return await resp.text();
}
async function loadFile(path) {
    return await apiGet('/workspace/file?workspace=' + encodeURIComponent(workspacePath) + '&file=' + encodeURIComponent(path));
}

// ====== EDITOR (CodeMirror 6) ======
function initEditor() {
    if (!window.CM6) {
        emptyEl.innerHTML = '<span>⚠️ Bundle editor non disponibile (/js/editor.js)</span>';
        return false;
    }
    view = new CM6.EditorView({ parent: editorHost, state: CM6.makeState('html', '', cmHandlers()) });
    return true;
}

function cmHandlers() {
    return {
        onChange: () => {
            if (activeTab) buffers.set(activeTab, view.state.doc.toString());
            autoPreview();
            renderTabs();
        },
        onSave: () => saveActive(),
    };
}

// ====== EXPLORER ======
async function loadTree() {
    try {
        const resp = await fetch('/workspace/tree?workspace=' + encodeURIComponent(workspacePath), { credentials: 'same-origin' });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        treeRoot = await resp.json();
        indexKinds(treeRoot);
        renderTree();
    } catch (e) {
        document.getElementById('explorer-tree').innerHTML = '<div class="tree-empty">Errore caricamento albero file</div>';
        console.error('Tree load failed:', e);
    }
}

function indexKinds(node) {
    if (node.type === 'dir') { (node.children || []).forEach(indexKinds); }
    else fileKinds.set(node.path, node.kind);
}

function renderTree() {
    const host = document.getElementById('explorer-tree');
    host.innerHTML = '';
    if (!treeRoot || !(treeRoot.children || []).length) {
        host.innerHTML = '<div class="tree-empty">Nessun file supportato nel workspace</div>';
        return;
    }
    host.appendChild(buildTreeChildren(treeRoot.children, 0));
}

function buildTreeChildren(nodes, depth) {
    const frag = document.createDocumentFragment();
    for (const node of nodes) {
        if (node.type === 'dir') {
            const details = document.createElement('details');
            details.dataset.path = node.path;
            details.open = !collapsedDirs.has(node.path);
            details.addEventListener('toggle', () => {
                if (details.open) collapsedDirs.delete(node.path); else collapsedDirs.add(node.path);
            });
            const summary = document.createElement('summary');
            summary.textContent = node.name;
            summary.style.paddingLeft = (8 + depth * 14) + 'px';
            if (node.path === selectedPath) summary.classList.add('selected');
            summary.addEventListener('click', () => setSelection(node.path, true, summary));
            details.appendChild(summary);
            details.appendChild(buildTreeChildren(node.children || [], depth + 1));
            frag.appendChild(details);
        } else {
            const row = document.createElement('div');
            row.className = 'tree-file' + (node.path === activeTab ? ' active' : '')
                + (node.path === selectedPath ? ' selected' : '');
            row.style.paddingLeft = (14 + depth * 14) + 'px';
            row.title = node.path;
            const icon = document.createElement('span');
            icon.textContent = KIND_ICON[node.kind] || '📄';
            row.appendChild(icon);
            row.appendChild(document.createTextNode(node.name));
            if (openTabs.includes(node.path) && isModified(node.path)) {
                const dot = document.createElement('span');
                dot.className = 'dirty-dot';
                dot.textContent = '●';
                row.appendChild(dot);
            }
            if (node.kind === 'img') {
                row.title = node.path + ' (immagine: sola visualizzazione)';
                row.addEventListener('click', () => { setSelection(node.path, false, row); openImageTab(node.path, assetUrl(node.path)); });
            } else {
                row.addEventListener('click', () => { setSelection(node.path, false, row); openFile(node.path); });
            }
            frag.appendChild(row);
        }
    }
    return frag;
}

// ====== EXPLORER FILE OPS (nuovo file/cartella, rinomina con proposta riferimenti) ======
let selectedPath = null;
let selectedIsDir = false;
let editing = null;              // { mode: 'create'|'rename', input, rowEl, parentRel, oldPath, isDir, done }

function setSelection(path, isDir, el) {
    selectedPath = path;
    selectedIsDir = isDir;
    const prev = document.querySelector('.tree-file.selected, .explorer-tree summary.selected');
    if (prev) prev.classList.remove('selected');
    if (el) el.classList.add('selected');
}

function parentDirOf(p) { return p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : ''; }

function invalidNameReason(name) {
    if (!name) return 'Il nome non può essere vuoto';
    if (name !== name.trim()) return 'Il nome non può iniziare/finire con spazi';
    if (name.startsWith('.')) return 'I nomi non possono iniziare con un punto';
    if (/[\\/:*?"<>|]/.test(name)) return 'Il nome contiene caratteri non validi';
    if (name.length > 255) return 'Nome troppo lungo (max 255)';
    return null;
}

async function apiPostJson(url, body) {
    return await fetch(url, {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
}

// --- inline edit: unico meccanismo per creazione e rinomina ---
function makeNameInput(value) {
    const input = document.createElement('input');
    input.className = 'tree-edit-input';
    input.value = value;
    input.spellcheck = false;
    input.addEventListener('keydown', (e) => {
        e.stopPropagation();
        if (e.key === 'Enter') { e.preventDefault(); confirmEditing(); }
        if (e.key === 'Escape') { e.preventDefault(); cancelEditing(); }
    });
    input.addEventListener('blur', () => { if (editing && !editing.done) confirmEditing(); });
    return input;
}

function beginEditing(opts) {
    if (editing) cancelEditing();
    editing = { ...opts, done: false };
}

function endEditing() {
    if (editing) editing.done = true;
    editing = null;
}

function cancelEditing() {
    if (!editing) return;
    endEditing();
    renderTree();
}

function showInlineError(rowEl, msg) {
    clearInlineError(rowEl);
    const err = document.createElement('div');
    err.className = 'edit-error';
    err.style.paddingLeft = rowEl.style.paddingLeft;
    err.textContent = msg;
    rowEl.after(err);
}

function clearInlineError(rowEl) {
    if (rowEl.nextElementSibling && rowEl.nextElementSibling.classList.contains('edit-error')) {
        rowEl.nextElementSibling.remove();
    }
}

// +📄 / +📁 / menu contestuale su cartella: crea nella cartella selezionata (o root)
function startCreate(kind) {
    closeContextMenu();
    if (editing) return;
    const isDir = kind === 'dir';
    let parentRel = '';
    if (selectedPath) parentRel = selectedIsDir ? selectedPath : parentDirOf(selectedPath);
    const host = document.getElementById('explorer-tree');
    if (parentRel) {
        const segs = parentRel.split('/');
        segs.forEach((_, i) => {
            const acc = segs.slice(0, i + 1).join('/');
            collapsedDirs.delete(acc);
            const d = host.querySelector('details[data-path="' + CSS.escape(acc) + '"]');
            if (d) d.open = true;
        });
    }
    const container = parentRel
        ? (host.querySelector('details[data-path="' + CSS.escape(parentRel) + '"]') || host)
        : host;
    const depth = parentRel ? parentRel.split('/').length : 0;
    const row = document.createElement('div');
    row.className = 'tree-file';
    row.style.paddingLeft = (14 + depth * 14) + 'px';
    const defaultName = isDir ? 'nuova-cartella' : 'nuovo.html';
    const input = makeNameInput(defaultName);
    row.appendChild(input);
    container.appendChild(row);
    beginEditing({ mode: 'create', input, rowEl: row, parentRel, isDir });
    input.focus();
    const dot = defaultName.lastIndexOf('.');
    input.setSelectionRange(0, dot > 0 ? dot : defaultName.length);
}

// Rinomina: inline edit sull'etichetta del nodo selezionato
function startRename() {
    closeContextMenu();
    if (!selectedPath || editing) return;
    const path = selectedPath;
    const isDir = selectedIsDir;
    const host = document.getElementById('explorer-tree');
    let rowEl;
    if (isDir) {
        const d = host.querySelector('details[data-path="' + CSS.escape(path) + '"]');
        rowEl = d ? d.querySelector(':scope > summary') : null;
    } else {
        rowEl = [...host.querySelectorAll('.tree-file')].find(r => r.title === path || r.title.startsWith(path + ' ('));
    }
    if (!rowEl) return;
    const name = path.split('/').pop();
    const input = makeNameInput(name);
    beginEditing({ mode: 'rename', input, rowEl, oldPath: path, isDir });
    rowEl.textContent = '';
    const iconEl = document.createElement('span');
    iconEl.textContent = isDir ? '📁' : (KIND_ICON[kindOfPath(path)] || '📄');
    rowEl.style.display = 'flex';
    rowEl.append(iconEl, input);
    input.focus();
    const dot = name.lastIndexOf('.');
    input.setSelectionRange(0, dot > 0 ? dot : name.length);
}

async function confirmEditing() {
    if (!editing || editing.done) return;
    editing.done = true;              // guard: Enter + blur non devono dopp-innescare
    const st = editing;
    const name = st.input.value.trim();
    const reason = invalidNameReason(name);
    if (reason) {
        st.done = false;
        st.input.classList.add('invalid');
        showInlineError(st.rowEl, reason);
        st.input.focus();
        return;
    }
    st.input.classList.remove('invalid');
    clearInlineError(st.rowEl);
    try {
        if (st.mode === 'create') {
            const rel = st.parentRel ? st.parentRel + '/' + name : name;
            const resp = await apiPostJson('/workspace/node',
                { workspace: workspacePath, path: rel, type: st.isDir ? 'dir' : 'file' });
            if (resp.status === 409) {
                st.done = false;
                showInlineError(st.rowEl, 'Esiste già un elemento con questo nome');
                st.input.focus();
                st.input.select();
                return;
            }
            if (!resp.ok) throw new Error(await resp.text());
            endEditing();
            const segs = st.parentRel.split('/').filter(Boolean);
            segs.forEach((_, i) => collapsedDirs.delete(segs.slice(0, i + 1).join('/')));
            await loadTree();
            if (!st.isDir) await openFile(rel);
            showStatus('✓ Creato ' + rel);
        } else {
            const parent = parentDirOf(st.oldPath);
            const to = parent ? parent + '/' + name : name;
            if (to === st.oldPath) { endEditing(); renderTree(); return; }
            endEditing();
            renderTree();
            await doRename(st.oldPath, to);
        }
    } catch (e) {
        endEditing();
        renderTree();
        alert('Operazione fallita: ' + e.message);
    }
}

// --- rinomina con proposta di aggiornamento riferimenti (D6 della spec) ---
async function doRename(from, to) {
    let refs = [];
    try {
        const scan = await (await apiPostJson('/workspace/rename/scan', { workspace: workspacePath, from })).json();
        refs = scan.references || [];
    } catch (e) { /* la scansione è un miglioramento: senza, si rinomina comunque */ }
    if (refs.length) {
        openRenameDialog(from, to, refs);
    } else {
        await executeRename(from, to, false);
    }
}

let pendingRename = null;
function openRenameDialog(from, to, refs) {
    pendingRename = { from, to };
    document.getElementById('rename-title').textContent = 'Rinomina ' + from + '  →  ' + to;
    const body = document.getElementById('rename-refs');
    body.innerHTML = '';
    const hint = document.createElement('div');
    hint.className = 'panel-hint';
    hint.textContent = 'Aggiorna anche i riferimenti trovati nei template:';
    body.appendChild(hint);
    for (const r of refs) {
        const row = document.createElement('label');
        row.className = 'rename-ref';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = true;
        const txt = document.createElement('span');
        const f = document.createElement('div');
        f.className = 'rf';
        f.textContent = r.file;
        const s = document.createElement('div');
        s.className = 'snip';
        s.textContent = r.snippet;
        txt.append(f, s);
        row.append(cb, txt);
        body.appendChild(row);
    }
    document.getElementById('rename-confirm').textContent = 'Rinomina e aggiorna (' + refs.length + ')';
    document.getElementById('rename-overlay').classList.add('open');
}

function closeRenameDialog() {
    document.getElementById('rename-overlay').classList.remove('open');
    pendingRename = null;
}

async function confirmRenameDialog() {
    if (!pendingRename) return;
    const { from, to } = pendingRename;
    const boxes = [...document.querySelectorAll('#rename-refs input[type="checkbox"]')];
    const anyChecked = boxes.some(cb => cb.checked);
    closeRenameDialog();
    await executeRename(from, to, anyChecked);
}

async function executeRename(from, to, updateRefs) {
    try {
        const resp = await apiPostJson('/workspace/rename',
            { workspace: workspacePath, from, to, updateReferences: updateRefs });
        if (resp.status === 409) { alert('Esiste già un elemento con il nome di destinazione.'); return; }
        if (!resp.ok) { alert('Rinomina fallita:\n' + await resp.text()); return; }
        const data = await resp.json();
        for (const mv of (data.moved || [])) applyRenameToState(mv.from, mv.to);
        await loadTree();
        renderTabs();
        const n = (data.updated || []).length;
        showStatus('✓ Rinominato ' + from + ' → ' + to + (n ? ' — ' + n + ' file aggiornati' : ''));
    } catch (e) {
        alert('Rinomina fallita: ' + e.message);
    }
}

// Remap dei tab aperti e dello stato editor dopo uno spostamento (anche con prefisso cartella)
function applyRenameToState(from, to) {
    const remap = (p) => {
        if (p === from) return to;
        if (p.startsWith(from + '/')) return to + p.slice(from.length);
        return null;
    };
    const rekey = (m) => {
        for (const [k, v] of [...m]) {
            const n = remap(k);
            if (n) { m.delete(k); m.set(n, v); }
        }
    };
    for (let i = 0; i < openTabs.length; i++) {
        const n = remap(openTabs[i]);
        if (n) openTabs[i] = n;
    }
    rekey(buffers); rekey(savedContents); rekey(tabStates); rekey(tabScroll); rekey(fileKinds); rekey(imageTabs);
    if (activeTab) activeTab = remap(activeTab) || activeTab;
    if (lastActiveTemplate) lastActiveTemplate = remap(lastActiveTemplate) || lastActiveTemplate;
}

// --- menu contestuale (right-click, delegato sull'albero: sopravvive ai re-render) ---
const ctxMenu = document.getElementById('ctx-menu');
const explorerTreeHost = document.getElementById('explorer-tree');
explorerTreeHost.addEventListener('contextmenu', (e) => {
    const row = e.target.closest('.tree-file');
    const summary = e.target.closest('.explorer-tree summary, summary');
    if (row) {
        e.preventDefault();
        openContextMenu(e, row.title.replace(/ \(immagine\)$/, ''), false, row);
    } else if (summary && summary.parentElement && summary.parentElement.dataset.path) {
        e.preventDefault();
        openContextMenu(e, summary.parentElement.dataset.path, true, summary);
    }
});
function openContextMenu(e, path, isDir, el) {
    setSelection(path, isDir, el);
    ctxMenu.innerHTML = '';
    const items = [];
    if (isDir) {
        items.push({ label: '📄 Nuovo file', fn: () => { closeContextMenu(); startCreate('file'); } });
        items.push({ label: '📁 Nuova cartella', fn: () => { closeContextMenu(); startCreate('dir'); } });
    }
    items.push({ label: '✏️ Rinomina', kbd: 'F2', fn: () => { closeContextMenu(); startRename(); } });
    items.push({ label: '🗑️ Elimina', fn: () => { closeContextMenu(); startDelete(path, isDir); } });
    for (const it of items) {
        const item = document.createElement('div');
        item.className = 'ctx-item';
        const lbl = document.createElement('span');
        lbl.textContent = it.label;
        item.appendChild(lbl);
        if (it.kbd) {
            const k = document.createElement('span');
            k.className = 'kbd';
            k.textContent = it.kbd;
            item.appendChild(k);
        }
        item.addEventListener('click', it.fn);
        ctxMenu.appendChild(item);
    }
    ctxMenu.classList.add('open');
    const mw = ctxMenu.offsetWidth, mh = ctxMenu.offsetHeight;
    ctxMenu.style.left = Math.max(4, Math.min(e.clientX, window.innerWidth - mw - 8)) + 'px';
    ctxMenu.style.top = Math.max(4, Math.min(e.clientY, window.innerHeight - mh - 8)) + 'px';
}

function closeContextMenu() { ctxMenu.classList.remove('open'); }
// chiusura su pointerdown esterno (primario): precede ogni click sintetizzato dal right-click,
// quindi il menu non può essere richiuso dalla stessa interazione che lo ha aperto
document.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    if (!ctxMenu.contains(e.target)) closeContextMenu();
});
// se l'albero scorre, il menu perde l'ancora → si chiude (solo scroll reale dell'albero)
explorerTreeHost.addEventListener('scroll', closeContextMenu);
window.addEventListener('resize', closeContextMenu);
window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { closeContextMenu(); closeRenameDialog(); closeDeleteDialog(); }
    if (e.key === 'F2' && !editing) {
        const ae = document.activeElement;
        if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) return;
        if (selectedPath) { e.preventDefault(); startRename(); }
    }
});

// ====== EXPLORER RELEASE (sola lettura) ======
let releaseTree = [];

async function loadReleaseTree() {
    const host = document.getElementById('release-tree');
    try {
        const resp = await fetch('/workspace/release-tree?workspace=' + encodeURIComponent(workspacePath), { credentials: 'same-origin' });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        releaseTree = await resp.json();
        renderReleaseTree();
    } catch (e) {
        host.innerHTML = '<div class="tree-empty">Errore caricamento release</div>';
        console.error('Release tree load failed:', e);
    }
}

function relKind(path) {
    const m = path.match(/\.([a-z0-9]+)$/i);
    return m ? m[1].toLowerCase() : '';
}

function renderReleaseTree() {
    const host = document.getElementById('release-tree');
    host.innerHTML = '';
    if (!releaseTree || !releaseTree.length) {
        host.innerHTML = '<div class="tree-empty">Nessuna release pubblicata</div>';
        return;
    }
    for (const doc of releaseTree) {
        const docEl = document.createElement('details');
        docEl.className = 'rel-doc';
        docEl.open = true; // il documento si apre da solo: le versioni sono subito visibili
        const sum = document.createElement('summary');
        sum.textContent = '📦 ' + doc.document;
        sum.title = doc.document;
        docEl.appendChild(sum);
        for (const ver of [...(doc.versions || [])].sort((a, b) => b.version - a.version)) {
            docEl.appendChild(buildReleaseVersion(doc.document, ver));
        }
        host.appendChild(docEl);
    }
}

function buildReleaseVersion(documentPath, ver) {
    const el = document.createElement('details');
    el.className = 'rel-ver';
    el.open = ver.active; // la versione attiva è espansa di default
    const sum = document.createElement('summary');
    const label = document.createElement('span');
    label.textContent = 'v' + ver.version;
    sum.appendChild(label);
    const badge = document.createElement('span');
    badge.className = 'rel-badge ' + (ver.status || 'candidate');
    badge.textContent = ver.status || '?';
    sum.appendChild(badge);
    if (ver.active) {
        const act = document.createElement('span');
        act.className = 'rel-badge active';
        act.textContent = 'attiva ✓';
        sum.appendChild(act);
    }
    if (ver.note) sum.title = ver.note;
    el.appendChild(sum);

    const own = (ver.files || []).filter(f => !f.dep);
    const deps = (ver.files || []).filter(f => f.dep);
    for (const f of own) el.appendChild(buildReleaseFileRow(documentPath, ver.version, f.path));
    if (deps.length) {
        const depsEl = document.createElement('details');
        depsEl.className = 'rel-deps';
        const dsum = document.createElement('summary');
        dsum.textContent = '🔒 dipendenze congelate (' + deps.length + ')';
        depsEl.appendChild(dsum);
        for (const f of deps) depsEl.appendChild(buildReleaseFileRow(documentPath, ver.version, f.path));
        el.appendChild(depsEl);
    }
    return el;
}

function relIsImage(path) {
    return ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(relKind(path));
}

function buildReleaseFileRow(documentPath, version, path) {
    const row = document.createElement('div');
    row.className = 'rel-file' + (path === activeTab ? ' active' : '');
    row.style.paddingLeft = '26px';
    const ext = relKind(path);
    const isImage = relIsImage(path);
    row.title = path + (isImage ? ' (release: immagine, sola visualizzazione)' : ' (release: sola lettura)');
    const icon = document.createElement('span');
    icon.textContent = isImage ? '🖼️' : (KIND_ICON[ext] || '📄');
    const name = document.createElement('span');
    name.textContent = basename(path);
    const lock = document.createElement('span');
    lock.className = 'lock';
    lock.textContent = '🔒';
    row.append(icon, name, lock);
    if (isImage) {
        row.addEventListener('click', () => openImageTab(path,
            '/workspace/release-asset?workspace=' + encodeURIComponent(workspacePath)
            + '&path=' + encodeURIComponent(path)));
    } else {
        row.addEventListener('click', () => openReleaseFile(path, documentPath, version));
    }
    return row;
}

async function openReleaseFile(path, document, version) {
    if (!openTabs.includes(path)) {
        try {
            const content = await apiGet('/workspace/release-file?workspace=' + encodeURIComponent(workspacePath)
                + '&path=' + encodeURIComponent(path));
            buffers.set(path, content);
            savedContents.set(path, content);
            tabStates.set(path, CM6.makeState(relKind(path), content, {
                readOnly: true,
                onSave: () => showStatus('🔒 Versione pubblicata: sola lettura'),
            }));
            openTabs.push(path);
            releaseTabInfo.set(path, { document, version, path });
        } catch (e) {
            console.error('Open release failed:', e);
            alert('Impossibile aprire ' + path);
            return;
        }
    }
    activateTab(path);
}

function activeReleaseInfo() {
    return (activeTab && releaseTabInfo.has(activeTab)) ? releaseTabInfo.get(activeTab) : null;
}

// ====== DELETE (solo snapshot; bloccato se referenziato) ======
let pendingDelete = null;

function startDelete(path, isDir) {
    closeContextMenu();
    if (isDir) { openDeleteDialog(path, true, [], false); return; }
    apiPostJson('/workspace/rename/scan', { workspace: workspacePath, from: path })
        .then(r => r.json())
        .then(scan => {
            const refs = scan.references || [];
            if (refs.length) openDeleteDialog(path, false, refs, true);
            else openDeleteDialog(path, false, [], false);
        })
        .catch(() => openDeleteDialog(path, false, [], false));
}

function openDeleteDialog(path, isDir, refs, blocked) {
    pendingDelete = { path, isDir, blocked };
    document.getElementById('delete-title').textContent = (blocked ? '🚫 Impossibile cancellare ' : '🗑️ Eliminare ') + path;
    const body = document.getElementById('delete-body');
    body.innerHTML = '';
    if (blocked) {
        const hint = document.createElement('div');
        hint.className = 'del-line';
        hint.innerHTML = 'È <b>referenziato da ' + refs.length + ' file</b>: rimuovi o aggiorna prima i riferimenti' +
            ' (a mano o con l\'assistente 🤖), oppure sostituisci il file con uno nuovo. Poi riprova.';
        body.appendChild(hint);
        for (const r of refs) {
            const rf = document.createElement('div');
            rf.className = 'del-ref';
            rf.textContent = '• ' + r.file;
            body.appendChild(rf);
        }
        document.getElementById('delete-confirm').textContent = 'Ho capito';
        document.getElementById('delete-overlay').classList.add('open');
        return;
    }
    const lines = [];
    if (isDir) {
        lines.push('La cartella verrà rimossa (le cartelle si cancellano solo se vuote).');
    } else {
        if (releaseTabInfo.has(path) || imageTabs.has(path)) { /* mai: tab release aperti solo da lì */ }
        if (relKind(path) === 'html') {
            lines.push('📄 È un documento principale: nessun riferimento in ingresso.');
            const docRel = path.replace(/\.html$/i, '');
            const entry = (releaseTree || []).find(d => d.document === docRel);
            if (entry && entry.versions.length) {
                lines.push('⚠️ Esistono ' + entry.versions.length + ' versioni pubblicate: continueranno a essere servite dall\'API (immutabili), ma il documento non sarà più modificabile.');
            }
            const pair = swapExt(path, '.json');
            if (openTabs.includes(pair) || fileKinds.has(pair)) {
                lines.push('📊 Verrà cancellata anche la coppia ' + basename(pair) + ' (accoppiata del modello).');
            }
        }
        if (isModified(path)) {
            lines.push('⚠️ Il tab aperto ha modifiche non salvate che andranno perse.');
        }
        lines.push('Le release già pubblicate non vengono toccate. Recuperabile dallo storico git solo se era stato committato.');
    }
    for (const t of lines) {
        const d = document.createElement('div');
        d.className = t.startsWith('⚠️') ? 'del-warn' : 'del-line';
        d.textContent = t;
        body.appendChild(d);
    }
    document.getElementById('delete-confirm').textContent = 'Elimina definitivamente';
    document.getElementById('delete-overlay').classList.add('open');
}

function closeDeleteDialog() {
    document.getElementById('delete-overlay').classList.remove('open');
    pendingDelete = null;
}

async function confirmDelete() {
    if (!pendingDelete) return;
    const { path, blocked } = pendingDelete;
    closeDeleteDialog();
    if (blocked) return; // dialogo informativo: nessuna azione
    try {
        const resp = await apiPostJson('/workspace/delete', { workspace: workspacePath, path });
        if (resp.status === 409) {
            const data = await resp.json();
            openDeleteDialog(path, false, (data.referencedBy || []).map(f => ({ file: f })), true);
            return;
        }
        if (!resp.ok) { alert('Cancellazione fallita:\n' + await resp.text()); return; }
        const data = await resp.json();
        for (const p of [...openTabs]) {
            if (p === path || p.startsWith(path + '/')) closeTab(p);
        }
        await loadTree();
        showStatus('✓ Cancellato ' + ((data.deleted || []).join(', ') || path));
    } catch (e) {
        alert('Cancellazione fallita: ' + e.message);
    }
}

// ====== TABS ======
function assetUrl(path) {
    return '/workspace/asset?workspace=' + encodeURIComponent(workspacePath) + '&file=' + encodeURIComponent(path);
}

function openImageTab(path, src) {
    if (!openTabs.includes(path)) {
        openTabs.push(path);
        imageTabs.set(path, src);
    }
    activateTab(path);
}

async function openFile(path) {
    if (!EDITABLE_KINDS.includes(kindOfPath(path))) {
        if (kindOfPath(path) === 'img') { openImageTab(path, assetUrl(path)); return; }
        showStatus('❓ ' + path + ' (tipo non supportato)');
        return;
    }
    if (!openTabs.includes(path)) {
        try {
            const content = await loadFile(path);
            buffers.set(path, content);
            savedContents.set(path, content);
            tabStates.set(path, CM6.makeState(kindOfPath(path), content, cmHandlers()));
            openTabs.push(path);
        } catch (e) {
            console.error('Open failed:', e);
            alert('Impossibile aprire ' + path);
            return;
        }
    }
    activateTab(path);
}

function activateTab(path) {
    if (!view) return;
    if (activeTab && activeTab !== path && !imageTabs.has(activeTab)) {
        tabStates.set(activeTab, view.state);
        tabScroll.set(activeTab, view.scrollDOM.scrollTop);
        buffers.set(activeTab, view.state.doc.toString());
    }
    activeTab = path;
    emptyEl.style.display = 'none';
    const imageHost = document.getElementById('image-host');
    if (imageTabs.has(path)) {
        // viewer immagine: l'editor resta nascosto, nessuno stato CM da ripristinare
        editorHost.style.display = 'none';
        imageHost.style.display = 'flex';
        imageHost.innerHTML = '<img src="' + imageTabs.get(path) + '" alt="" />'
            + '<div class="img-caption">🖼️ ' + path + ' — sola visualizzazione</div>';
        renderTabs();
        renderTree();
        renderReleaseTree();
        return;
    }
    imageHost.style.display = 'none';
    view.setState(tabStates.get(path));
    view.scrollDOM.scrollTop = tabScroll.get(path) || 0;
    editorHost.style.display = '';
    if (kindOfPath(path) === 'html' && !releaseTabInfo.has(path)) lastActiveTemplate = path;
    renderTabs();
    renderTree();
    renderReleaseTree();
    if (popoutWin && !popoutWin.closed) refreshPreview();
}

function closeTab(path, ev) {
    if (ev) ev.stopPropagation();
    const idx = openTabs.indexOf(path);
    if (idx === -1) return;
    flushActive();
    if (isModified(path) && !confirm('"' + basename(path) + '" ha modifiche non salvate. Chiudere comunque?')) return;
    openTabs.splice(idx, 1);
    buffers.delete(path);
    savedContents.delete(path);
    tabStates.delete(path);
    tabScroll.delete(path);
    releaseTabInfo.delete(path);
    imageTabs.delete(path);
    if (activeTab === path) {
        activeTab = null;
        const next = openTabs[Math.min(idx, openTabs.length - 1)];
        if (next) { activateTab(next); } else { showEmpty(); }
    }
    if (lastActiveTemplate === path || !openTabs.includes(lastActiveTemplate)) recomputeLastTemplate();
    renderTabs();
    renderTree();
    renderReleaseTree();
}

function recomputeLastTemplate() {
    lastActiveTemplate = null;
    for (let i = openTabs.length - 1; i >= 0; i--) {
        if (kindOfPath(openTabs[i]) === 'html') { lastActiveTemplate = openTabs[i]; break; }
    }
}

function showEmpty() {
    activeTab = null;
    editorHost.style.display = 'none';
    emptyEl.style.display = 'flex';
}

function renderTabs() {
    const bar = document.getElementById('tabbar');
    bar.innerHTML = '';
    for (const path of openTabs) {
        const tab = document.createElement('div');
        tab.className = 'tab' + (path === activeTab ? ' active' : '');
        tab.title = path;
        tab.addEventListener('click', () => activateTab(path));
        tab.addEventListener('auxclick', (e) => { if (e.button === 1) closeTab(path, e); });

        const icon = document.createElement('span');
        icon.textContent = KIND_ICON[kindOfPath(path)] || '📄';
        const label = document.createElement('span');
        const relInfo = releaseTabInfo.get(path);
        label.textContent = relInfo ? ('v' + relInfo.version + ' · ' + basename(path)) : basename(path);
        const dot = document.createElement('span');
        dot.className = 'dirty-dot' + (isModified(path) ? ' on' : '');
        dot.textContent = '●';
        const close = document.createElement('span');
        close.className = 'tab-close';
        close.textContent = '×';
        close.title = 'Chiudi';
        close.addEventListener('click', (e) => closeTab(path, e));

        tab.append(icon, label, dot, close);
        bar.appendChild(tab);
    }
}

// ====== PREVIEW (solo via popout) ======
function pickTemplate() {
    if (activeTab && kindOfPath(activeTab) === 'html' && !releaseTabInfo.has(activeTab)) return activeTab;
    if (activeTab && releaseTabInfo.has(activeTab)) return null; // release: niente preview/stampa dal workspace
    return lastActiveTemplate;
}

async function refreshPreview() {
    const rel = activeReleaseInfo();
    if (rel) { await refreshReleasePreview(rel); return; }
    flushActive();
    const template = pickTemplate();
    if (!template) { console.log('Nessun template aperto: anteprima non disponibile'); return; }

    const dirtyFiles = dirtyFilesMap();
    const info = {
        template,
        json: fileKinds.has(swapExt(template, '.json')),
        cssLinks: cssLinkCount(template),
        live: Object.keys(dirtyFiles).length > 0,
    };

    const seq = ++previewSeq;
    try {
        const resp = await fetch('/workspace/preview', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ workspace: workspacePath, template, dirtyFiles }),
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const html = await resp.text();
        if (seq !== previewSeq) { console.log('Preview scartata (fuori ordine)'); return; }
        const doc = buildPreviewDoc(html, info);
        lastPreviewDoc = doc;
        lastPreviewInfo = info;
        previewDirty = false;
        syncPopout(doc, popoutTitle(info));
    } catch (e) {
        console.error('Preview failed:', e);
    }
}

// Anteprima di una versione pubblicata: renderizzata dallo snapshot congelato (immagini data-URI)
async function refreshReleasePreview(rel) {
    const seq = ++previewSeq;
    try {
        const resp = await fetch('/release/preview?version=' + rel.version, {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ workspace: workspacePath, template: rel.document + '.html', dirtyFiles: {} }),
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const html = await resp.text();
        if (seq !== previewSeq) return;
        const info = { template: rel.document + '@v' + rel.version, json: true, cssLinks: null, live: false };
        const doc = buildPreviewDoc(html, info);
        lastPreviewDoc = doc;
        lastPreviewInfo = info;
        previewDirty = false;
        syncPopout(doc, popoutTitle(info));
    } catch (e) {
        console.error('Release preview failed:', e);
    }
}

// Conta i <link rel="stylesheet"> dichiarati nel template (da buffer se aperto, altrimenti dal disco via cache)
const cssLinkCache = new Map();
function cssLinkCount(template) {
    const content = openTabs.includes(template) ? buffers.get(template) : cssLinkCache.get(template);
    if (content == null) return null;
    return (content.match(/<link\b[^>]*stylesheet/gi) || []).length;
}

function popoutTitle(info) {
    let t = '👁️ ' + info.template;
    t += ' · ' + (info.json ? '📊 json ✓' : '📊 json —');
    if (info.cssLinks != null) t += ' · 🎨 css ×' + info.cssLinks;
    if (info.live) t += ' (live)';
    return t;
}

function injectBeforeLast(doc, tag, content) {
    const idx = doc.toLowerCase().lastIndexOf(tag);
    if (idx === -1) return null;
    return doc.slice(0, idx) + content + doc.slice(idx);
}

// Prepara il documento dell'anteprima: Paged.js + badge del trio renderizzato
function buildPreviewDoc(rawHtml, info) {
    const isDocument = /<\/head>/i.test(rawHtml) || /<\/body>/i.test(rawHtml);

    if (!isDocument) {
        // Messaggio di errore dal server (già HTML-escaped da Thymeleaf)
        return '<!DOCTYPE html><html><head><title>Errore anteprima</title></head>' +
            '<body style="font-family:monospace;padding:16px;background:#1e1e2e;color:#f38ba8;white-space:pre-wrap;">' +
            rawHtml + '</body></html>';
    }

    let doc = rawHtml;
    if (pagedEnabled) {
        // Dimensione pagina A4 di default (come OpenHTMLtoPDF) se il CSS non definisce @page
        const extraCss = [];
        if (!/@page\s*[{:]/i.test(doc)) extraCss.push('@page { size: A4; }');
        extraCss.push(
            'body { background: #525659; margin: 0; }' +
            '.pagedjs_pages { display: flex; flex-direction: column; align-items: center; padding: 12px; gap: 12px; }' +
            '.pagedjs_page { background: #fff; box-shadow: 0 2px 10px rgba(0,0,0,.4); margin: 0; }'
        );
        doc = injectBeforeLast(doc, '</head>', '<style>' + extraCss.join('\n') + '</style>\n') || doc;
        const pagedScript = '<scr' + 'ipt src="https://unpkg.com/pagedjs/dist/paged.polyfill.js"></scr' + 'ipt>';
        doc = injectBeforeLast(doc, '</body>', pagedScript + '\n') || doc;
    }
    if (info) {
        const parts = ['📄 ' + info.template];
        parts.push(info.json ? '📊 json ✓' : '📊 json —');
        if (info.cssLinks != null) parts.push('🎨 css ×' + info.cssLinks);
        const live = info.live ? ' · <span style="color:#f9e2af">● live</span>' : '';
        const badge = '<div style="position:fixed;bottom:10px;right:14px;background:rgba(24,24,37,.9);border:1px solid #45475a;border-radius:6px;padding:4px 10px;font:11px/1.4 monospace;color:#a6adc8;z-index:99999">'
            + parts.join(' · ') + live + '</div>';
        doc = injectBeforeLast(doc, '</body>', badge + '\n') || doc;
    }
    return doc;
}

function syncPopout(doc, title) {
    if (!popoutWin || popoutWin.closed) return;
    try {
        popoutWin.document.open();
        popoutWin.document.write(doc);
        popoutWin.document.close();
        if (title) popoutWin.document.title = title;
    } catch (e) { console.warn('syncPopout ritardata:', e.message); }
}

function togglePaged() {
    pagedEnabled = !pagedEnabled;
    const btn = document.getElementById('paged-toggle');
    btn.classList.toggle('on', pagedEnabled);
    btn.title = pagedEnabled
        ? 'Paginazione Paged.js attiva — clicca per disattivarla'
        : 'Paginazione disattivata — clicca per attivare Paged.js';
    if (lastPreviewDoc || (popoutWin && !popoutWin.closed)) refreshPreview();
}

function popoutPreview() {
    if (popoutWin && !popoutWin.closed) { popoutWin.focus(); }
    else {
        popoutWin = window.open('', 'pdforNotPdf_preview', 'width=920,height=1080,menubar=no,toolbar=no,location=no,status=no');
        if (!popoutWin) { alert('Il browser ha bloccato la finestra di anteprima. Consenti i popup per questo sito.'); return; }
    }
    if (lastPreviewDoc && !previewDirty && lastPreviewInfo) {
        syncPopout(lastPreviewDoc, popoutTitle(lastPreviewInfo));
    } else {
        refreshPreview();
    }
}

// ====== SALVATAGGIO ======
async function sha256Hex(text) {
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
    return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
}

async function saveActive(force = false) {
    flushActive();
    if (activeTab && (releaseTabInfo.has(activeTab) || imageTabs.has(activeTab))) {
        showStatus('🔒 ' + basename(activeTab) + ': sola lettura');
        return;
    }
    if (!activeTab) return;
    // P1: l'impronta del contenuto noto viaggia come hash (32 char) invece che come intero file
    const expectedSha256 = force ? null : await sha256Hex(savedContents.get(activeTab) ?? '');
    try {
        const resp = await fetch('/workspace/file', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                workspace: workspacePath, file: activeTab, content: buffers.get(activeTab),
                expectedSha256,
            }),
        });
        if (resp.status === 409) {
            if (!confirm('Il file è stato modificato su disco da un altro utente.\n\nSovrascrivere comunque con la tua versione?')) return;
            return saveActive(true);
        }
        if (!resp.ok) { alert('Salvataggio fallito:\n' + await resp.text()); return; }
        savedContents.set(activeTab, buffers.get(activeTab));
        renderTabs();
        renderTree();
        showStatus('✓ Salvato ' + basename(activeTab));
        if (popoutWin && !popoutWin.closed) refreshPreview();
    } catch (e) {
        alert('Salvataggio fallito: ' + e.message);
    }
}

let statusTimer;
function showStatus(msg) {
    const el = document.getElementById('status-msg');
    el.textContent = msg;
    el.classList.add('visible');
    clearTimeout(statusTimer);
    statusTimer = setTimeout(() => el.classList.remove('visible'), 2500);
}

// ====== STAMPA PDF ======
async function printPdf() {
    flushActive();
    const rel = activeReleaseInfo();
    if (rel && kindOfPath(activeTab) === 'html') { await printRelease(rel); return; }
    const template = pickTemplate();
    if (!template) { alert('Apri un template .html prima di stampare.'); return; }

    const dirtyFiles = dirtyFilesMap();

    showStatus('⏳ Generazione PDF…');
    try {
        const resp = await fetch('/workspace/print', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ workspace: workspacePath, template, dirtyFiles }),
        });
        if (!resp.ok) { alert(await resp.text()); return; }
        const blob = await resp.blob();
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = template.replace(/\.html$/i, '') + '.pdf';
        a.click();
        URL.revokeObjectURL(a.href);
        showStatus('✓ PDF generato');
    } catch (e) {
        alert('Generazione PDF fallita: ' + e.message);
    }
}

// Stampa PDF di una versione pubblicata: sempre e solo dallo snapshot congelato
async function printRelease(rel) {
    const templateRel = rel.path.split('/files/')[1];
    showStatus('⏳ Generazione PDF release…');
    try {
        const resp = await fetch('/release/print', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ workspace: workspacePath, document: rel.document, version: rel.version, template: templateRel }),
        });
        if (!resp.ok) { alert(await resp.text()); return; }
        const blob = await resp.blob();
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = templateRel.replace(/\.html$/i, '').replace(/\//g, '-') + '-v' + rel.version + '.pdf';
        a.click();
        URL.revokeObjectURL(a.href);
        showStatus('✓ PDF release generato');
    } catch (e) {
        alert('Generazione PDF release fallita: ' + e.message);
    }
}

// ===== PUBBLICAZIONI (bozza → candidata → approvata) =====
let versionsDoc = null;

function documentName(t) { return t.replace(/\.html$/i, ''); }
function toggleVersions() {
    closeAssistant();
    closeImport();
    const panel = document.getElementById('versions-panel');
    if (panel.style.display === 'flex') { panel.style.display = 'none'; return; }
    flushActive();
    const template = pickTemplate();
    if (!template) { alert('Apri un template .html per gestirne le versioni.'); return; }
    versionsDoc = documentName(template);
    document.getElementById('versions-doc').textContent = versionsDoc;
    panel.style.display = 'flex';
    refreshVersions();
}
function closeVersions() { document.getElementById('versions-panel').style.display = 'none'; }

function closeAssistant() { document.getElementById('assistant-panel').style.display = 'none'; }
function closeImport() { document.getElementById('import-panel').style.display = 'none'; }
function toggleImport() {
    closeAssistant();
    closeVersions();
    const panel = document.getElementById('import-panel');
    panel.style.display = panel.style.display === 'flex' ? 'none' : 'flex';
}

async function doImport() {
    const fileInput = document.getElementById('import-file');
    const file = fileInput.files[0];
    if (!file) { alert('Seleziona un file .docx o .pdf'); return; }
    const folder = document.getElementById('import-folder').value.trim();
    const isPdf = file.name.toLowerCase().endsWith('.pdf');
    const fd = new FormData();
    fd.append('workspace', workspacePath);
    fd.append('folder', folder);
    fd.append('file', file);
    const res = document.getElementById('import-result');
    res.innerHTML = '<div class="panel-hint">⏳ Importazione…</div>';
    try {
        let resp = await fetch(isPdf ? '/workspace/import-reference' : '/workspace/import-docx',
            { method: 'POST', credentials: 'same-origin', body: fd });
        // B3: re-import su percorso esistente → 409: chiedi conferma e riposta con overwrite=true
        if (resp.status === 409 && !isPdf) {
            if (!confirm('Esiste già un template con questo nome. Sovrascriverlo con il nuovo import?\n' +
                'Il contenuto su disco verrà sostituito.')) {
                res.innerHTML = '<div class="panel-hint">Import annullato</div>';
                return;
            }
            resp = await fetch('/workspace/import-docx?overwrite=true',
                { method: 'POST', credentials: 'same-origin', body: fd });
        }
        const text = await resp.text();
        if (!resp.ok) { res.innerHTML = '<div class="panel-hint" style="color:#f38ba8">' + text + '</div>'; return; }
        if (isPdf) {
            const data = JSON.parse(text);
            loadTree();
            res.innerHTML = '<div class="panel-hint">✓ Riferimento salvato: ' + data.path
                + ' — <a href="' + data.url + '" target="_blank" style="color:#89b4fa">apri</a></div>';
        } else {
            const data = JSON.parse(text);
            await loadTree();
            await openFile(data.template);
            closeImport();
            showStatus('✓ Importato ' + data.template);
        }
    } catch (e) {
        res.innerHTML = '<div class="panel-hint" style="color:#f38ba8">Errore: ' + e.message + '</div>';
    }
}

async function syncReleases() {
    const ok = await releasePost('/release/sync', { workspace: workspacePath }, '🔄 Sync completata');
    if (ok) { refreshVersions(); refreshVersionsIfOpen(); }
}

async function refreshCandidates() {
    try {
        const resp = await fetch('/release/candidates?workspace=' + encodeURIComponent(workspacePath), { credentials: 'same-origin' });
        const list = await resp.json();
        const host = document.getElementById('candidates-info');
        if (!list.length) { host.textContent = ''; return; }
        host.textContent = '🔗 in attesa di merge: ' + list.map(c => c.branch).join(', ');
    } catch (e) { /* non critico */ }
}

async function refreshVersions() {
    refreshCandidates();
    loadReleaseTree();
    const list = document.getElementById('versions-list');
    list.innerHTML = '<div class="panel-hint">Caricamento…</div>';
    try {
        const resp = await fetch('/release/list?workspace=' + encodeURIComponent(workspacePath)
            + '&document=' + encodeURIComponent(versionsDoc), { credentials: 'same-origin' });
        if (resp.status === 404) {
            list.innerHTML = '<div class="panel-hint">Documento mai pubblicato. Usa "📤 Pubblica candidate" per creare la prima versione.</div>';
            return;
        }
        const data = await resp.json();
        const host = document.getElementById('versions-list');
        host.innerHTML = '';
        for (const v of [...(data.versions || [])].sort((a, b) => b.version - a.version)) {
            const row = document.createElement('div');
            row.className = 'ver-row';
            const head = document.createElement('div');
            head.className = 'ver-head';
            const ver = document.createElement('b');
            ver.textContent = 'v' + v.version;
            const badge = document.createElement('span');
            badge.className = 'badge ' + v.status;
            badge.textContent = v.status;
            head.append(ver, badge);
            if (data.active === v.version) {
                const act = document.createElement('span');
                act.className = 'badge active';
                act.textContent = 'attiva ✓';
                head.append(act);
            }
            const when = document.createElement('span');
            when.style.cssText = 'margin-left:auto;color:#6c7086;font-size:11px;';
            when.textContent = (v.createdAt || '').replace('T', ' ').substring(0, 16);
            head.append(when);
            row.appendChild(head);
            if (v.note) {
                const note = document.createElement('div');
                note.className = 'ver-note';
                note.textContent = v.note;
                row.appendChild(note);
            }
            const actions = document.createElement('div');
            actions.className = 'ver-actions';
            const mk = (label, fn) => { const b = document.createElement('button'); b.textContent = label; b.addEventListener('click', fn); return b; };
            actions.append(mk('👁 Anteprima', () => previewVersion(v.version)));
            if (v.status === 'candidate') {
                actions.append(mk('✓ Approva', () => toggleApprove(row, v.version)));
                actions.append(mk('✕ Scarta', () => rejectVersion(v.version)));
            }
            if ((v.status === 'approved' || v.status === 'published') && data.active !== v.version) {
                actions.append(mk('⏪ Rollback', () => rollbackVersion(v.version)));
            }
            row.appendChild(actions);
            const ap = document.createElement('div');
            ap.className = 'ver-approve';
            ap.id = 'approve-' + v.version;
            ap.innerHTML = '<input type="date" data-role="efficacia" title="Data di efficacia (opzionale: per cambi di legge)" />';
            const conf = document.createElement('button');
            conf.textContent = 'Conferma approvazione';
            conf.addEventListener('click', () => approveVersion(v.version, ap.querySelector('input').value));
            ap.appendChild(conf);
            row.appendChild(ap);
            host.appendChild(row);
        }
    } catch (e) {
        list.innerHTML = '<div class="panel-hint">Errore caricamento versioni</div>';
        console.error(e);
    }
}

function toggleApprove(row, version) {
    const ap = row.querySelector('#approve-' + version);
    ap.classList.toggle('open');
}

async function releasePost(url, body, okMsg) {
    const resp = await fetch(url, { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!resp.ok) { alert((await resp.text()) || 'Operazione fallita'); return false; }
    showStatus(okMsg);
    return true;
}

async function saveDraft() {
    flushActive();
    const template = pickTemplate();
    if (!template) { alert('Apri un template .html prima di salvare la bozza.'); return; }
    const note = prompt('Nota per la bozza (opzionale):') ?? '';
    if (note === null) return;
    const ok = await releasePost('/release/draft',
        { workspace: workspacePath, template, dirtyFiles: dirtyFilesMap(), note },
        '✓ Bozza salvata');
    if (ok) refreshVersionsIfOpen();
}

async function publishCandidate() {
    const note = document.getElementById('publish-note').value;
    try {
        const resp = await fetch('/release/publish', { method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ workspace: workspacePath, template: versionsDoc, dirtyFiles: dirtyFilesMap(), note }) });
        if (!resp.ok) { alert(await resp.text()); return; }
        const data = await resp.json();
        document.getElementById('publish-note').value = '';
        refreshVersions();
        if (data.prUrl) {
            showStatus('✓ Candidate v' + data.version + ' — PR aperta sul remote');
            if (confirm('Candidate v' + data.version + ' pubblicata.\nAprire la Pull Request per la review?')) {
                window.open(data.prUrl, '_blank');
            }
        } else {
            showStatus('✓ Candidate v' + data.version + ' pubblicata');
        }
    } catch (e) { alert('Pubblicazione fallita: ' + e.message); }
}

async function approveVersion(version, effectiveFrom) {
    let approver = localStorage.getItem('approver') || '';
    approver = prompt('Nome approvatore:', approver); if (approver === null) return;
    localStorage.setItem('approver', approver);
    const ok = await releasePost('/release/approve',
        { workspace: workspacePath, document: versionsDoc, version, approver, effectiveFrom: effectiveFrom || null },
        '✓ v' + version + ' approvata' + (effectiveFrom ? ' (efficacia ' + effectiveFrom + ')' : ''));
    if (ok) refreshVersions();
}

async function rejectVersion(version) {
    if (!confirm('Scartare la v' + version + '?')) return;
    const ok = await releasePost('/release/reject',
        { workspace: workspacePath, document: versionsDoc, version, reason: '' }, '✓ Candidate scartata');
    if (ok) refreshVersions();
}

async function rollbackVersion(version) {
    if (!confirm("Riattivare la v" + version + " come versione attiva?")) return;
    const ok = await releasePost('/release/rollback',
        { workspace: workspacePath, document: versionsDoc, version, author: localStorage.getItem('approver') || 'workbench' },
        '✓ Rollback alla v' + version);
    if (ok) refreshVersions();
}

async function previewVersion(version) {
    const resp = await fetch('/release/preview?version=' + version, { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workspace: workspacePath, template: versionsDoc + '.html', dirtyFiles: {} }) });
    const html = await resp.text();
    const w = window.open('', '_blank');
    w.document.open();
    w.document.write(buildPreviewDoc(html, { template: versionsDoc + '@v' + version, json: true, cssLinks: null, live: false }));
    w.document.close();
    w.document.title = '👁️ ' + versionsDoc + ' v' + version;
}

function refreshVersionsIfOpen() {
    if (document.getElementById('versions-panel').style.display === 'flex') refreshVersions();
}

// ===== ASSISTENTE LLM (propone, l'utente applica) =====
let assistantDoc = null;
let assistProposals = null;

function toggleAssistant() {
    closeImport();
    closeVersions();
    flushActive();
    const template = pickTemplate();
    if (!template) { alert('Apri un template .html per usare l\'assistente.'); return; }
    assistantDoc = documentName(template);
    document.getElementById('assist-doc').textContent = assistantDoc;
    document.getElementById('assistant-panel').style.display = 'flex';
    loadLlmSettingsInputs();
    const res = document.getElementById('assist-result');
    res.innerHTML = '';
    updateAssistStatus();
}
async function askAssistant() {
    const instruction = document.getElementById('assist-instruction').value.trim();
    if (!instruction) return;
    flushActive();
    const res = document.getElementById('assist-result');
    res.innerHTML = '<div class="panel-hint">🤔 L\'assistente sta lavorando…</div>';
    try {
        const resp = await fetch('/llm/assist', { method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ workspace: workspacePath, document: assistantDoc + '.html',
                instruction, dirtyFiles: dirtyFilesMap(), llm: getLlmConfig() }) });
        const text = await resp.text();
        if (!resp.ok) { res.innerHTML = '<div class="panel-hint" style="color:#f38ba8">' + text + '</div>'; return; }
        const data = JSON.parse(text);
        assistProposals = data.modifiche;
        res.innerHTML = '';
        const sp = document.createElement('div');
        sp.className = 'assist-spiegazione';
        sp.textContent = data.spiegazione || 'Modifica proposta';
        res.appendChild(sp);
        for (const p of assistProposals) {
            const wrap = document.createElement('div');
            wrap.className = 'assist-file';
            const path = document.createElement('div');
            path.className = 'path';
            path.textContent = '📝 ' + p.path;
            let old = null;
            if (openTabs.includes(p.path)) old = buffers.get(p.path);
            else {
                try {
                    old = await (await fetch('/workspace/file?workspace=' + encodeURIComponent(workspacePath)
                        + '&file=' + encodeURIComponent(p.path), { credentials: 'same-origin' })).text();
                } catch (e) { old = ''; }
            }
            const diffEl = buildDiffEl(old != null ? old : '', p.contenuto);
            const apply = document.createElement('div');
            apply.className = 'assist-apply';
            const btn = document.createElement('button');
            btn.textContent = '✓ Applica al buffer';
            btn.addEventListener('click', () => applyEdit(p.path, p.contenuto));
            apply.appendChild(btn);
            wrap.append(path, diffEl, apply);
            res.appendChild(wrap);
        }
    } catch (e) {
        res.innerHTML = '<div class="panel-hint" style="color:#f38ba8">Errore: ' + e.message + '</div>';
    }
}

function buildDiffEl(oldText, newText) {
    const el = document.createElement('div');
    el.className = 'diff';
    for (const row of diffLines(oldText, newText)) {
        const line = document.createElement('div');
        line.className = row.t === '+' ? 'd-add' : row.t === '-' ? 'd-del' : 'd-eq';
        line.textContent = (row.t === '+' ? '+ ' : row.t === '-' ? '- ' : '  ') + row.x;
        el.appendChild(line);
    }
    return el;
}

function diffLines(a, b) {
    const A = a.split('\n'), B = b.split('\n');
    const n = A.length, m = B.length;
    const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
    for (let i = n - 1; i >= 0; i--) {
        for (let j = m - 1; j >= 0; j--) {
            dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        }
    }
    const rows = [];
    let i = 0, j = 0;
    while (i < n && j < m) {
        if (A[i] === B[j]) { rows.push({ t: '=', x: A[i] }); i++; j++; }
        else if (dp[i + 1][j] >= dp[i][j + 1]) { rows.push({ t: '-', x: A[i] }); i++; }
        else { rows.push({ t: '+', x: B[j] }); j++; }
    }
    while (i < n) rows.push({ t: '-', x: A[i++] });
    while (j < m) rows.push({ t: '+', x: B[j++] });
    return rows;
}

function getLlmConfig() {
    try {
        const s = JSON.parse(localStorage.getItem('llm-settings') || '{}');
        return (s.baseUrl && s.model) ? { baseUrl: s.baseUrl, apiKey: s.apiKey || '', model: s.model } : null;
    } catch (e) { return null; }
}
function saveLlmSettings() {
    const s = {
        baseUrl: document.getElementById('llm-baseurl').value.trim(),
        apiKey: document.getElementById('llm-apikey').value,
        model: document.getElementById('llm-model').value.trim(),
    };
    localStorage.setItem('llm-settings', JSON.stringify(s));
    updateAssistStatus();
    showStatus('✓ Credenziali salvate in questo browser');
}
function loadLlmSettingsInputs() {
    try {
        const s = JSON.parse(localStorage.getItem('llm-settings') || '{}');
        document.getElementById('llm-baseurl').value = s.baseUrl || '';
        document.getElementById('llm-apikey').value = s.apiKey || '';
        document.getElementById('llm-model').value = s.model || '';
    } catch (e) { }
}
function updateAssistStatus() {
    const cfg = getLlmConfig();
    const el = document.getElementById('assist-status');
    if (cfg) { el.textContent = '🤖 Modello utente: ' + cfg.model; el.style.color = '#a6e3a1'; return; }
    fetch('/llm/status', { credentials: 'same-origin' }).then(r => r.json()).then(s => {
        el.textContent = s.enabled ? '🤖 Modello: ' + s.model + ' (config. applicazione)'
            : '⚠️ Nessun servizio AI configurato (usa i campi sopra)';
        el.style.color = s.enabled ? '#a6e3a1' : '#f9e2af';
    }).catch(() => {});
}

async function applyEdit(path, content) {
    await openFile(path);
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: content } });
    showStatus('✓ Proposta applicata al buffer — salva quando vuoi');
}

// ====== AUTO-PREVIEW (debounce mentre l'anteprima è aperta) ======
let previewTimer;
let treeTimer;
function autoPreview() {
    previewDirty = true;
    clearTimeout(treeTimer);
    treeTimer = setTimeout(renderTree, 400); // aggiorna i pallini dirty nell'Explorer
    if (!popoutWin || popoutWin.closed) return;
    clearTimeout(previewTimer);
    previewTimer = setTimeout(refreshPreview, 500);
}
window.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); saveActive(); }
});
window.addEventListener('beforeunload', (e) => {
    flushActive();
    if (openTabs.some(isModified)) { e.preventDefault(); e.returnValue = ''; }
});

// ====== SIDEBAR RESIZE ======
const explorerEl = document.getElementById('explorer');
const sbHandle = document.getElementById('sidebar-handle');
let draggingSb = false;
sbHandle.addEventListener('mousedown', e => {
    draggingSb = true;
    sbHandle.classList.add('active');
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    e.preventDefault();
});
document.addEventListener('mousemove', e => {
    if (!draggingSb) return;
    explorerEl.style.width = Math.max(160, Math.min(420, e.clientX)) + 'px';
});
document.addEventListener('mouseup', () => {
    if (!draggingSb) return;
    draggingSb = false;
    sbHandle.classList.remove('active');
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
});

// ====== ASSISTENTE AI: colonna dock ridimensionabile (come l'explorer) ======
const assistPanel = document.getElementById('assistant-panel');
const assistHandle = document.getElementById('assistant-handle');
let draggingAssist = false;
assistHandle.addEventListener('mousedown', e => {
    draggingAssist = true;
    assistHandle.classList.add('active');
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    e.preventDefault();
});
document.addEventListener('mousemove', e => {
    if (!draggingAssist) return;
    // bordo sinistro del pannello = posizione del mouse; si lasciano ≥360px all'editor+explorer
    const w = Math.max(320, Math.min(window.innerWidth - 360, window.innerWidth - e.clientX));
    assistPanel.style.width = w + 'px';
    localStorage.setItem('assist-width', String(w));
});
document.addEventListener('mouseup', () => {
    if (!draggingAssist) return;
    draggingAssist = false;
    assistHandle.classList.remove('active');
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
});
// larghezza ripristinata tra le sessioni
const savedAssistWidth = localStorage.getItem('assist-width');
if (savedAssistWidth) assistPanel.style.width = savedAssistWidth + 'px';

// ====== INIT ======
document.getElementById('btn-new-file').addEventListener('click', () => startCreate('file'));
document.getElementById('btn-new-dir').addEventListener('click', () => startCreate('dir'));
document.getElementById('btn-release-refresh').addEventListener('click', loadReleaseTree);
document.getElementById('btn-new-image').addEventListener('click', () => document.getElementById('image-file-input').click());
document.getElementById('image-file-input').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    if (!file) return;
    let folder = '';
    if (selectedPath) folder = selectedIsDir ? selectedPath : parentDirOf(selectedPath);
    if (!folder) folder = 'assets';
    const fd = new FormData();
    fd.append('workspace', workspacePath);
    fd.append('folder', folder);
    fd.append('file', file);
    try {
        // B5: gli SVG compaiono nell'anteprima ma OpenHTMLtoPDF non li include nel PDF stampato
        if (file.name.toLowerCase().endsWith('.svg')
            && !confirm('⚠️ Gli SVG sono visualizzabili nell\'anteprima ma NON vengono inclusi nel PDF stampato.\n' +
                'Per una stampa completa usare PNG/JPG. Caricare comunque?')) return;
        let resp = await fetch('/workspace/upload-image', { method: 'POST', credentials: 'same-origin', body: fd });
        if (resp.status === 409) {
            if (!confirm('Esiste già un\'immagine con questo nome. Sovrascriverla?\n' +
                'I template che la usano punteranno al nuovo file automaticamente.')) return;
            resp = await fetch('/workspace/upload-image?replace=true', { method: 'POST', credentials: 'same-origin', body: fd });
        }
        const text = await resp.text();
        if (!resp.ok) { alert('Caricamento fallito:\n' + text); return; }
        const data = JSON.parse(text);
        await loadTree();
        // se il tab immagine era già aperto, forza il ricaricamento (cache-bust)
        const src = assetUrl(data.path) + (imageTabs.has(data.path) ? '&t=' + Date.now() : '');
        imageTabs.set(data.path, src);
        openImageTab(data.path, src);
        showStatus((src.includes('&t=') ? '✓ Immagine sostituita: ' : '✓ Immagine caricata: ') + data.path);
    } catch (err) {
        alert('Caricamento fallito: ' + err.message);
    }
});
if (initEditor()) { loadTree(); loadReleaseTree(); }


// ===== UI API (R1): gli handler onclick del markup restano globali, tutto il resto vive nel bundle =====
Object.assign(window, {
    askAssistant, closeAssistant, closeDeleteDialog, closeImport, closeRenameDialog,
    closeVersions, confirmDelete, confirmRenameDialog, doImport, popoutPreview,
    printPdf, publishCandidate, saveActive, saveDraft, saveLlmSettings, syncReleases,
    toggleAssistant, toggleImport, togglePaged, toggleVersions,
    // usate direttamente dagli e2e (page.evaluate)
    loadReleaseTree, refreshVersions,
});
// stato interno osservabile dagli e2e: getter live (le variabili sono let del bundle)
Object.defineProperty(window, 'view', { get: () => view });
Object.defineProperty(window, 'activeTab', { get: () => activeTab });
window.releaseTabInfo = releaseTabInfo;
