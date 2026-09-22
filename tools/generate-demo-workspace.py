#!/usr/bin/env python3
"""
Genera un workspace di esempio multi-tenant per PDForNotPDF.

Uso: python3 tools/generate-demo-workspace.py <percorso-target>

Il workspace generato vive FUORI dal repo dell'applicazione ed è nel formato attuale:

    <target>/snapshot/…     ← area di lavoro (template, css, json, asset)
    <target>/release/…      ← release pubblicate (create dall'app)

Il repo git unico alla root viene inizializzato dall'app al primo "Salva bozza".
Idempotente: ricrea da zero.
"""
import os
import shutil
import sys
import zlib
import struct


def png(path, w, h, rgb):
    def chunk(t, data):
        c = t + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    raw = b''.join(b'\x00' + bytes(rgb) * w for _ in range(h))
    data = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)


def generate(root):
    if os.path.exists(root):
        shutil.rmtree(root)  # idempotente: ricrea da zero (usato anche dai test e2e)

    # Formato attuale: l'area di lavoro vive in snapshot/, le release in release/
    ws_root = root
    root = os.path.join(root, 'snapshot')

    # ===== Asset nei rispettivi domini =====
    png(os.path.join(root, 'STANDARD/assets/logo-standard.png'), 90, 24, (37, 99, 235))
    png(os.path.join(root, 'STANDARD/assets/certificazione.png'), 60, 20, (22, 163, 74))
    png(os.path.join(root, 'CLIENTE_A/assets/logo-cliente-a.png'), 90, 24, (220, 38, 38))

    # ===== STANDARD: frammenti condivisi =====
    write(f'{root}/STANDARD/include/header.html', '''<header th:fragment="header" style="border-bottom: 3px solid #2563eb; padding-bottom: 12px; margin-bottom: 24px;">
    <img src="STANDARD/assets/logo-standard.png" alt="Logo" style="float: right; width: 90px;" />
    <div style="font-size: 11px; color: #6b7280; text-transform: uppercase; letter-spacing: 1.5px;">Documento commerciale</div>
    <div style="font-size: 13px; color: #374151; margin-top: 4px;">ACME Spa — Via dei Tigli 12, 20100 Milano — P.IVA 01234567890</div>
</header>
''')
    write(f'{root}/STANDARD/include/footer.html', '''<footer th:fragment="footer" style="margin-top: 40px; border-top: 1px solid #e5e7eb; padding-top: 12px; font-size: 11px; color: #9ca3af;">
    <img src="STANDARD/assets/certificazione.png" alt="Certificazione" style="width: 60px; float: right;" />
    Pagamento a 30 giorni data fattura — Banca: IT60X0542811101000000123456 — info@acme.example
</footer>
''')
    write(f'{root}/STANDARD/tabella-tariffe.html', '''<table th:fragment="tariffe" style="width: 100%; border-collapse: collapse; margin: 20px 0;">
    <thead><tr><th>Categoria</th><th>Descrizione</th><th>Tariffa oraria</th></tr></thead>
    <tbody>
        <tr><td>Senior</td><td>Consulenza specializzata</td><td>€150</td></tr>
        <tr><td>Mid</td><td>Consulenza</td><td>€100</td></tr>
        <tr><td>Junior</td><td>Supporto</td><td>€60</td></tr>
    </tbody>
</table>
''')

    # ===== STANDARD: CSS =====
    write(f'{root}/STANDARD/include/common.css', '''/* CSS condiviso: usato sia dai template STANDARD sia dai template dei clienti */
body { font-family: Arial, sans-serif; margin: 40px; color: #333; font-size: 13px; }
table { width: 100%; border-collapse: collapse; margin: 20px 0; }
th { padding: 8px; text-align: left; }
td { padding: 6px 8px; border-bottom: 1px solid #e5e7eb; }
''')
    write(f'{root}/STANDARD/include/standard.css', '''/* Stile specifico dei template STANDARD */
h1 { color: #2563eb; border-bottom: 2px solid #2563eb; padding-bottom: 8px; }
th { background: #2563eb; color: white; }
''')

    # ===== STANDARD: stampe =====
    write(f'{root}/STANDARD/fattura.html', '''<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Fattura standard</title>
    <link rel="stylesheet" href="STANDARD/include/common.css" />
    <link rel="stylesheet" href="STANDARD/include/standard.css" />
</head>
<body>
    <div th:replace="~{STANDARD/include/header :: header}"></div>
    <h1 th:text="${titolo}">Fattura</h1>
    <p>Cliente: <span th:text="${cliente}">Nome Cliente</span> — Data: <span th:text="${data}">2026-01-01</span></p>
    <table>
        <thead><tr><th>Descrizione</th><th>Quantità</th><th>Prezzo</th></tr></thead>
        <tbody>
            <tr th:each="item : ${righe}">
                <td th:text="${item.descrizione}">desc</td>
                <td th:text="${item.quantita}">0</td>
                <td th:text="${item.prezzo}">€0.00</td>
            </tr>
        </tbody>
    </table>
    <p><strong>Totale: €<span th:text="${totale}">0.00</span></strong></p>
    <div th:replace="~{STANDARD/include/footer :: footer}"></div>
</body>
</html>
''')
    write(f'{root}/STANDARD/fattura.json', '''{
  "titolo": "Fattura standard #2026-001",
  "cliente": "Cliente generico",
  "data": "01/01/2026",
  "righe": [
    { "descrizione": "Consulenza", "quantita": 3, "prezzo": "€450.00" }
  ],
  "totale": "450.00"
}
''')
    write(f'{root}/STANDARD/preventivo.html', '''<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Preventivo standard</title>
    <link rel="stylesheet" href="STANDARD/include/common.css" />
    <link rel="stylesheet" href="STANDARD/include/standard.css" />
</head>
<body>
    <div th:replace="~{STANDARD/include/header :: header}"></div>
    <h1 th:text="${titolo}">Preventivo</h1>
    <p>Cliente: <span th:text="${cliente}">Nome Cliente</span> — Validità: <span th:text="${validita}">30 giorni</span></p>
    <div th:replace="~{STANDARD/tabella-tariffe :: tariffe}"></div>
    <p><strong>Totale stimato: €<span th:text="${totale}">0.00</span></strong></p>
    <div th:replace="~{STANDARD/include/footer :: footer}"></div>
</body>
</html>
''')
    write(f'{root}/STANDARD/preventivo.json', '''{
  "titolo": "Preventivo #P-2026-001",
  "cliente": "Cliente generico",
  "validita": "30 giorni",
  "totale": "1.500,00"
}
''')
    write(f'{root}/STANDARD/contratto.html', '''<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Contratto standard</title>
    <link rel="stylesheet" href="STANDARD/include/common.css" />
    <link rel="stylesheet" href="STANDARD/include/standard.css" />
</head>
<body>
    <div th:replace="~{STANDARD/include/header :: header}"></div>
    <h1 th:text="${titolo}">Contratto</h1>
    <p>Tra <strong th:text="${azienda}">ACME Spa</strong> e <strong th:text="${cliente}">Cliente</strong>.</p>
    <div th:replace="~{STANDARD/tabella-tariffe :: tariffe}"></div>
    <p th:text="${clausola}">Clausole contrattuali.</p>
    <div th:replace="~{STANDARD/include/footer :: footer}"></div>
</body>
</html>
''')
    write(f'{root}/STANDARD/contratto.json', '''{
  "titolo": "Contratto di consulenza 2026",
  "azienda": "ACME Spa",
  "cliente": "Cliente generico",
  "clausola": "Il presente contratto ha durata 12 mesi con rinnovo automatico."
}
''')

    # ===== CLIENTE_A: CSS + header =====
    write(f'{root}/CLIENTE_A/include/common.css', '''/* Override del brand CLIENTE_A: vince sul common STANDARD per ordine di cascata */
h1 { color: #dc2626; border-bottom-color: #dc2626; }
th { background: #dc2626; }
''')
    write(f'{root}/CLIENTE_A/include/fattura.css', '''/* Stile specifico della fattura CLIENTE_A */
.fattura-meta { background: #fef2f2; border-left: 4px solid #dc2626; padding: 8px 12px; margin: 12px 0; }
''')
    write(f'{root}/CLIENTE_A/include/header.html', '''<header th:fragment="header" style="border-bottom: 3px solid #dc2626; padding-bottom: 12px; margin-bottom: 24px;">
    <img src="CLIENTE_A/assets/logo-cliente-a.png" alt="Logo cliente" style="float: right; width: 90px;" />
    <div style="font-size: 11px; color: #6b7280; text-transform: uppercase; letter-spacing: 1.5px;">CLIENTE_A Srl — documento personalizzato</div>
</header>
''')

    # ===== CLIENTE_A: stampe =====
    write(f'{root}/CLIENTE_A/fattura.html', '''<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Fattura CLIENTE_A</title>
    <link rel="stylesheet" href="STANDARD/include/common.css" />
    <link rel="stylesheet" href="CLIENTE_A/include/common.css" />
    <link rel="stylesheet" href="CLIENTE_A/include/fattura.css" />
</head>
<body>
    <div th:replace="~{CLIENTE_A/include/header :: header}"></div>
    <h1 th:text="${titolo}">Fattura</h1>
    <div class="fattura-meta">
        Cliente: <span th:text="${cliente}">Nome Cliente</span> — Data: <span th:text="${data}">2026-01-01</span>
    </div>
    <table>
        <thead><tr><th>Descrizione</th><th>Quantità</th><th>Prezzo</th></tr></thead>
        <tbody>
            <tr th:each="item : ${righe}">
                <td th:text="${item.descrizione}">desc</td>
                <td th:text="${item.quantita}">0</td>
                <td th:text="${item.prezzo}">€0.00</td>
            </tr>
        </tbody>
    </table>
    <p><strong>Totale: €<span th:text="${totale}">0.00</span></strong></p>
    <div th:replace="~{STANDARD/include/footer :: footer}"></div>
</body>
</html>
''')
    write(f'{root}/CLIENTE_A/fattura.json', '''{
  "titolo": "Fattura CLIENTE_A #A-2026-001",
  "cliente": "Mario Rossi Spa",
  "data": "14/07/2026",
  "righe": [
    { "descrizione": "Consulenza dedicata", "quantita": 2, "prezzo": "€600.00" }
  ],
  "totale": "1.200,00"
}
''')
    write(f'{root}/CLIENTE_A/preventivo.html', '''<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Preventivo CLIENTE_A</title>
    <link rel="stylesheet" href="STANDARD/include/common.css" />
    <link rel="stylesheet" href="CLIENTE_A/include/common.css" />
</head>
<body>
    <div th:replace="~{CLIENTE_A/include/header :: header}"></div>
    <h1 th:text="${titolo}">Preventivo</h1>
    <p>Cliente: <span th:text="${cliente}">Nome Cliente</span> — Validità: <span th:text="${validita}">15 giorni</span></p>
    <div th:replace="~{STANDARD/tabella-tariffe :: tariffe}"></div>
    <p><strong>Totale stimato: €<span th:text="${totale}">0.00</span></strong></p>
    <div th:replace="~{STANDARD/include/footer :: footer}"></div>
</body>
</html>
''')
    write(f'{root}/CLIENTE_A/preventivo.json', '''{
  "titolo": "Preventivo CLIENTE_A #P-A-001",
  "cliente": "Mario Rossi Spa",
  "validita": "15 giorni",
  "totale": "2.400,00"
}
''')

    count = sum(len(fs) for _, _, fs in os.walk(root))
    print(f"Workspace demo generato in: {os.path.abspath(ws_root)} ({count} file in snapshot/)")


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    generate(sys.argv[1])
