# Third-Party Notices — Licenze software di terze parti

PDForNotPDF è distribuito sotto licenza **Apache License 2.0** (vedi [LICENSE](LICENSE)).

Questo progetto utilizza le librerie di terze parti elencate di seguito. Ogni libreria
rimane opera dei rispettivi autori ed è distribuita sotto la propria licenza.
Le versioni indicate sono quelle risolte al momento della stesura (verificare
`pdfornotpdf/pom.xml`, `package.json` e i rispettivi lockfile per gli aggiornamenti).

---

## ⚠️ Componente sotto LGPL — OpenHTMLtoPDF

Il motore di rendering PDF del progetto è basato su **OpenHTMLtoPDF**, distribuito sotto
**GNU Lesser General Public License v2.1 o successiva (LGPL-2.1-or-later)**:

> OpenHTMLtoPDF — https://github.com/danfickle/openhtmltopdf
> Copyright (c) danfickle e contributori
> Licenza: GNU Lesser General Public License, versione 2.1 o successiva

- **Utilizzo in questo progetto**: OpenHTMLtoPDF è consumata come libreria esterna
  (dipendenza Maven separata, non modificata). Ai sensi della LGPL il codice
  applicativo che la utilizza non è soggetto a obblighi di licenza derivati.
- **Codice sorgente della libreria**: disponibile presso i link sopra indicati.
- Se si modificano i sorgenti di OpenHTMLtoPDF, tali modifiche devono essere
  rilasciate sotto LGPL-2.1-or-later.

---

## Licenze dei componenti

### Apache License 2.0

| Componente | Versione | Scope |
|---|---|---|
| org.springframework.boot:spring-boot-starter-web | 3.3.5 | runtime |
| org.springframework.boot:spring-boot-starter-thymeleaf | 3.3.5 | runtime |
| org.springframework.boot:spring-boot-starter-tomcat | 3.3.5 | runtime |
| org.springframework.boot:spring-boot-starter-json | 3.3.5 | runtime |
| org.springframework.boot:spring-boot-starter-logging | 3.3.5 | runtime |
| org.springframework.boot:spring-boot-starter-test | 3.3.5 | test |
| org.springframework.boot:spring-boot | 3.3.5 | runtime |
| org.springframework.boot:spring-boot-autoconfigure | 3.3.5 | runtime |
| org.springframework.boot:spring-boot-devtools | 3.3.5 | runtime |
| org.springframework:spring-web / spring-webmvc | 6.1.14 | runtime |
| org.springframework:spring-core / spring-beans / spring-aop / spring-context / spring-expression / spring-jcl / spring-test | 6.1.14 | runtime/test |
| org.apache.tomcat.embed:tomcat-embed-core / -el / -websocket | 10.1.31 | runtime |
| org.thymeleaf:thymeleaf / thymeleaf-spring6 | 3.1.2.RELEASE | runtime |
| org.thymeleaf.extras:thymeleaf-extras-springsecurity6 | 3.1.2.RELEASE | runtime |
| org.attoparser:attoparser | 2.0.7.RELEASE | runtime |
| org.unbescape:unbescape | 1.1.6.RELEASE | runtime |
| com.fasterxml.jackson.core:jackson-databind / jackson-core / jackson-annotations | 2.17.2 | runtime |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml | 2.17.2 | runtime |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8 / -jsr310 | 2.17.2 | runtime |
| com.fasterxml.jackson.module:jackson-module-parameter-names | 2.17.2 | runtime |
| org.springdoc:springdoc-openapi-starter-webmvc-ui / -api / -common | 2.6.0 | runtime |
| io.swagger.core.v3:swagger-core-jakarta / -annotations-jakarta / -models-jakarta | 2.2.22 | runtime |
| org.webjars:swagger-ui | 5.17.14 | runtime |
| ognl:ognl | 3.3.4 | runtime |
| org.apache.poi:poi-ooxml / poi / poi-ooxml-lite | 5.2.5 | runtime |
| org.apache.xmlbeans:xmlbeans | 5.2.0 | runtime |
| org.apache.pdfbox:pdfbox / fontbox / xmpbox | 2.0.24 | runtime |
| de.rototor.pdfbox:graphics2d | 0.32 | runtime |
| org.apache.commons:commons-lang3 | 3.14.0 | runtime |
| org.apache.commons:commons-compress | 1.25.0 | runtime |
| org.apache.commons:commons-math3 | 3.6.1 | runtime |
| org.apache.commons:commons-collections4 | 4.4 | runtime |
| commons-io:commons-io | 2.15.0 | runtime |
| commons-codec:commons-codec | 1.16.1 | runtime |
| commons-logging:commons-logging | 1.2 | runtime |
| org.apache.logging.log4j:log4j-api / log4j-to-slf4j | 2.23.1 | runtime |
| io.micrometer:micrometer-observation / micrometer-commons | 1.13.6 | runtime |
| org.yaml:snakeyaml | 2.2 | runtime |
| jakarta.validation:jakarta.validation-api | 3.0.2 | runtime |
| com.zaxxer:SparseBitSet | 1.3 | runtime |
| com.googlecode.javaewah:JavaEWAH | 1.2.3 | runtime |
| org.javassist:javassist | 3.29.0-GA | runtime (licenza tripla: Apache-2.0 / LGPL-2.1 / MPL-1.1 — qui applicata la clausola Apache-2.0) |
| org.assertj:assertj-core | 3.25.3 | test |
| org.awaitility:awaitility | 4.2.2 | test |
| net.bytebuddy:byte-buddy / byte-buddy-agent | 1.14.19 | test |
| org.objenesis:objenesis | 3.3 | test |
| com.jayway.jsonpath:json-path | 2.9.0 | test |
| net.minidev:json-smart / accessors-smart | 2.5.1 | test |
| org.skyscreamer:jsonassert | 1.5.3 | test |
| com.vaadin.external.google:android-json | 0.0.20131108.vaadin1 | test |
| org.xmlunit:xmlunit-core | 2.9.1 | test |
| org.apiguardian:apiguardian-api | 1.1.2 | test |
| org.opentest4j:opentest4j | 1.3.0 | test |
| playwright / playwright-core (npm) | 1.61.1 | build/test E2E |
| esbuild + @esbuild/linux-x64 (npm) | 0.28.2 | build |

