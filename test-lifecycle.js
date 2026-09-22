/**
 * E2E M1: ciclo di vita con Gitea — bozza → candidata → PR → merge (approvazione) → sync → API.
 * Richiede: app su :8080 con remote Gitea configurato + Gitea su :3000 (docker/gitea).
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const fs = require('fs');

const WS = '/home/ocuda/workspace/stampe-demo';
const GITEA_API = 'http://localhost:3000/api/v1';
const REPO = 'pdforCurrent/stampe-releases';
// credenziali: da ambiente oppure da docker/gitea/.credentials (creato dal setup, gitignored)
let GITEA_PASSWORD = process.env.GITEA_PASSWORD || '';
const credFile = __dirname + '/docker/gitea/.credentials';
if (!GITEA_PASSWORD && fs.existsSync(credFile)) {
  for (const line of fs.readFileSync(credFile, 'utf8').split('\n')) {
    const m = line.match(/^GITEA_PASSWORD=(.*)$/);
    if (m) GITEA_PASSWORD = m[1].trim();
  }
}
if (!GITEA_PASSWORD) {
  console.error('❌ GITEA_PASSWORD non trovata: esegui docker/gitea/setup-gitea.sh oppure export GITEA_PASSWORD=…');
  process.exit(1);
}
const GITEA_AUTH = 'pdforCurrent:' + GITEA_PASSWORD;

execSync(`python3 ${__dirname}/tools/generate-demo-workspace.py ${WS}`);
const sleepMs = (ms) => { execSync(`sleep ${Math.max(1, Math.round(ms / 1000))}`); };

// reset del repo releases su Gitea (il test pubblica da uno stato pulito)
execSync(`curl -s -u ${GITEA_AUTH} -X DELETE "${GITEA_API}/repos/${REPO}" -o /dev/null`);
execSync(`curl -s -u ${GITEA_AUTH} -X POST "${GITEA_API}/user/repos" -H "Content-Type: application/json" `
  + `-d '{"name":"stampe-releases","auto_init":true,"default_branch":"main","private":true}' -o /dev/null`);

let failures = 0;
function check(name, ok) { console.log((ok ? '✅' : '❌') + ' ' + name); if (!ok) failures++; }

function mergeOpenPulls() {
  const pulls = JSON.parse(execSync(
    `curl -s -u ${GITEA_AUTH} "${GITEA_API}/repos/${REPO}/pulls?state=open"`).toString());
  for (const p of pulls) {
    let merged = false;
    for (let attempt = 1; attempt <= 6 && !merged; attempt++) {
      const out = execSync(`curl -s -w "\\n%{http_code}" -u ${GITEA_AUTH} -X POST `
        + `"${GITEA_API}/repos/${REPO}/pulls/${p.number}/merge" `
        + `-H "Content-Type: application/json" -d '{"Do":"merge"}'`).toString();
      const code = out.split('\n').pop();
      if (code === '200') {
        merged = true;
        console.log('  ↳ merged PR #' + p.number);
      } else if (code === '405') {
        console.log('  ↳ PR non ancora mergeable, retry ' + attempt);
        sleepMs(1500);
      } else {
        throw new Error('Merge PR #' + p.number + ' fallito (HTTP ' + code + ')');
      }
    }
    if (!merged) throw new Error('Merge PR #' + p.number + ' fallito dopo 6 tentativi');
  }
  return pulls.length;
}

function syncApp() {
  const out = execSync(`curl -s -X POST http://localhost:8080/release/sync -H "Content-Type: application/json" `
    + `-d '{"workspace":"${WS}"}'`).toString();
  console.log('  ↳ sync:', out);
}

// attende che la sync porti la versione attesa (max 10s)
async function waitForVersion(page, document, version) {
  await page.waitForFunction(({ ws, doc, ver }) => {
    return fetch('/release/list?workspace=' + encodeURIComponent(ws) + '&document=' + doc,
      { credentials: 'same-origin' })
      .then(r => r.status === 404 ? false : r.json())
      .then(d => d && d.active === ver)
      .catch(() => false);
  }, { ws, doc: document, ver: version }, { timeout: 10000 });
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ acceptDownloads: true });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error' && !/404|409/.test(m.text())) errors.push('console: ' + m.text()); });
  page.on('dialog', async d => { await d.accept(); });

  await page.goto('http://localhost:8080/');
  await page.fill('input[name="path"]', WS);
  await page.click('button[type="submit"]');
  await page.waitForURL('**/workspace?ws=**');
  await page.waitForSelector('.tree-file');

  const treeText = await page.locator('#explorer-tree').innerText();
  check('Explorer: releases/ nascosta', !treeText.includes('releases'));

  // ===== pubblica candidata v1 (con marker visibile nel PDF) =====
  await page.locator('.tree-file[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForTimeout(300);
  await page.locator('.cm-content').click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type('\n<p>MARKER-V1</p>');

  await page.click('button[onclick="toggleVersions()"]');
  await page.waitForSelector('#versions-panel', { state: 'visible' });
  await page.waitForTimeout(800);
  check('Pannello: hint mai pubblicato', (await page.locator('#versions-list').innerText()).includes('mai pubblicato'));

  await page.fill('#publish-note', 'prima release');
  await page.click('.versions-publish button');
  await page.waitForTimeout(2000);

  // ===== approvazione = merge della PR su Gitea =====
  const merged = mergeOpenPulls();
  check('Gitea: PR merged (' + merged + ')', merged >= 1);
  syncApp();

  // refresh pannello: chiudi + riapri
  await page.click('#versions-panel .versions-head button'); // chiudi
  await page.click('button[onclick="toggleVersions()"]'); // riapri (refresh)
  await page.waitForTimeout(1000);
  const dbg = await page.evaluate(async (ws) => {
    const r = await fetch('/release/list?workspace=' + encodeURIComponent(ws) + '&document=CLIENTE_A/fattura', { credentials: 'same-origin' });
    return { status: r.status, body: (await r.text()).substring(0, 150) };
  }, WS);
  console.log('  ↳ DBG list:', JSON.stringify(dbg));
  const list1 = (await page.locator('#versions-list').innerText()).toLowerCase();
  check('Dopo merge: v1 pubblicata', list1.includes('v1'));
  check('Dopo merge: badge published', list1.includes('published'));
  check('Dopo merge: attiva ✓', list1.includes('attiva'));

  // ===== API serve lo snapshot pubblicato =====
  await page.waitForTimeout(200);
  const api1 = await page.evaluate(async (ws) => {
    const r = await fetch('/api/generate?workspace=' + encodeURIComponent(ws)
      + '&template=CLIENTE_A/fattura.html', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ titolo: 'T', cliente: 'C', data: 'D', righe: [], totale: '0' }),
    });
    const buf = new Uint8Array(await r.arrayBuffer());
    let bin = '';
    buf.forEach(b => bin += String.fromCharCode(b));
    return { status: r.status, b64: btoa(bin) };
  }, WS);
  fs.writeFileSync('/tmp/m1-v1.pdf', Buffer.from(api1.b64, 'base64'));
  check('API: 200 PDF', api1.status === 200);
  const t1 = execSync('pdftotext /tmp/m1-v1.pdf - 2>/dev/null || true').toString();
  check('API: MARKER-V1 nello snapshot pubblicato', t1.includes('MARKER-V1'));

  // ===== Explorer Release: sola lettura (visualizza + stampa) =====
  await page.evaluate(() => closeVersions()); // libera la toolbar (il pannello copre il pulsante Stampa)
  await page.evaluate(() => loadReleaseTree());
  await page.waitForTimeout(600);
  const relText = await page.locator('#release-tree').innerText();
  check('Explorer Release: documento e versione visibili', relText.includes('CLIENTE_A/fattura') && /v1/i.test(relText));
  check('Explorer Release: badge attiva', relText.toLowerCase().includes('attiva'));

  check('Explorer Release: dipendenze congelate raggruppate',
      (await page.locator('#release-tree').innerText()).includes('dipendenze congelate'));
  // il primo file per ordine è l'immagine del logo: viewer immagine (sola visualizzazione)
  await page.locator('#release-tree .rel-file[title*="logo-cliente-a.png"]').first().click();
  await page.waitForTimeout(500);
  check('Release: immagine visualizzabile', await page.locator('#image-host img').count() === 1);
  await page.locator('.tab.active .tab-close').click();
  await page.waitForTimeout(200);

  // template della release in tab read-only
  await page.locator('#release-tree .rel-file[title*="files/CLIENTE_A/fattura.html"]').first().click();
  await page.waitForTimeout(500);
  const relState = await page.evaluate(() => ({
    isRelease: !!(activeTab && releaseTabInfo.has(activeTab)),
    content: view ? view.state.doc.toString() : '',
  }));
  check('Release: tab aperto in sola lettura', relState.isRelease);
  check('Release: contenuto dello snapshot nel viewer', relState.content.includes('MARKER-V1'));

  // stampa dal tab release (snapshot congelato)
  const [dl] = await Promise.all([
    page.waitForEvent('download'),
    page.click('button[onclick="printPdf()"]'),
  ]);
  await dl.saveAs('/tmp/m1-release-print.pdf');
  const tRel = execSync('pdftotext /tmp/m1-release-print.pdf - 2>/dev/null || true').toString();
  check('Release: stampa dalla snapshot congelata', tRel.includes('MARKER-V1'));

  // torna al tab di lavoro per continuare il ciclo di vita
  await page.locator('#explorer-tree .tree-file[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForTimeout(300);
  check('Ritorno al tab snapshot di lavoro', await page.evaluate(() => activeTab === 'CLIENTE_A/fattura.html'));

  // ===== v2: pubblicazione + merge =====
  await page.locator('.cm-content').click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type('\n<p>MARKER-V2</p>');
  await page.evaluate(() => toggleVersions()); // riapri il pannello chiuso per la stampa release
  await page.waitForSelector('#versions-panel', { state: 'visible' });
  await page.waitForTimeout(500);
  await page.fill('#publish-note', 'seconda release');
  await page.click('.versions-publish button');
  await page.waitForTimeout(2000);
  mergeOpenPulls();
  syncApp();
  await page.waitForTimeout(600);
  await page.evaluate(() => refreshVersions());
  await page.waitForTimeout(400);
  const listJson2 = await page.evaluate(async (ws) => await (await fetch(
    '/release/list?workspace=' + encodeURIComponent(ws) + '&document=CLIENTE_A/fattura',
    { credentials: 'same-origin' })).text(), WS);
  console.log('  ↳ list v2:', listJson2.substring(0, 300));

  const api2 = await page.evaluate(async (ws) => {
    const r = await fetch('/api/generate?workspace=' + encodeURIComponent(ws)
      + '&template=CLIENTE_A/fattura.html', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ titolo: 'T2', cliente: 'C', data: 'D', righe: [], totale: '0' }),
    });
    const buf = new Uint8Array(await r.arrayBuffer());
    let bin = '';
    buf.forEach(b => bin += String.fromCharCode(b));
    return btoa(bin);
  }, WS);
  fs.writeFileSync('/tmp/m1-v2.pdf', Buffer.from(api2, 'base64'));
  const t2 = execSync('pdftotext /tmp/m1-v2.pdf - 2>/dev/null || true').toString();
  check('v2: MARKER-V1 e MARKER-V2 entrambi (snapshot cumulativo)', t2.includes('MARKER-V1') && t2.includes('MARKER-V2'));

  // ===== rollback alla v1 =====
  await page.evaluate(() => refreshVersions());
  await page.waitForTimeout(500);
  const v1row = page.locator('.ver-row', { hasText: 'v1' }).first();
  await v1row.locator('.ver-actions button', { hasText: '⏪ Rollback' }).click();
  await page.waitForTimeout(1500);
  syncApp();
  await page.waitForTimeout(500);
  const api3 = await page.evaluate(async (ws) => {
    const r = await fetch('/api/generate?workspace=' + encodeURIComponent(ws)
      + '&template=CLIENTE_A/fattura.html', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ titolo: 'T3', cliente: 'C', data: 'D', righe: [], totale: '0' }),
    });
    const buf = new Uint8Array(await r.arrayBuffer());
    let bin = '';
    buf.forEach(b => bin += String.fromCharCode(b));
    return btoa(bin);
  }, WS);
  fs.writeFileSync('/tmp/m1-v1b.pdf', Buffer.from(api3, 'base64'));
  const t3 = execSync('pdftotext /tmp/m1-v1b.pdf - 2>/dev/null || true').toString();
  check('Rollback: MARKER-V2 assente', !t3.includes('MARKER-V2'));
  check('Rollback: MARKER-V1 presente', t3.includes('MARKER-V1'));

  const realErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::') && !/status of (404|409)/.test(e));
  if (realErrors.length) { console.log('\n⚠️ Errori JS:'); realErrors.forEach(e => console.log('  ' + e)); }
  else console.log('\n✅ Nessun errore JS');

  await browser.close();
  process.exit(failures || realErrors.length ? 1 : 0);
})().catch(e => { console.error('❌ TEST FALLITO:', e.message); process.exit(1); });
