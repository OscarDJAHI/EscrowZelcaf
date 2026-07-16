package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EvidenceService;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Web-layer proof of {@code POST /{id}/evidence/{evidenceId}/withdraw} without a
 * database or security context: the {@link EvidenceController} is stood up with a
 * mocked {@link EvidenceService} and the {@link GlobalExceptionHandler} advice,
 * and {@code @AuthenticationPrincipal} is resolved to a fixed actor. Covers what
 * only the HTTP surface can prove — the EvidenceDto serialization and the
 * service-exception → status mapping (200/403/404/409).
 */
class EvidenceControllerWithdrawTest {

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
    @DisplayName("200: a successful withdrawal returns the updated EvidenceDto (status WITHDRAWN)")
    void withdrawReturns200WithBody() throws Exception {
        when(service.withdraw(any(), eq(42L), eq(99L))).thenReturn(stubWithdrawn());

        mvc.perform(post("/api/v1/escrow/{id}/evidence/{eid}/withdraw", 42L, 99L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("403: a ForbiddenException (not the owner / not a party) maps to 403")
    void forbiddenMapsTo403() throws Exception {
        when(service.withdraw(any(), any(), any()))
                .thenThrow(new ForbiddenException("You can only withdraw your own evidence"));

        mvc.perform(post("/api/v1/escrow/{id}/evidence/{eid}/withdraw", 42L, 99L))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("404: a NotFoundException (unknown transaction or piece) maps to 404")
    void notFoundMapsTo404() throws Exception {
        when(service.withdraw(any(), any(), any()))
                .thenThrow(new NotFoundException("Evidence 99 not found"));

        mvc.perform(post("/api/v1/escrow/{id}/evidence/{eid}/withdraw", 42L, 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("409: a ConflictException (closed window / already withdrawn / floor) maps to 409")
    void conflictMapsTo409() throws Exception {
        when(service.withdraw(any(), any(), any()))
                .thenThrow(new ConflictException("Withdrawal would leave the dispute without evidence"));

        mvc.perform(post("/api/v1/escrow/{id}/evidence/{eid}/withdraw", 42L, 99L))
                .andExpect(status().isConflict());
    }

    // --- helpers ---

    private static EvidenceDto stubWithdrawn() {
        return new EvidenceDto(99L, 42L, 7L, UploaderType.BUYER, "receipt.pdf",
                "application/pdf", 1234L, "proof", EvidenceStatus.WITHDRAWN,
                Instant.parse("2026-07-15T10:00:00Z"),
                Instant.parse("2026-07-15T10:05:00Z"), 7L);
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
