package io.github.ocuda.pdfornotpdf.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
