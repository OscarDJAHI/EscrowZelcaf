package com.zlecaf.escrow.service;

import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit coverage of the content rows of the I/O matrix. Real magic bytes are
 * used so Tika's content sniffing is exercised, not a filename heuristic: the
 * validator must accept only files whose real type, extension and declared
 * Content-Type all agree and fall on the whitelist.
 */
class EvidenceContentValidatorTest {

    private final EvidenceContentValidator validator = new EvidenceContentValidator();

    /** JPEG SOI + JFIF header (magic 0xFFD8FF) then EOI. */
    private static byte[] jpegBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    /** 8-byte PNG signature followed by a minimal IHDR chunk header. */
    private static byte[] pngBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00});
        return out.toByteArray();
    }

    private static byte[] pdfBytes() {
        return ("%PDF-1.4\n"
                + "1 0 obj<</Type/Catalog>>endobj\n"
                + "trailer<</Root 1 0 R>>\n"
                + "%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] gifBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00, 0x00});
        return out.toByteArray();
    }

    @Test
    @DisplayName("A real JPEG (matching extension and Content-Type) is accepted")
    void acceptsValidJpeg() {
        assertThat(validator.validate(jpegBytes(), "photo.jpg", "image/jpeg"))
                .isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("A real PNG (matching extension and Content-Type) is accepted")
    void acceptsValidPng() {
        assertThat(validator.validate(pngBytes(), "capture.png", "image/png"))
                .isEqualTo("image/png");
    }

    @Test
    @DisplayName("A real PDF (matching extension and Content-Type) is accepted")
    void acceptsValidPdf() {
        assertThat(validator.validate(pdfBytes(), "invoice.pdf", "application/pdf"))
                .isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("PNG bytes disguised as a .pdf (declared application/pdf) are rejected")
    void rejectsFakeExtension() {
        assertThatThrownBy(() -> validator.validate(pngBytes(), "malware.pdf", "application/pdf"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("A non-whitelisted real type (GIF) is rejected even when self-consistent")
    void rejectsNonWhitelistedType() {
        assertThatThrownBy(() -> validator.validate(gifBytes(), "anim.gif", "image/gif"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("An empty file is rejected")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> validator.validate(new byte[0], "empty.pdf", "application/pdf"))
                .isInstanceOf(BadRequestException.class);
    }
}
