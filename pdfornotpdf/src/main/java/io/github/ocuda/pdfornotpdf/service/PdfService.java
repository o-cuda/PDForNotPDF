package io.github.ocuda.pdfornotpdf.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Wrapper di OpenHTMLtoPDF: converte HTML in byte PDF, senza side-effect su disco.
 * (Il salvataggio su file è stato rimosso: la stampa dell'app scarica i byte via browser.)
 */
@Service
public class PdfService {

    /**
     * Generate PDF from HTML string and return bytes.
     */
    public byte[] generatePdf(String html) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, "");
            builder.toStream(baos);
            builder.run();

            return baos.toByteArray();
        }
    }
}
