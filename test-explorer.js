/**
 * E2E: Explorer multi-tenant (STANDARD / CLIENTI) + tab + popout base.
 * Uso: node test-explorer.js   (app avviata su :8080)
 */
const { chromium } = require('playwright');

const WS = '/home/ocuda/workspace/PDForNotPDF/test-workspace';
let failures = 0;
function check(name, ok) { console.log((ok ? '✅' : '❌') + ' ' + name); if (!ok) failures++; }

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  // Loader: path errato → errore
  await page.goto('http://localhost:8080/');
  await page.fill('input[name="path"]', '/percorso/inesistente');
  await page.click('button[type="submit"]');
  await page.waitForSelector('.error');
  check('Loader: errore su directory inesistente', await page.locator('.error').count() === 1);

  // Load workspace progetto
  await page.fill('input[name="path"]', WS);
  await page.click('button[type="submit"]');
  await page.waitForURL('**/workspace?ws=**');
  await page.waitForSelector('.tree-file');

  // Explorer: struttura multi-tenant
  const treeText = await page.locator('#explorer-tree').innerText();
  for (const f of ['STANDARD', 'CLIENTE_A', 'assets', 'fattura.html', 'preventivo.html',
                   'contratto.html', 'tabella-tariffe.html', 'logo-cliente-a.png']) {
    check('Explorer: ' + f + ' visibile', treeText.includes(f));
  }

  // Apertura template cliente
  await page.locator('.tree-file[title="CLIENTE_A/preventivo.html"]').click();
  await page.waitForTimeout(350);
  check('Tab CLIENTE_A/preventivo.html aperto', await page.locator('.tab[title="CLIENTE_A/preventivo.html"]').count() === 1);
  const doc = await page.evaluate(() => view.state.doc.toString());
  check('Editor: contiene i 2 <link> ai CSS', (doc.match(/<link[^>]*stylesheet/g) || []).length === 2);
  check('Editor: frammento cross-cartella STANDARD', doc.includes('~{STANDARD/tabella-tariffe :: tariffe}'));

  // Immagini nell'Explorer: non apribili
  await page.locator('.tree-file[title*="logo-cliente-a.png"]').click();
  await page.waitForTimeout(300);
  check('Immagine non apre tab', await page.locator('.tab', { hasText: 'logo-cliente-a.png' }).count() === 0);

  // Multi-tab
  await page.locator('.tree-file[title="CLIENTE_A/fattura.json"]').click();
  await page.waitForTimeout(250);
  check('Tab json (CLIENTE_A) aperto', await page.locator('.tab[title="CLIENTE_A/fattura.json"]').count() === 1);

  // Popout: paginazione + include cross-cartella + immagini
  await page.locator('.tab[title="CLIENTE_A/preventivo.html"]').click();
  await page.waitForTimeout(200);
  const [popup] = await Promise.all([
    page.waitForEvent('popup', { timeout: 5000 }),
    page.click('button[onclick="popoutPreview()"]'),
  ]);
  await popup.waitForLoadState('domcontentloaded');
  await popup.locator('.pagedjs_page').first().waitFor({ timeout: 15000 });
  const popoutText = await popup.locator('body').innerText();
  check('Popout: dati JSON + titolo cliente', popoutText.includes('Preventivo CLIENTE_A'));
  check('Popout: frammento tabella-tariffe (STANDARD)', popoutText.includes('Tariffa oraria'));
  check('Popout: footer STANDARD con certificazione (img via asset)', await popup.locator('img[src*="/workspace/asset"]').count() >= 2);
  check('Popout: badge con css ×2', await popup.locator('div[style*="position:fixed"]').first().innerText().then(t => t.includes('css ×2')));

  // Paged toggle
  await page.click('#paged-toggle');
  await page.waitForTimeout(2000);
  check('Toggle Paged OFF: niente paginazione', await popup.locator('.pagedjs_page').count() === 0);
  await page.click('#paged-toggle');
  await popup.locator('.pagedjs_page').first().waitFor({ timeout: 15000 });
  check('Toggle Paged ON: paginazione ripristinata', await popup.locator('.pagedjs_page').count() >= 1);

  const realErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::'));
  if (realErrors.length) { console.log('\n⚠️ Errori JS:'); realErrors.forEach(e => console.log('  ' + e)); }
  else console.log('\n✅ Nessun errore JS');

  await browser.close();
  process.exit(failures || realErrors.length ? 1 : 0);
})().catch(e => { console.error('❌ TEST FALLITO:', e.message); process.exit(1); });
