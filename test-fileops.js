/**
 * E2E: Explorer file ops — nuovo file/cartella (scaffold), menu contestuale,
 * rinomina con dialogo proposta riferimenti, F2. Uso: node /tmp/test-fileops.js (app su :8080)
 */
const { makeCheck, rightClick, launch } = require('./test-utils');
const { execSync } = require('child_process');
const fs = require('fs');
execSync(`python3 ${__dirname}/tools/generate-demo-workspace.py ${'/home/ocuda/workspace/stampe-demo'}`);
const WS = '/home/ocuda/workspace/stampe-demo';
// i confirm del browser (es. sostituzione immagine) vengono accettati automaticamente da launch()
const { check, state } = makeCheck();

(async () => {
  const { browser, page, errors } = await launch();

  await page.goto('http://localhost:8080/');
  await page.fill('input[name="path"]', WS);
  await page.click('button[type="submit"]');
  await page.waitForSelector('.tree-file');

  // 1. +📄 senza selezione → crea nella root, con scaffold, e apre il tab
  await page.click('#btn-new-file');
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.type('prova-creazione');
  await page.keyboard.press('Enter');
  await page.waitForSelector('.tab');
  await page.waitForTimeout(500);
  const scaffold = fs.readFileSync(WS + '/snapshot/prova-creazione.html', 'utf8');
  check('+📄 root: file con scaffold (DOCTYPE+body)', scaffold.startsWith('<!DOCTYPE html>') && scaffold.includes('</body>'));
  check('+📄 root: tab aperto', await page.locator('.tab').count() === 1);

  // 1b. upload immagine + viewer
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
  await page.setInputFiles('#image-file-input', { name: 'nuova-immagine.png', mimeType: 'image/png', buffer: png });
  await page.waitForTimeout(600);
  check('🖼️ Upload: file su disco (assets/)', fs.existsSync(WS + '/snapshot/assets/nuova-immagine.png'));
  check('🖼️ Upload: viewer aperto', await page.locator('#image-host img').count() === 1
      && await page.locator('#image-host').isVisible());
  check('🖼️ Upload: tab immagine etichettato', (await page.locator('.tab.active').innerText()).includes('nuova-immagine.png'));
  // chiusura del tab immagine
  await page.locator('.tab.active .tab-close').click();
  await page.waitForTimeout(200);
  check('🖼️ Chiusura tab immagine (viewer nascosto)', await page.locator('#image-host').isHidden());

  // 1c. re-upload stesso nome → conferma sostituzione (replace=true)
  const png2 = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 9, 9, 9, 9]);
  await page.setInputFiles('#image-file-input', { name: 'nuova-immagine.png', mimeType: 'image/png', buffer: png2 });
  await page.waitForTimeout(600);
  const onDisk = fs.readFileSync(WS + '/snapshot/assets/nuova-immagine.png');
  check('🖼️ Replace: immagine sovrascritta', onDisk[8] === 9);

  // 2. seleziona cartella CLIENTE_A → +📁 poi +📄 dentro
  await page.locator('details[data-path="CLIENTE_A"] > summary').click();
  await page.click('#btn-new-dir');
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.type('sottocartella');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(500);
  check('+📁 su cartella selezionata', fs.existsSync(WS + '/snapshot/CLIENTE_A/sottocartella'));

  // 3. nome duplicato → errore inline, l'edit resta attivo
  await page.locator('details[data-path="CLIENTE_A"] > summary').click();
  await page.click('#btn-new-file');
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.press('Control+a');
  await page.keyboard.type('fattura.html');
  await page.keyboard.press('Enter');
  await page.waitForSelector('.edit-error');
  check('Duplicato: errore inline mostrato', (await page.locator('.edit-error').innerText()).includes('Esiste già'));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // 4. right-click su file SENZA riferimenti → menu 1 voce → rinomina secca (nessun dialogo)
  await rightClick(page, page.locator('.tree-file[title="prova-creazione.html"]'));
  await page.waitForSelector('.ctx-menu.open');
  check('Menu su file: Rinomina + Elimina', await page.locator('.ctx-item').count() === 2);
  await page.locator('.ctx-item', { hasText: 'Rinomina' }).click();
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.press('Control+a');
  await page.keyboard.type('prova-rinominata.html');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(500);
  check('Rinomina file secca (nessun riferimento)',
      fs.existsSync(WS + '/snapshot/prova-rinominata.html') && !fs.existsSync(WS + '/snapshot/prova-creazione.html'));


  // 5. right-click su cartella → 3 voci → Rinomina cartella con riferimento incluso → dialogo proposta
  await rightClick(page, page.locator('details[data-path="STANDARD"] > summary'));
  await page.waitForSelector('.ctx-menu.open');
  check('Menu su cartella: 4 voci', await page.locator('.ctx-item').count() === 4);
  await page.locator('.ctx-item', { hasText: 'Rinomina' }).click();
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.press('Control+a');
  await page.keyboard.type('STANDARD-V2');
  await page.keyboard.press('Enter');
  await page.waitForSelector('.rename-overlay.open');
  check('Dialogo proposta: include trovato in fattura.html', (await page.locator('#rename-refs').innerText()).includes('fattura.html'));
  await page.click('#rename-confirm');
  await page.waitForTimeout(600);
  check('Cartella rinominata', fs.existsSync(WS + '/snapshot/STANDARD-V2') && !fs.existsSync(WS + '/snapshot/STANDARD'));
  const preventivo = fs.readFileSync(WS + '/snapshot/CLIENTE_A/preventivo.html', 'utf8');
  check('Include aggiornato a STANDARD-V2', preventivo.includes('STANDARD-V2/tabella-tariffe') && !preventivo.includes('~{STANDARD/'));

  // 6. F2 sul file selezionato → inline-edit; Esc annulla senza rinominare
  await page.locator('.tree-file[title="prova-rinominata.html"]').click();
  await page.keyboard.press('F2');
  await page.waitForSelector('.tree-edit-input');
  const f2ok = (await page.locator('.tree-edit-input').inputValue()) === 'prova-rinominata.html';
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  check('F2 apre inline-edit, Esc annulla', f2ok && fs.existsSync(WS + '/snapshot/prova-rinominata.html'));


  // 6b. delete BLOCCATO: common.css (ora in STANDARD-V2) è referenziato dai template
  await rightClick(page, page.locator('.tree-file[title="STANDARD-V2/include/common.css"]'));
  await page.locator('.ctx-item', { hasText: 'Elimina' }).click();
  await page.waitForSelector('#delete-overlay.open');
  check('Delete bloccato: dialogo con referenze', (await page.locator('#delete-body').innerText()).includes('referenziato'));
  await page.evaluate(() => closeDeleteDialog());
  await page.waitForTimeout(200);
  check('Delete bloccato: file NON cancellato', fs.existsSync(WS + '/snapshot/STANDARD-V2/include/common.css'));

  // 6c. delete prima donna: prova-rinominata.html (nessun riferimento) + tab chiuso
  await rightClick(page, page.locator('.tree-file[title="prova-rinominata.html"]'));
  await page.locator('.ctx-item', { hasText: 'Elimina' }).click();
  await page.waitForSelector('#delete-overlay.open');
  await page.click('#delete-confirm');
  await page.waitForTimeout(500);
  check('Delete prima donna: file rimosso e tab chiuso',
      !fs.existsSync(WS + '/snapshot/prova-rinominata.html')
      && await page.locator('.tab', { hasText: 'prova-rinominata' }).count() === 0);

  // 6d. delete cartella vuota
  await rightClick(page, page.locator('details[data-path="CLIENTE_A/sottocartella"] > summary'));
  await page.locator('.ctx-item', { hasText: 'Elimina' }).click();
  await page.waitForSelector('#delete-overlay.open');
  await page.click('#delete-confirm');
  await page.waitForTimeout(400);
  check('Delete cartella vuota', !fs.existsSync(WS + '/snapshot/CLIENTE_A/sottocartella'));

  // 6b. T8 (audit 03): delete di una cartella NON vuota via UI → errore, la cartella resta
  await page.locator('details[data-path="CLIENTE_A"] > summary').click();
  await page.click('#btn-new-dir');
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.type('piena');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(600);
  await rightClick(page, page.locator('details[data-path="CLIENTE_A/piena"] > summary'));
  await page.locator('.ctx-item', { hasText: 'Nuovo file' }).click();
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.type('nota');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(800);
  check('T8: setup (nota.html dentro piena/)', fs.existsSync(WS + '/snapshot/CLIENTE_A/piena/nota.html'));
  await rightClick(page, page.locator('details[data-path="CLIENTE_A/piena"] > summary'));
  await page.locator('.ctx-item', { hasText: 'Elimina' }).click();
  await page.waitForSelector('#delete-overlay.open');
  await page.click('#delete-confirm');
  await page.waitForTimeout(600);
  check('T8: delete cartella non vuota rifiutato', fs.existsSync(WS + '/snapshot/CLIENTE_A/piena'));
  // pulizia: svuota la cartella, poi cancellala (ripristina lo stato demo)
  await rightClick(page, page.locator('.tree-file[title="CLIENTE_A/piena/nota.html"]'));
  await page.locator('.ctx-item', { hasText: 'Elimina' }).click();
  await page.waitForSelector('#delete-overlay.open');
  await page.click('#delete-confirm');
  await page.waitForTimeout(600);
  await rightClick(page, page.locator('details[data-path="CLIENTE_A/piena"] > summary'));
  await page.locator('.ctx-item', { hasText: 'Elimina' }).click();
  await page.waitForSelector('#delete-overlay.open');
  await page.click('#delete-confirm');
  await page.waitForTimeout(600);
  check('T8: pulizia completata', !fs.existsSync(WS + '/snapshot/CLIENTE_A/piena'));

  // 7. remap tab: apri fattura, rinominala, il tab segue
  await page.locator('.tree-file[title="CLIENTE_A/fattura.html"]').click();
  await page.waitForSelector('.tab.active');
  await rightClick(page, page.locator('.tree-file[title="CLIENTE_A/fattura.html"]'));
  await page.locator('.ctx-item', { hasText: 'Rinomina' }).click();
  await page.waitForSelector('.tree-edit-input');
  await page.keyboard.press('Control+a');
  await page.keyboard.type('fattura-2026.html');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(600);
  check('Tab remappato dopo rinomina', (await page.locator('.tab.active').innerText()).includes('fattura-2026.html'));

  const realErrors = errors.filter(e => !e.includes('409') && !e.includes('status of 400')); // 409 duplicato e 400 T8 sono attesi
  check('Nessun errore JS', realErrors.length === 0);
  if (realErrors.length) console.log(realErrors.join('\n'));
  await browser.close();
  console.log(state.failures ? `\n${state.failures} fallimenti` : '\nTutti i check passati');
  process.exit(state.failures ? 1 : 0);
})();
