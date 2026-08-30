/**
 * E2E: CodeMirror 6 (highlighting, lint), salvataggio/dirty e overlay live multi-file
 * (template + CSS linkati + JSON) su workspace multi-tenant.
 * Uso: node test-phase234.js   (app avviata su :8080)
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');

const WS = '/home/ocuda/workspace/PDForNotPDF/test-workspace';
let failures = 0;
function check(name, ok) { console.log((ok ? '✅' : '❌') + ' ' + name); if (!ok) failures++; }
async function setEditorContent(page, text) {
  await page.evaluate((t) => view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: t } }), text);
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ acceptDownloads: true });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  // ===== Setup =====
  await page.goto('http://localhost:8080/');
  await page.fill('input[name="path"]', WS);
  await page.click('button[type="submit"]');
  await page.waitForURL('**/workspace?ws=**');
  await page.waitForSelector('.tree-file');
  await page.locator('.tree-file[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForTimeout(300);
  await page.locator('.tree-file[title="CLIENTE_A/fattura.json"]').click();
  await page.waitForTimeout(300);

  // ===== FASE 2: CodeMirror 6 =====
  check('F2: editor CM6 attivo', await page.locator('.cm-editor').count() === 1);
  check('F2: numeri di riga', await page.locator('.cm-lineNumbers').count() === 1);
  await page.locator('.tab[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForTimeout(200);
  const spanCount = await page.locator('.cm-content span').count();
  check('F2: syntax highlighting HTML (' + spanCount + ' span)', spanCount > 10);

  // JSON lint
  await page.locator('.tab[title="CLIENTE_A/fattura.json"]').click();
  await setEditorContent(page, 'non json ---');
  await page.waitForTimeout(3000);
  check('F2: lint JSON segnala errore di sintassi',
        await page.locator('[class*="cm-lintPoint-error"], [class*="cm-lintRange-error"]').count() > 0);
  await setEditorContent(page, JSON.stringify({
    titolo: 'Fattura CM6', cliente: 'Mario Rossi Spa', data: '14/07/2026',
    righe: [{ descrizione: 'Consulenza', quantita: 3, prezzo: '€450.00' }], totale: '1,350.00'
  }, null, 2));
  await page.waitForTimeout(3000);
  check('F2: lint JSON OK dopo correzione',
        await page.locator('[class*="cm-lintPoint-error"], [class*="cm-lintRange-error"]').count() === 0);

  // Switch di tab preserva le modifiche (apro anche il CSS del cliente)
  await page.locator('.tree-file[title="CLIENTE_A/include/common.css"]').click();
  await page.waitForTimeout(250);
  await page.locator('.tab[title="CLIENTE_A/fattura.html"]').click();
  await page.locator('.cm-content').click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type('\n<!-- edit live -->');
  await page.locator('.tab[title="CLIENTE_A/include/common.css"]').click();
  await page.waitForTimeout(200);
  await page.locator('.tab[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForTimeout(200);
  const htmlDoc = await page.evaluate(() => view.state.doc.toString());
  check('F2: switch di tab preserva le modifiche', htmlDoc.includes('<!-- edit live -->'));

  // ===== FASE 3: dirty dot + salvataggio =====
  check('F3: pallino dirty sul tab',
        await page.locator('.tab[title="CLIENTE_A/fattura.html"]').locator('.dirty-dot.on').count() === 1);
  await page.locator('.cm-content').click();
  await page.keyboard.press('Control+s');
  await page.waitForTimeout(600);
  check('F3: pallino rimosso dopo Ctrl+S',
        await page.locator('.tab[title="CLIENTE_A/fattura.html"]').locator('.dirty-dot.on').count() === 0);
  const disk = await page.evaluate(async (ws) =>
    await (await fetch('/workspace/file?workspace=' + encodeURIComponent(ws) + '&file=CLIENTE_A/fattura.html')).text(), WS);
  check('F3: file salvato su disco', disk.includes('<!-- edit live -->'));

  // Chiusura tab dirty → conferma
  await page.evaluate(() => view.dispatch({ changes: { from: view.state.doc.length, insert: 'X' } }));
  await page.waitForTimeout(200);
  let dialogSeen = null;
  page.once('dialog', async d => { dialogSeen = d.message(); await d.accept(); });
  await page.locator('.tab[title="CLIENTE_A/fattura.html"]').locator('.tab-close').click();
  await page.waitForTimeout(400);
  check('F3: conferma su chiusura tab dirty', dialogSeen !== null && dialogSeen.includes('modifiche non salvate'));
  check('F3: tab chiuso dopo conferma', await page.locator('.tab[title="CLIENTE_A/fattura.html"]').count() === 0);

  // ===== FASE 4: overlay multi-file (template + CSS linkato + JSON) =====
  await page.locator('.tree-file[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForTimeout(300);
  const [popup] = await Promise.all([
    page.waitForEvent('popup', { timeout: 5000 }),
    page.click('button[onclick="popoutPreview()"]'),
  ]);
  await popup.waitForLoadState('domcontentloaded');
  await popup.locator('.pagedjs_page').first().waitFor({ timeout: 15000 });
  const base = await popup.locator('body').innerText();
  check('F4: popout con json live da overlay (CM6) + header cliente', base.includes('Fattura CM6') && base.toLowerCase().includes('cliente_a srl'));

  // JSON e CSS linkato dirty contemporaneamente → preview live
  await page.locator('.tab[title="CLIENTE_A/fattura.json"]').click();
  await setEditorContent(page, JSON.stringify({
    titolo: 'Fattura OVERLAY', cliente: 'Mario Rossi Spa', data: '14/07/2026',
    righe: [{ descrizione: 'Consulenza overlay', quantita: 1, prezzo: '€999.00' }], totale: '999.00'
  }, null, 2));
  await page.locator('.tab[title="CLIENTE_A/include/common.css"]').click();
  await setEditorContent(page, 'h1 { color: #dc2626; }\nh2 { color: #123456; }');
  await page.locator('.tab[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForTimeout(2200);
  const live = await popup.locator('body').innerText();
  const liveHtml = await popup.evaluate(() => document.documentElement.innerHTML);
  check('F4: JSON dirty renderizzato live (titolo OVERLAY)', live.includes('Fattura OVERLAY'));
  check('F4: CSS linkato dirty inline-ato live (valore #123456)', liveHtml.includes('#123456'));
  check('F4: immagini via endpoint asset', await popup.locator('img[src*="/workspace/asset"]').count() >= 2);
  check('F4: badge "live"', (await popup.locator('div[style*="position:fixed"]').first().innerText()).includes('● live'));

  // Stampa con tutto dirty: PDF con immagine + contenuto live
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 15000 }),
    page.click('button[onclick="printPdf()"]'),
  ]);
  await download.saveAs('/tmp/overlay-multi.pdf');
  const pdfBytes = require('fs').readFileSync('/tmp/overlay-multi.pdf');
  check('F4: PDF contiene le immagini (/Image)', pdfBytes.includes('/Image'));
  const pdfText = execSync('pdftotext /tmp/overlay-multi.pdf - 2>/dev/null || true').toString();
  check('F4: PDF con JSON live (OVERLAY)', pdfText.includes('Fattura OVERLAY'));
  check('F4: PDF con header cliente', pdfText.toUpperCase().includes('CLIENTE_A SRL'));

  // Salvataggio di tutto → badge live rimosso
  await page.locator('.tab[title="CLIENTE_A/fattura.json"]').click();
  await page.keyboard.press('Control+s');
  await page.locator('.tab[title="CLIENTE_A/include/common.css"]').click();
  await page.keyboard.press('Control+s');
  await page.locator('.tab[title="CLIENTE_A/fattura.html"]').click();
  await page.keyboard.press('Control+s');
  await page.waitForTimeout(1500);
  const afterSave = await popup.locator('body').innerHTML();
  check('F4: badge "live" rimosso dopo salvataggio', !afterSave.includes('● live'));

  const realErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::'));
  if (realErrors.length) { console.log('\n⚠️ Errori JS:'); realErrors.forEach(e => console.log('  ' + e)); }
  else console.log('\n✅ Nessun errore JS');

  await browser.close();
  process.exit(failures || realErrors.length ? 1 : 0);
})().catch(e => { console.error('❌ TEST FALLITO:', e.message); process.exit(1); });
