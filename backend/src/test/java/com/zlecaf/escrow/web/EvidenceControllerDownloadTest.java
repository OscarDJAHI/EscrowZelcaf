package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EvidenceDownload;
import com.zlecaf.escrow.service.EvidenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Targeted proof of the download response wiring without a web/security context:
 * the controller is instantiated with a mocked {@link EvidenceService} and the
 * response is asserted directly. The invariant under test is the anti-XSS header
 * (NFR-2): the binary is served as an {@code attachment}, never {@code inline},
 * with the stored content-type and body.
 */
class EvidenceControllerDownloadTest {

    @Test
    @DisplayName("download wires a Content-Disposition: attachment response (never inline) with mime + body")
    void downloadServesAttachmentWithMimeAndBody() {
        EvidenceService service = mock(EvidenceService.class);
        EvidenceController controller = new EvidenceController(service);

        byte[] body = "hello-evidence".getBytes(StandardCharsets.UTF_8);
        AuthPrincipal actor = new AuthPrincipal(7L, "party@example.com", Role.BUYER);
        EvidenceDownload stub = new EvidenceDownload(
                new ByteArrayInputStream(body), "receipt.pdf", "application/pdf", (long) body.length);
        when(service.download(any(AuthPrincipal.class), eq(42L), eq(99L))).thenReturn(stub);

        ResponseEntity<InputStreamResource> response = controller.download(actor, 42L, 99L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        ContentDisposition cd = response.getHeaders().getContentDisposition();
        assertThat(cd.isAttachment()).isTrue();
        assertThat(cd.isInline()).isFalse();
        assertThat(cd.getFilename()).isEqualTo("receipt.pdf");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(body.length);

        byte[] served = readAll(response.getBody());
        assertThat(served).isEqualTo(body);
    }

    @Test
    @DisplayName("download omits the Content-Length header when the piece has an unknown (null) size, still streaming the body")
    void downloadOmitsContentLengthWhenSizeNull() {
        EvidenceService service = mock(EvidenceService.class);
        EvidenceController controller = new EvidenceController(service);

        byte[] body = "sizeless".getBytes(StandardCharsets.UTF_8);
        AuthPrincipal actor = new AuthPrincipal(7L, "party@example.com", Role.BUYER);
        // size_bytes is nullable end-to-end: a null size must not throw and must
        // leave the Content-Length header unset (getContentLength() == -1).
        EvidenceDownload stub = new EvidenceDownload(
                new ByteArrayInputStream(body), "receipt.pdf", "application/pdf", null);
        when(service.download(any(AuthPrincipal.class), eq(42L), eq(99L))).thenReturn(stub);

        ResponseEntity<InputStreamResource> response = controller.download(actor, 42L, 99L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1); // header absent
        assertThat(response.getHeaders().containsKey("Content-Length")).isFalse();
        assertThat(readAll(response.getBody())).isEqualTo(body);
    }

    @Test
    @DisplayName("download closes the already-open storage stream if building the response fails (no connection leak)")
    void downloadClosesStreamWhenResponseBuildFails() {
        EvidenceService service = mock(EvidenceService.class);
        EvidenceController controller = new EvidenceController(service);

        CloseTrackingInputStream tracker = new CloseTrackingInputStream();
        // An unparseable stored content-type makes MediaType.parseMediaType throw
        // AFTER the service opened the stream — the exact leak the guard prevents.
        EvidenceDownload stub = new EvidenceDownload(tracker, "receipt.pdf", "not-a-media-type", 3L);
        AuthPrincipal actor = new AuthPrincipal(7L, "party@example.com", Role.BUYER);
        when(service.download(any(AuthPrincipal.class), eq(42L), eq(99L))).thenReturn(stub);

        assertThatThrownBy(() -> controller.download(actor, 42L, 99L))
                .isInstanceOf(RuntimeException.class);
        assertThat(tracker.closed).isTrue();
    }

    private static byte[] readAll(InputStreamResource resource) {
        try (var in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal stream that records whether the controller closed it on the failure path. */
    private static final class CloseTrackingInputStream extends InputStream {
        boolean closed;

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
