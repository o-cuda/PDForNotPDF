/**
 * Helper condivisi per le suite e2e (E-Proposte/04 / R9).
 * Prima ogni file ridefiniva check/rightClick/wiring browser: ora la piattaforma è unica,
 * così una correzione (es. il settle del right-click) beneficia tutte le suite.
 *
 * Esportati:
 *  - makeCheck(): { check, state } — stampa ✅/❌ e conta i fallimenti
 *  - rightClick(page, locator): right-click robusto (scroll in vista + settle)
 *  - launch({ acceptDownloads, acceptDialogs, consoleFilter }): browser+page con raccolta errori
 *  - setEditorContent(page, text): sostituisce il contenuto del tab attivo (CM6)
 */
const { chromium } = require('playwright');

function makeCheck() {
    const state = { failures: 0 };
    const check = (name, ok) => {
        console.log((ok ? '✅ ' : '❌ ') + name);
        if (!ok) state.failures++;
    };
    return { check, state };
}

/** Right-click robusto: scrolla il nodo in vista e attende il flush dello scroll, così
 *  l'apertura del menu non viene chiusa dallo scroll pre-click (nato dal debug flaky). */
async function rightClick(page, locator) {
    await page.waitForTimeout(300); // lascia finire eventuali re-render debounced
    for (let i = 0; i < 3; i++) {
        try { await locator.scrollIntoViewIfNeeded({ timeout: 5000 }); break; }
        catch (e) { await page.waitForTimeout(500); }
    }
    await page.waitForTimeout(150);
    await locator.click({ button: 'right' });
}

/** Browser + pagina con raccolta errori console/pageerror e (di default) auto-accettazione dialog.
 *  consoleFilter: RegExp degli errori DA ESCLUDERE (es. /404|409/ per gli attesi). */
async function launch({ acceptDownloads = false, acceptDialogs = true, consoleFilter = null } = {}) {
    const browser = await chromium.launch();
    const context = await browser.newContext({ acceptDownloads });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push('pageerror: ' + e.message));
    page.on('console', m => {
        if (m.type() !== 'error') return;
        const text = 'console: ' + m.text();
        if (consoleFilter && consoleFilter.test(text)) return;
        errors.push(text);
    });
    if (acceptDialogs) page.on('dialog', async d => { await d.accept(); });
    return { browser, context, page, errors };
}

/** Sostituisce tutto il contenuto del tab attivo nell'editor CodeMirror 6. */
async function setEditorContent(page, text) {
    await page.evaluate((t) => view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: t } }), text);
}

module.exports = { makeCheck, rightClick, launch, setEditorContent };
