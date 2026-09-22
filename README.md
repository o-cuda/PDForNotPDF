# PDForNotPDF

Workspace interattivo per la progettazione e generazione di PDF da template Thymeleaf.  
Pensato per chi lavora con **OpenHTMLtoPDF** e vuole iterare rapidamente sul design dei template senza perdere tempo in cicli di build → deploy → check.

## Cosa fa

Un'interfaccia stile **VSCode**:

| Zona | Cosa fa |
|------|---------|
| 📁 Explorer — 📝 Snapshot (sinistra, sopra) | Albero ricorsivo dei file di lavoro (`.html`, `.css`, `.json`), con cartelle comprimibili e larghezza regolabile. **Creazione** con `+ 📄` / `+ 📁` nell'intestazione (il nuovo `.html` nasce con impalcatura minima) e **rinomina** via menu contestuale (tasto destro o `F2`): la coppia `X.html` ↔ `X.json` si muove insieme e i riferimenti negli altri template (include Thymeleaf, css) vengono proposti per l'aggiornamento automatico. Le **immagini** si aprono in un viewer di sola visualizzazione e si caricano con il bottone `🖼️` (nella cartella selezionata, default `assets/`; `png jpg jpeg gif webp svg`; su nome esistente chiede conferma e sovrascrive). **🗑️ Elimina** dal menu contestuale: bloccato se il file è referenziato da altri template (409 con la lista); le cartelle si cancellano solo se vuote |
| 📦 Explorer — Release (sinistra, sotto) | Le versioni pubblicate, **in sola lettura**: documento → versione (con stato `candidate`/`published`/`attiva ✓`) → file. Visualizza il template (tab read-only), 👁 anteprima e 🖨 stampa PDF **dallo snapshot congelato**. Le dipendenze condivise sono raggruppate sotto `🔒 dipendenze congelate`. Anche le immagini pubblicate sono visualizzabili |
| 🗂️ Tab (in alto) | I file aperti, uno alla volta nell'editor. Pallino ● sui file con modifiche non salvate. Chiusura con × o click centrale (con conferma se dirty) |
| ✍️ Editor | **CodeMirror 6**: syntax highlighting HTML/CSS/JSON, numeri di riga, bracket matching, ricerca, **lint JSON** con errori di sintassi evidenziati. Salvataggio con **💾 Salva** o `Ctrl/Cmd+S` |
| 👁️ Anteprima (popout) | Si apre con **👁️ Popout** in una finestra separata del browser, sempre sincronizzata: ogni modifica si riflette in tempo reale, **inclusi i file non salvati**. Paginazione **Paged.js** attivabile/disattivabile con **📄 Paged** |
| 🖨️ Stampa PDF | Genera il PDF del template attivo **nello stato corrente** (include e buffer live) con OpenHTMLtoPDF |

### Layout del workspace

    mio-workspace/
    ├── .git/           # repo unico: storia di bozze e release
    ├── snapshot/       # area di lavoro (CLIENTE_x/, STANDARD/, assets/)
    └── release/        # versioni pubblicate (snapshot immutabili)
        └── CLIENTE_A/fattura/{index.json, v2/{manifest.json, files/…}}

I workspace nel formato precedente (file direttamente nella root) si migrano con
`python3 tools/migrate-workspace.py <percorso>`.

### Modello multi-tenant (STANDARD + CLIENTI)

Il workspace è la **root del progetto stampe**: cartelle `STANDARD/` (stampe valide per tutti) e una cartella per ogni cliente (`CLIENTE_A/`…), con quante stampe si vuole per cartella.

- **CSS espliciti**: ogni template dichiara i propri CSS con `<link>`, percorsi relativi alla root, multipli e in ordine di cascata:
  ```html
  <link rel="stylesheet" href="STANDARD/include/common.css" />
  <link rel="stylesheet" href="CLIENTE_A/include/common.css" />
  ```
  Vengono **inline-ati** al render (anteprima e PDF): vincono i buffer modificati non salvati sul disco.
- **JSON accoppiato** (unico vincolo): `CLIENTE_A/fattura.html` ↔ `CLIENTE_A/fattura.json` (stessa cartella, stesso nome).
- **Frammenti condivisi**: `~{STANDARD/tabella-tariffe :: tariffe}` si usa da qualunque cartella.
- **Immagini**: `<img src="assets/logo.png">` (png/jpg/gif/webp/svg) in cartelle qualsiasi; in anteprima sono servite dall'app, nel PDF incorpor come data-URI.

### Due modalità

1. **Modalità Workspace** — Carica una directory con i tuoi template, sotto-template, CSS e JSON. Modifica tutto in tempo reale. Quando il template è pronto, clicca 🖨️ **Stampa PDF** per generare il PDF tramite OpenHTMLtoPDF.

