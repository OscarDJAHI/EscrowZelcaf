package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EvidenceService;
import com.zlecaf.escrow.service.scan.MalwareScanUnavailableException;
import com.zlecaf.escrow.service.storage.EvidenceStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Web-layer proof that a storage-neutral {@link EvidenceStorageException} raised
 * by the service maps to a {@code 502 Bad Gateway} in the standard error
 * envelope, with a fixed neutral message and no SDK detail leaked. The controller
 * is stood up with a mocked {@link EvidenceService} and the
 * {@link GlobalExceptionHandler} advice — the only thing under test is the
 * exception → status mapping.
 */
class EvidenceStorageErrorMappingTest {

    private static final AuthPrincipal ACTOR = new AuthPrincipal(7L, "party@example.com", Role.BUYER);

    private EvidenceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(EvidenceService.class);
        mvc = standaloneSetup(new EvidenceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(fixedPrincipal(ACTOR))
                .build();
    }

    @Test
    @DisplayName("502: an EvidenceStorageException on download maps to a 502 envelope with a neutral message")
    void storageFailureMapsTo502() throws Exception {
        when(service.download(any(), any(), any()))
                .thenThrow(new EvidenceStorageException("42/abc", new RuntimeException("boom")));

        mvc.perform(get("/api/v1/escrow/42/evidence/99/download"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                // The classifiable token: `error` is a reason phrase, `code` is the
                // contract a client may branch on, and a storage outage is retryable.
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Stockage de preuves indisponible"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("502: a MalwareScanUnavailableException on deposit maps to a 502 SCAN_UNAVAILABLE envelope, cause not leaked")
    void scanUnavailableMapsTo502() throws Exception {
        // Symétrie avec le stockage (Story 1.8) : l'autre dépendance sortante du
        // dépôt. Le cas est volontairement prouvé sur la route de DÉPÔT, la seule où
        // l'analyse a lieu.
        when(service.deposit(any(), any(), any(), any(), any()))
                .thenThrow(new MalwareScanUnavailableException(
                        "échec de dialogue avec clamd av-host:3310 (ConnectException)",
                        new java.net.ConnectException("Connection refused")));

        mvc.perform(multipart("/api/v1/escrow/42/evidence")
                        .file(new MockMultipartFile("files", "proof.pdf", "application/pdf", "%PDF-1.4".getBytes())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                // TRANSIENT : la file offline doit rejouer, pas geler la preuve.
                .andExpect(jsonPath("$.code").value("SCAN_UNAVAILABLE"))
                // Message FIXE : ni l'hôte, ni le port, ni la cause technique ne
                // franchissent la frontière HTTP.
                .andExpect(jsonPath("$.message").value("Analyse antivirus indisponible"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("clamd"))))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /** Resolves {@code @AuthenticationPrincipal AuthPrincipal} to a fixed actor
     *  so the slice needs no security context. */
    private static HandlerMethodArgumentResolver fixedPrincipal(AuthPrincipal principal) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return AuthPrincipal.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return principal;
            }
        };
    }
}
