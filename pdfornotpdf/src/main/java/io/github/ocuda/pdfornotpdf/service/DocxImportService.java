package io.github.ocuda.pdfornotpdf.service;

import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import io.github.ocuda.pdfornotpdf.service.WorkspacePaths;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import DOCX → template HTML di partenza.
 *
 * Obiettivo: unBeginning SKELETON pulito e semantico (non un rendering fedele di Word):
 *  - paragrafi con testo formattato (grassetto/corsivo/sottolineato)
 *  - titoli: stili Heading* / Titolo* + euristica "paragrafo breve tutto maiuscolo"
 *  - tabelle → table/tr/td (prima riga th)
 *  - elenchi (numerati e puntati) → ul/ol + li
 *  - immagini incorporate → estratte in <cartella>/assets/import/<documento>/ con <img>
 *    (root se l'import avviene senza cartella); gli <img src> sono root-relative,
 *    stessa convenzione di CLIENTE_A/assets
 *
 * I placeholder Word ([NOME DEL PROPIETARIO]) restano testo: la mappatura verso th:*
 * avviene dopo (a mano o con l'assistente LLM).
 */
@Service
public class DocxImportService {

    private static final Pattern ALL_CAPS = Pattern.compile("[A-ZÀÈÉÌÒÙ0-9 ,.:'()/\\-\\[\\]«»\n]{6,80}");

    public record ImportedFile(String path, String content) {}
    public record ImportResult(String templatePath, String html, List<ImportedFile> assets) {}

    /** Variante che salva gli asset su disco mentre li estrae (evita il passaggio base64).
     *  B3 (audit 05): il template esistente rifiuta la riscrittura con FileAlreadyExistsException
     *  PRIMA di qualsiasi estrazione/scrittura — il 409 arriva senza effetti collaterali su disco. */
    public ImportResult importDocxToFiles(String docxName, InputStream docxStream, Path wsRoot,
                                          String folder, boolean overwrite, List<ImportedFile> assetsOut) throws IOException {
        String base = sanitizeBase(docxName);
        // C2 (audit 05): la cartella arriva da parametri di request — validata PRIMA di qualsiasi
        // scrittura (gli asset vengono scritti durante l'estrazione, non dopo)
        String rel = (folder == null ? "" : folder.replace('\\', '/') + "/") + base + ".html";
        WorkspacePaths.validateRelPath(rel);
        Path templatePath = WorkspacePaths.resolveInside(wsRoot, rel);
        if (Files.exists(templatePath) && !overwrite) {
            throw new FileAlreadyExistsException(rel);
        }
        // gli asset seguono la cartella del template: ALBA2/assets/import/<doc>/…
        // altrimenti le <img> con src root-relative sarebbero rotte (defetto del 2026-09-22)
        String assetPrefix = (folder == null || folder.isBlank() ? "" : folder.replace('\\', '/') + "/")
                + "assets/import/" + base;
        XWPFDocument doc = new XWPFDocument(docxStream);

        // body + header/footer di Word: nelle carte intestate il contenuto sta nell'header,
        // non nel body — senza questa estrazione l'import produrrebbe una pagina vuota.
        StringBuilder body = renderElements(doc.getBodyElements(), wsRoot, assetPrefix, assetsOut, true);
        String headerHtml = "";
        String footerHtml = "";
        try {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(doc);
            XWPFHeader hdr = policy.getDefaultHeader();
            XWPFFooter ftr = policy.getDefaultFooter();
            var writtenByHash = new java.util.HashMap<Integer, String>();
            if (hdr != null) {
                // per header/footer le immagini (inline E ancorate) vengono dagli r:embed della parte
                headerHtml = renderElements(hdr.getBodyElements(), wsRoot, assetPrefix, assetsOut, false).toString()
                        + extractBlipImages(hdr, writtenByHash, wsRoot, assetPrefix, assetsOut);
            }
            if (ftr != null) {
                footerHtml = renderElements(ftr.getBodyElements(), wsRoot, assetPrefix, assetsOut, false).toString()
                        + extractBlipImages(ftr, writtenByHash, wsRoot, assetPrefix, assetsOut);
            }
        } catch (Exception ignored) {
            // documento senza intestazione/piè di pagina
        }
        doc.close();

        StringBuilder content = new StringBuilder();
        if (!headerHtml.isBlank()) {
            content.append("<header class=\"docx-header\">\n").append(headerHtml).append("</header>\n");
        }
        content.append(body);
        if (!footerHtml.isBlank()) {
            content.append("<footer class=\"docx-footer\">\n").append(footerHtml).append("</footer>\n");
        }

        String templateRel = (folder == null || folder.isBlank() ? "" : folder + "/") + base + ".html";
        return new ImportResult(templateRel, documentScaffold(base, content.toString()), assetsOut);
    }

    /**
     * Rendering di paragrafi/tabelle/elenchi di una parte del documento (body, header o footer
     * di Word): condiviso tra le tre sezioni con estrazione immagini su disco.
     */
    private StringBuilder renderElements(List<IBodyElement> elements, Path wsRoot,
                                         String assetPrefix, List<ImportedFile> assetsOut,
                                         boolean extractRunPictures) throws IOException {
        StringBuilder out = new StringBuilder();
        boolean inList = false;

        for (IBodyElement el : elements) {
            if (el instanceof XWPFParagraph p) {
                boolean listItem = p.getNumID() != null;
                String text = renderRunsToFiles(p, wsRoot, assetPrefix, assetsOut, extractRunPictures);
                boolean isEmpty = text.isBlank();

                if (listItem && !isEmpty) {
                    if (!inList) { out.append("<ul>\n"); inList = true; }
                    out.append("  <li>").append(text).append("</li>\n");
                    continue;
                }
                if (inList) { out.append("</ul>\n"); inList = false; }

                if (isEmpty) continue;
                String style = p.getStyleID() == null ? "" : p.getStyleID().toLowerCase();
                if (style.startsWith("heading") || style.startsWith("titolo")) {
                    int level = Math.min(6, Math.max(1, parseTrailingDigits(style)));
                    out.append("<h").append(level).append(">").append(text).append("</h").append(level).append(">\n");
                } else if (isAllCapsTitle(p)) {
                    out.append("<h2>").append(text).append("</h2>\n");
                } else {
                    out.append("<p>").append(text).append("</p>\n");
                }
            } else if (el instanceof XWPFTable t) {
                if (inList) { out.append("</ul>\n"); inList = false; }
                out.append(renderTable(t));
            }
        }
        if (inList) out.append("</ul>\n");
        return out;
    }

    /**
     * Avvolge il body estratto dal DOCX in un documento HTML completo (scaffold minimo:
     * DOCTYPE + html + head/charset + title). Il template importato è subito apribile,
     * renderizzabile e stampabile, come i template creati con +📄 dall'Explorer.
     */
    static String documentScaffold(String title, String body) {
        return "<!DOCTYPE html>\n<html lang=\"it\">\n<head>\n  <meta charset=\"utf-8\">\n  <title>"
                + title + "</title>\n</head>\n<body>\n" + body + "</body>\n</html>\n";
    }

    // ===== rendering runs =====

    private String renderRunsToFiles(XWPFParagraph p, Path wsRoot, String assetPrefix,
                                     List<ImportedFile> assets, boolean extractPictures) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : p.getRuns()) {
            for (XWPFPicture pic : extractPictures ? r.getEmbeddedPictures() : java.util.List.<XWPFPicture>of()) {
                String ext = pic.getPictureData().suggestFileExtension();
                String fileName = "img-" + System.nanoTime() + "-" + assets.size() + "." + ext;
                try {
                    Path target = wsRoot.resolve(assetPrefix).resolve(fileName);
                    Files.createDirectories(target.getParent());
                    Files.write(target, pic.getPictureData().getData());
                    assets.add(new ImportedFile(assetPrefix + "/" + fileName, ""));
                    sb.append("<img src=\"").append(assetPrefix).append("/").append(fileName).append("\" style=\"max-width:100%;\" />");
                } catch (IOException ignored) { }
            }
            String text = r.text();
            if (text == null || text.isEmpty()) continue;
            String open = "", close = "";
            if (r.isBold()) { open += "<strong>"; close = "</strong>" + close; }
            if (r.isItalic()) { open += "<em>"; close = "</em>" + close; }
            if (r.getUnderline() != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) { open += "<u>"; close = "</u>" + close; }
            sb.append(open).append(escape(text)).append(close);
        }
        return sb.toString().trim();
    }

    /**
     * Estrae le immagini di un header/footer Word dagli r:embed dell'XML della parte:
     * copre sia i disegni inline sia quelli ANCORATI (sfondi a pagina intera delle carte
     * intestate) che getEmbeddedPictures non intercetta. Deduplica per contenuto.
     */
    private String extractBlipImages(XWPFHeaderFooter part, java.util.Map<Integer, String> writtenByHash,
                                     Path wsRoot, String assetPrefix, List<ImportedFile> assetsOut) {
        StringBuilder sb = new StringBuilder();
        try {
            String xml;
            try (var in = part.getPackagePart().getInputStream()) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher m = Pattern.compile("r:embed=\"(rId\\d+)\"").matcher(xml);
            while (m.find()) {
                XWPFPictureData pd = part.getPictureDataByID(m.group(1));
                if (pd == null) continue;
                byte[] data = pd.getData();
                int hash = java.util.Arrays.hashCode(data);
                String rel = writtenByHash.get(hash);
                if (rel == null) {
                    rel = assetPrefix + "/img-header-" + System.nanoTime() + "-" + assetsOut.size()
                            + "." + pd.suggestFileExtension();
                    Path target = wsRoot.resolve(rel);
                    Files.createDirectories(target.getParent());
                    Files.write(target, data);
                    assetsOut.add(new ImportedFile(rel, ""));
                    writtenByHash.put(hash, rel);
                }
                sb.append("<img src=\"").append(rel).append("\" style=\"max-width:100%;\" />\n");
            }
        } catch (Exception ignored) { }
        return sb.toString();
    }

    private String renderTable(XWPFTable t) {
        StringBuilder sb = new StringBuilder("<table>\n");
        int rowIdx = 0;
        for (XWPFTableRow row : t.getRows()) {
            sb.append("  <tr>\n");
            for (XWPFTableCell cell : row.getTableCells()) {
                String tag = rowIdx == 0 ? "th" : "td";
                String text = escape(cell.getText().trim());
                sb.append("    <").append(tag).append(">").append(text.isEmpty() ? "&nbsp;" : text)
                  .append("</").append(tag).append(">\n");
            }
            sb.append("  </tr>\n");
            rowIdx++;
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    // ===== euristica titoli =====

    private boolean isAllCapsTitle(XWPFParagraph p) {
        String text = p.getText().trim();
        if (text.length() < 6 || text.length() > 90) return false;
        if (!ALL_CAPS.matcher(text).matches()) return false;
        // bold su almeno un run ⇒ titolo
        for (XWPFRun r : p.getRuns()) if (r.isBold()) return true;
        return p.getRuns().isEmpty();
    }

    private static int parseTrailingDigits(String s) {
        Matcher m = Pattern.compile("(\\d)$").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 2;
    }

    public static String sanitizeBase(String docxName) {
        String n = docxName.toLowerCase().replaceAll("\\.docx$", "");
        n = n.replaceAll("[^a-z0-9]+", "-").replaceAll("-{2,}", "-");
        n = n.replaceAll("^-|-$", "");
        return n.isBlank() ? "documento-importato" : n;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
