# PDForNotPDF

Workspace interattivo per la progettazione e generazione di PDF da template Thymeleaf.  
Pensato per chi lavora con **OpenHTMLtoPDF** e vuole iterare rapidamente sul design dei template senza perdere tempo in cicli di build → deploy → check.

## Cosa fa

Un'interfaccia stile **VSCode**:

| Zona | Cosa fa |
|------|---------|
| 📁 Explorer (sinistra) | Albero ricorsivo di tutti i file del workspace (`.html`, `.css`, `.json`), con cartelle comprimibili e larghezza regolabile |
| 🗂️ Tab (in alto) | I file aperti, uno alla volta nell'editor. Pallino ● sui file con modifiche non salvate. Chiusura con × o click centrale (con conferma se dirty) |
| ✍️ Editor | **CodeMirror 6**: syntax highlighting HTML/CSS/JSON, numeri di riga, bracket matching, ricerca, **lint JSON** con errori di sintassi evidenziati. Salvataggio con **💾 Salva** o `Ctrl/Cmd+S` |
| 👁️ Anteprima (popout) | Si apre con **👁️ Popout** in una finestra separata del browser, sempre sincronizzata: ogni modifica si riflette in tempo reale, **inclusi i file non salvati**. Paginazione **Paged.js** attivabile/disattivabile con **📄 Paged** |
| 🖨️ Stampa PDF | Genera il PDF del template attivo **nello stato corrente** (include e buffer live) con OpenHTMLtoPDF |

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
3. Nel campo "Percorso directory workspace" incolla:  
   ```
   /home/ocuda/workspace/PDForNotPDF/test-workspace
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
│       │   │   │   ├── WorkspaceController.java   # UI web + tree + save + preview + stampa
│       │   │   │   └── ApiController.java         # REST API (/api/generate)
│       │   │   └── service/
│       │   │       ├── PdfService.java            # Wrapper OpenHTMLtoPDF
│       │   │       └── WorkspaceService.java      # Tree, render, overlay, save
│       │   ├── resources/
│       │   │   ├── static/js/editor.js            # Bundle CM6 precompilato (committato)
│       │   │   └── templates/
│       │   │       ├── loader.html                # Selezione workspace
│       │   │       ├── workspace.html             # Workbench (explorer + tab + popout)
│       │   │       └── fragments/preview.html     # Fragment anteprima
│       └── test/java/.../
│           └── WorkspaceServiceTest.java          # Unit test (10)
├── test-explorer.js                # E2E: explorer, tab, popout base (18)
├── test-phase234.js                # E2E: CM6, save, overlay (20)
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
# Unit + integration test backend (33)
cd pdfornotpdf && mvn test

# E2E (richiedono l'app avviata su :8080; modificano test-workspace e lo ripristinano)
node test-explorer.js     # explorer multi-tenant, tab, popout base (21 check)
node test-phase234.js     # editor CM6, salvataggio, overlay live multi-file (21 check)
```

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