2. **Modalità REST API** — Espone un endpoint `POST /api/generate` dove passi nome template, workspace e dati reali (dal tuo gestionale), e ti restituisce il PDF.

## Stack

- Java 21
- Spring Boot 3.3
- Thymeleaf (per i template)
- CodeMirror 6 (editor con syntax highlighting, bundle locale)
- Paged.js (paginazione dell'anteprima)
- OpenHTMLtoPDF (per la generazione PDF)
- SpringDoc/Swagger (per la documentazione API)

> 📐 La documentazione tecnica di architettura e le proposte di evoluzione sono documenti **interni** (non distribuiti con il repo).

## Come avviare in locale

### Prerequisiti

- **Java 21** — `java -version` deve mostrare `21.x`
- **Maven** — `mvn -version`
- Se non li hai: `curl -s "https://get.sdkman.io" | bash && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk install java 21.0.9-tem && sdk install maven`

### Avvio

```bash
cd pdfornotpdf/
mvn spring-boot:run
```

L'app parte sulla porta **8080**.

> Se modifichi `frontend/editor-main.js` (il bundle CodeMirror), rigeneralo: `npm install` poi `node frontend/build.js`.

### Provala nel browser

**In locale:**  
👉 [http://localhost:8080](http://localhost:8080)

**Da un altro dispositivo della tua rete:**  
👉 `http://<tuo-ip>:8080`

**Swagger (documentazione API):**  
👉 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

> 💡 L'URL del workbench è condivisibile e sopravvive al refresh: `…/workspace?ws=/percorso/del/workspace`

### Prova rapida

1. Avvia l'app
2. Apri [http://localhost:8080](http://localhost:8080)
3. Genera un workspace di esempio **fuori dal repo dell'app** e incolla il suo percorso:  
   ```bash
   python3 tools/generate-demo-workspace.py ~/stampe-demo
   ```
4. Clicca **Carica workspace**
5. Apri `fattura.html` dall'**Explorer** → si apre come tab
6. Clicca **👁️ Popout** → l'anteprima si apre in una finestra separata, impaginata con Paged.js e con gli include risolti
7. Modifica il JSON o il template → l'anteprima si aggiorna in tempo reale
8. Clicca **🖨️ Stampa PDF** per scaricare il PDF generato

### Anteprima: Paged.js e finestra separata

L'anteprima (solo via popout) usa **Paged.js** per impaginare il documento in pagine reali (stile viewer PDF), così vedi subito come sarà la paginazione del PDF finale:

- **📄 Paged** — toggle nella toolbar: attiva/disattiva la paginazione Paged.js. Se il tuo CSS non definisce `@page`, viene applicato `A4` di default (come OpenHTMLtoPDF); se lo definisci, la tua regola vince.
- **👁️ Popout** — apre l'anteprima in una **finestra separata del browser**, sempre sincronizzata in tempo reale: ogni modifica a template, CSS o JSON si riflette anche lì. Utile per mettere l'anteprima su un secondo monitor.

## Struttura del progetto

```
PDForNotPDF/
├── ARCHITECTURE.md                 # Documentazione architetturale
├── frontend/                       # Sorgente bundle CodeMirror 6
│   ├── editor-main.js              #   entry point (tema, linguaggi, lint)
│   └── build.js                    #   build esbuild → static/js/editor.js
├── pdfornotpdf/                    # Progetto Spring Boot
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/.../
│       │   │   ├── PdForNotPdfApplication.java
│       │   │   ├── controller/
│       │   │   │   ├── WorkspaceController.java   # UI web + explorer (snapshot/release) + file-ops + release
│       │   │   │   ├── ApiController.java         # REST API (/api/generate)
│       │   │   │   └── LlmController.java         # Assistente AI (BYOK/self-hosted)
│       │   │   └── service/
│       │   │       ├── PdfService.java            # Wrapper OpenHTMLtoPDF
│       │   │       ├── WorkspaceService.java      # Tree, render, overlay, save, file-ops, immagini
│       │   │       ├── ReleaseService.java        # Ciclo di vita + repo git unico + sync Gitea
│       │   │       ├── DocxImportService.java     # Import DOCX (scaffold + header/footer Word)
│       │   │       └── LlmService.java            # Client LLM OpenAI-compatibile/Ollama
│       │   ├── resources/
│       │   │   ├── static/js/editor.js            # Bundle CM6 precompilato (committato)
│       │   │   └── templates/
│       │   │       ├── loader.html                # Selezione workspace
│       │   │       ├── workspace.html             # Workbench (doppio explorer + tab + popout)
│       │   │       └── fragments/preview.html     # Fragment anteprima
│       └── test/java/.../                         # 69 test (controller + service)
├── docker/
│   └── gitea/                      # Gitea locale: approvazioni via PR (setup idempotente)
├── tools/
│   ├── generate-demo-workspace.py  # Workspace demo (formato snapshot/, idempotente)
│   └── migrate-workspace.py        # Migrazione dal formato precedente (snapshot/ + release/)
├── test-explorer.js                # E2E: explorer, tab, popout, viewer immagini (22)
├── test-phase234.js                # E2E: CM6, save, overlay (21)
├── test-fileops.js                 # E2E: nuovo file/cartella, rinomina, delete con blocco, upload/replace immagini (22)
├── test-lifecycle.js               # E2E: bozza→candidata→PR→approvata→rollback (Gitea)
├── test-workspace/                 # Workspace di esempio
│   ├── fattura.html                # Template Thymeleaf di test (include header/footer)
│   ├── fattura.css                 # CSS di test
│   ├── fattura.json                # Dati mock di test
│   └── includes/
│       ├── header.html             # Frammento incluso via th:replace
│       └── footer.html             # Frammento incluso via th:replace
└── design-process/                 # Documentazione WDS del progetto
    ├── 00-design-log.md
    ├── A-Product-Brief/
    ├── C-UX-Scenarios/
    └── D-Design-System/
```

## Test

```bash
# Unit + integration test backend (69)
cd pdfornotpdf && mvn test

# E2E (richiedono l'app avviata su :8080; modificano test-workspace e lo ripristinano)
node test-explorer.js     # explorer multi-tenant, tab, popout base, viewer immagini (22 check)
node test-phase234.js     # editor CM6, salvataggio, overlay live multi-file (21 check)
node test-fileops.js      # file ops complete: create/rename/delete con blocco, upload+replace immagini (22 check)
node test-lifecycle.js    # ciclo di vita con Gitea + Explorer Release read-only e stampa (20 check)
```

## Ciclo di vita dei template (bozza → candidata → approvata)

Le stampe in produzione sono **snapshot immutabili** pubblicati dal workbench:

1. **Modifica + `Ctrl+S`** — salvataggi liberi dei file (bozze, nessuna versione)
2. **🗂️ Bozza** — snapshot della chiusura del documento (template + include + CSS + immagini + json)
   + commit nel repo bozze del workspace (`<workspace>/.git`, creato automaticamente)
3. **📤 Pubblica** (dal pannello 📚 Versioni) — crea una **candidata** `v<n>` in `release/`,
   con manifest e hash SHA-256, in attesa di approvazione
4. **Approvazione** — dal pannello: preview della candidata, nome approvatore, eventuale
   **data di efficacia** (per i cambi di legge: la versione si attiva da sola alla data),
   conferma → diventa la versione **attiva** servita dall'API
5. **Rollback** — riattivare una versione approvata precedente in un click

L'API serve solo le versioni approvate: `POST /api/generate` senza `version` = attiva;
con `&version=N` = pin esplicito.

**Componenti condivisi (header/footer/css comuni)**: modificare un file condiviso non tocca
nessun documento pubblicato. `GET /release/impact?workspace=…&file=CLIENTE_A/include/header.html`
restituisce i documenti attivi che lo usano → si pubblicano le nuove candidate solo quando
si vuole (anche in blocco, approvandole insieme).

**Backup**: tutto è file piatti — `snapshot/` (bozze) + `release/` (versioni) in un **unico repo git** alla root del workspace
(snapshot immutabili con storico git dedicato). Copiare la directory = backup completo;
copiarla su un altro host = seconda istanza.

### Approvazione con Gitea (docker)

Il flusso di pubblicazione usa **Gitea** (container locale) per lo strato di approvazione:

```bash
docker compose -f docker/gitea/docker-compose.yml up -d
./docker/gitea/setup-gitea.sh        # utente admin + repo stampe-releases (idempotente)
```

- **📤 Pubblica** → push del branch `candidate/<doc>/v<n>` + apertura **Pull Request**
- L'approvatore fa review e **merge** dalla UI di Gitea (ⓘ il merge può richiedere un
  retry di qualche secondo subito dopo la creazione della PR: Gitea calcola la merge-base
  in modo asincrono)
- L'app sincronizza `main` ogni 30s (o **🔄 Sincronizza col remote** nel pannello Versioni):
  la versione merge-ata diventa **pubblicata** e servita dall'API
- Utente admin: `pdforCurrent` — la **password viene generata** da `setup-gitea.sh` e salvata
  in `docker/gitea/.credentials` (gitignored). Per l'app: `export GITEA_PASSWORD=…` prima
  di avviarla (config: `app.release.remote.*`, legge la variabile d'ambiente).
  Per ruotarla su un'istanza esistente: `docker exec -u git pdforNotPdf-gitea gitea admin
  user change-password --username pdforCurrent --password <nuova>`

## Import documenti (M3) e assistente AI (M2)

### 📥 Import DOCX/PDF

Dal pulsante **📥 Importa**:

- **DOCX** → bozza di template HTML: titoli, paragrafi formattati, tabelle, elenchi e
  immagini vengono convertiti; le immagini finiscono in `assets/import/<documento>/`.
  La cartella di destinazione indicata viene **creata se non esiste**.
  **Header e footer di Word vengono estratti** (carte intestate: logo/grafica nell'header
  finiscono in `<header class="docx-header">` all'inizio del template — le immagini
  ancorate a pagina intera arrivano come `<img>` da riordinare/posizionare col CSS).
  I placeholder Word (`[NOME DEL PROPIETARIO]`) restano testo: la mappatura verso `th:*`
  si fa dopo (a mano o con l'assistente AI).
- **PDF** → salvato in `assets/reference/` come **documento di riferimento** consultabile
  (non convertito: il PDF non ha struttura riutilizzabile).

Endpoint: `POST /workspace/import-docx` e `POST /workspace/import-reference` (multipart).

### 🤖 Assistente AI (BYOK — bring your own key)

Il pannello **🤖 Assistente** propone modifiche ai template in linguaggio naturale.
Configurazione per utente (salvata **solo nel browser**, mai sul server):

- **Base URL**: endpoint OpenAI-compatibile (OpenAI, OpenRouter, gateway aziendale,
  **Ollama self-hosted** per non far uscire i dati)
- **API key** e **modello**

Ogni proposta viene mostrata come **diff** e applicata solo su conferma, sul buffer
(never-write-to-disk). Con remote Gitea configurato l'approvazione resta il merge della PR.

## REST API

Genera un PDF passando template e dati reali.

```bash
curl -X POST \
  'http://localhost:8080/api/generate?workspace=/home/ocuda/workspace/PDForNotPDF/test-workspace&template=fattura.html&cssFile=fattura.css' \
  -H 'Content-Type: application/json' \
  -d '{
    "titolo": "Fattura #2026-002",
    "cliente": "ACME Spa",
    "data": "14/07/2026",
    "righe": [
      {"descrizione": "Consulenza", "quantita": 3, "prezzo": "€450.00"}
    ],
    "totale": "1,350.00"
  }' \
  --output fattura.pdf
```

## Workspace

Il workspace è la **root del progetto** (caricata dal loader). Struttura attesa:

```
progetto-stampe/
├── STANDARD/                 # stampe standard per tutti i clienti
│   ├── fattura.html + fattura.json
│   ├── preventivo.html + preventivo.json
│   ├── tabella-tariffe.html  # frammento th:fragment condiviso
│   └── include/ common.css, standard.css, header.html, footer.html
├── CLIENTE_A/                # stampe custom del cliente
│   ├── fattura.html + fattura.json
│   └── include/ common.css, fattura.css, header.html
├── STANDARD/assets/          # loghi/certificazioni STANDARD (png/jpg/gif/webp/svg)
└── CLIENTE_A/assets/         # loghi specifici del cliente
```

> Gli asset vivono **dentro il dominio che li possiede**: un logo di cliente in `CLIENTE_x/assets/`, un logo/certificazione standard in `STANDARD/assets/`. Referenziabili da qualunque template con percorso root-relative.

- **CSS**: dichiarati nel template con `<link rel="stylesheet" href="…">` — percorsi **relativi alla root del workspace**, multipli, applicati in ordine. Un template può mescolare CSS STANDARD e propri.
- **JSON**: accoppiato alla pagina per **cartella + nome** (`CLIENTE_A/fattura.html` → `CLIENTE_A/fattura.json`). È l'unico vincolo del modello.
- **Immagini**: referenziabili da qualunque template con percorsi root-relative (`assets/logo.png`).
- **Include Thymeleaf**: `th:replace="~{STANDARD/tabella-tariffe :: tariffe}"` — risolvibili cross-cartella.
- **Stato corrente**: anteprima e PDF usano i **buffer modificati** (non salvati) al posto dei file su disco, per tutti i file coinvolti (template, CSS linkati, JSON).

> ⚠️ Rottura rispetto alle versioni precedenti: non esiste più il pairing CSS-per-nome; il CSS va dichiarato con `<link>` nel template.

### I tuoi template devono usare il tag `xmlns:th`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Documento</title>
</head>
<body>
    <h1 th:text="${titolo}">Titolo</h1>
    <p th:text="${cliente}">Cliente</p>
</body>
</html>
```

I template con `th:text`, `th:each`, `th:if` ecc. vengono processati in tempo reale con i dati dal pannello JSON.

## Licenza

Open source.
