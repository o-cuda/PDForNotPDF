package io.github.ocuda.pdfornotpdf.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test unitari di PdfService: conversione HTML → PDF senza side-effect su disco.
 */
class PdfServiceTest {

    private final PdfService service = new PdfService();

    @Test
    void generatePdfReturnsValidPdfBytes() throws IOException {
        String html = "<html><head><title>Test</title></head><body><h1>Ciao PDF</h1><p>Corpo del documento.</p></body></html>";

        byte[] pdf = service.generatePdf(html);

        assertTrue(pdf.length > 500, "un PDF reale non è vuoto (ricevuti " + pdf.length + " bytes)");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "magic bytes PDF");
    }

    @Test
    void generatePdfHandlesMultiPageContent() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 300; i++) body.append("<p>Riga di contenuto numero ").append(i).append(" per riempire più pagine.</p>");
        String html = "<html><head><title>Multipagina</title></head><body>" + body + "</body></html>";

        byte[] pdf = service.generatePdf(html);
        String asText = new String(pdf, StandardCharsets.ISO_8859_1);

        assertTrue(asText.contains("/Type /Page"), "il PDF contiene pagine");
        assertTrue(pdf.length > 2000);
    }

    @Test
    void generatePdfDoesNotWriteAnywhere(@TempDir Path dir) throws IOException {
        String html = "<html><body><p>Contenuto</p></body></html>";

        service.generatePdf(html);

        try (var stream = Files.list(dir)) {
            assertEquals(0, stream.count(), "nessun side-effect su disco");
        }
    }

    /**
     * Audit 03 / B5 (verificato empiricamente e pinato): OpenHTMLtoPDF NON renderizza le
     * immagini SVG in data-URI — compaiono nell'anteprima HTML ma NON nel PDF stampato.
     * Il test documenta il limite: se un domani si introduce una rasterizzazione SVG
     * (es. svgsalamander), questo test va aggiornato insieme alla documentazione.
     */
    @Test
    void svgDataUriIsAKnownLimitationNotIncludedInPrintPdf() throws IOException {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='50' height='50'>"
                + "<circle cx='25' cy='25' r='20' fill='red'/></svg>";
        String dataUri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        String html = "<html><head><title>B5</title></head><body><h1>SVG</h1>"
                + "<img src=\"" + dataUri + "\" style=\"width:50px;height:50px;\"/></body></html>";

        byte[] pdf = service.generatePdf(html); // non deve fallire

        String asText = new String(pdf, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("/Subtype /Image") || asText.contains("/Subtype/Image"),
                "l'SVG data-URI non produce un XObject immagine (limite noto, vedi audit 03/B5)");
    }
}