### MIT License

| Componente | Versione | Scope |
|---|---|---|
| org.jsoup:jsoup | 1.18.3 | runtime |
| org.slf4j:slf4j-api / jul-to-slf4j | 2.0.16 | runtime |
| codemirror (npm) | 6.0.2 | frontend |
| @codemirror/autocomplete | 6.20.3 | frontend |
| @codemirror/commands | 6.11.0 | frontend |
| @codemirror/lang-css / lang-html / lang-javascript / lang-json | 6.x | frontend |
| @codemirror/language | 6.12.4 | frontend |
| @codemirror/lint | 6.9.7 | frontend |
| @codemirror/search | 6.7.1 | frontend |
| @codemirror/state | 6.7.1 | frontend |
| @codemirror/view | 6.43.9 | frontend |
| @lezer/common / css / highlight / html / javascript / json / lr | 1.x | frontend |
| @marijn/find-cluster-break | 1.0.4 | frontend |
| style-mod | 4.1.3 | frontend |
| crelt | 1.0.7 | frontend |
| w3c-keyname | 2.2.8 | frontend |
| org.mockito:mockito-core / mockito-junit-jupiter | 5.11.0 | test |

### BSD-3-Clause (incl. EDL 1.0 — Eclipse Distribution License)

| Componente | Versione | Scope |
|---|---|---|
| org.eclipse.jgit:org.eclipse.jgit | 6.10.1.202505221210-r | runtime |
| jakarta.xml.bind:jakarta.xml.bind-api | 4.0.2 | runtime |
| jakarta.activation:jakarta.activation-api | 2.1.3 | runtime |
| org.hamcrest:hamcrest | 2.2 | test |
| org.ow2.asm:asm | 9.6 | test |
| com.github.virtuald:curvesapi | 1.08 | runtime |

### EPL — Eclipse Public License

| Componente | Versione | Licenza | Scope |
|---|---|---|---|
| org.junit.jupiter:junit-jupiter (+ api, params, engine) | 5.10.5 | EPL-2.0 | test |
| org.junit.platform:junit-platform-commons / -engine | 1.10.5 | EPL-2.0 | test |
| jakarta.annotation:jakarta.annotation-api | 2.1.1 | EPL-2.0 o GPL-2.0 con Classpath Exception | runtime |
| ch.qos.logback:logback-classic / logback-core | 1.5.11 | EPL-1.0 o LGPL-2.1 (doppia) | runtime |

### LGPL — GNU Lesser General Public License

| Componente | Versione | Licenza | Note |
|---|---|---|---|
| com.openhtmltopdf:openhtmltopdf-core | 1.0.10 | LGPL-2.1-or-later | motore PDF — vedi sezione dedicata in alto |
| com.openhtmltopdf:openhtmltopdf-pdfbox | 1.0.10 | LGPL-2.1-or-later | binding PDFBox di OpenHTMLtoPDF |

---

## Note di conformità

1. **Nessuna dipendenza sotto GPL/AGPL** è presente nel classpath: l'uso in prodotti
   commerciali e la ridistribuzione sotto licenza permissiva (Apache-2.0) sono consentiti.
2. **OpenHTMLtoPDF (LGPL)** è utilizzata come libreria esterna non modificata: questa
   modalità d'uso è espressamente consentita dalla LGPL anche in software distribuito
   sotto licenze permissive o proprietarie. Il codice sorgente originale è disponibile
   al repository upstream citato sopra.
3. **Javassist** è distribuita con triple licenza (Apache-2.0 / LGPL-2.1 / MPL-1.1);
   in questo progetto si applica la clausola **Apache-2.0**.
4. **Logback** è distribuita con doppia licenza (EPL-1.0 / LGPL-2.1); in questo
   progetto si applica la clausola **EPL-1.0**.
5. Le dipendenze con scope `test` (JUnit, Mockito, AssertJ, ecc.) non vengono
   distribuite con l'applicazione.
6. Questo file non sostituisce i testi integrali delle licenze citate, che restano
   gli unici riferimenti normativi: Apache-2.0 (LICENSE in questo repository),
   [LGPL-2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html),
   [EPL-1.0/2.0](https://www.eclipse.org/legal/),
   [MIT](https://opensource.org/licenses/MIT),
   [BSD-3](https://opensource.org/licenses/BSD-3-Clause).
