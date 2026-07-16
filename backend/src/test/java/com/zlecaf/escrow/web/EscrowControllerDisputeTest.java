package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EscrowService;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EscrowDtos.DisputeOpenedDto;
import com.zlecaf.escrow.web.dto.EscrowDtos.TransactionDto;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
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

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Web-layer proof of {@code POST /{id}/dispute} without a database or security
 * context: the controller is stood up with a mocked {@link EscrowService} and
 * the {@link GlobalExceptionHandler} advice, and {@code @AuthenticationPrincipal}
 * is resolved to a fixed actor. Covers what only the HTTP surface can prove —
 * multipart binding (required {@code files}/{@code comment}, optional
 * {@code clientCapturedAt}), the DisputeOpenedDto serialization, and the
 * service-exception → status mapping (200/400/403/404/409).
 */
class EscrowControllerDisputeTest {

    private static final AuthPrincipal ACTOR = new AuthPrincipal(7L, "party@example.com", Role.BUYER);
    private static final String COMMENT = "the item never arrived";

    private EscrowService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(EscrowService.class);
        mvc = standaloneSetup(new EscrowController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(fixedPrincipal(ACTOR))
                .build();
    }

    @Test
    @DisplayName("200: a full multipart request returns the DisputeOpenedDto (transaction DISPUTED + one evidence)")
    void fullRequestReturns200WithBody() throws Exception {
        when(service.openDispute(any(), eq(42L), anyList(), eq(COMMENT), any()))
                .thenReturn(stubResponse());

        mvc.perform(multipart("/api/v1/escrow/42/dispute").file(pdf()).param("comment", COMMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.state").value("DISPUTED"))
                .andExpect(jsonPath("$.evidence", hasSize(1)))
                .andExpect(jsonPath("$.evidence[0].id").value(99));
    }

    @Test
    @DisplayName("400: a missing 'files' part is a clean bad request (never a 500)")
    void missingFilesPartIs400() throws Exception {
        mvc.perform(multipart("/api/v1/escrow/42/dispute").param("comment", COMMENT))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400: a missing 'comment' param is a clean bad request")
    void missingCommentParamIs400() throws Exception {
        mvc.perform(multipart("/api/v1/escrow/42/dispute").file(pdf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("clientCapturedAt is optional: absent still reaches the service and returns 200")
    void clientCapturedAtIsOptional() throws Exception {
        when(service.openDispute(any(), eq(42L), anyList(), eq(COMMENT), isNull()))
                .thenReturn(stubResponse());

        mvc.perform(multipart("/api/v1/escrow/42/dispute").file(pdf()).param("comment", COMMENT))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("409: a ConflictException (state not openable) maps to 409")
    void conflictMapsTo409() throws Exception {
        when(service.openDispute(any(), any(), anyList(), any(), any()))
                .thenThrow(new ConflictException("not openable in this state"));

        mvc.perform(multipart("/api/v1/escrow/42/dispute").file(pdf()).param("comment", COMMENT))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("404: a NotFoundException (unknown transaction) maps to 404")
    void notFoundMapsTo404() throws Exception {
        when(service.openDispute(any(), any(), anyList(), any(), any()))
                .thenThrow(new NotFoundException("Transaction 42 not found"));

        mvc.perform(multipart("/api/v1/escrow/42/dispute").file(pdf()).param("comment", COMMENT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("403: a ForbiddenException (not a party) maps to 403")
    void forbiddenMapsTo403() throws Exception {
        when(service.openDispute(any(), any(), anyList(), any(), any()))
                .thenThrow(new ForbiddenException("not a party to this transaction"));

        mvc.perform(multipart("/api/v1/escrow/42/dispute").file(pdf()).param("comment", COMMENT))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("files", "receipt.pdf", "application/pdf", "pdf-bytes".getBytes());
    }

    private static DisputeOpenedDto stubResponse() {
        TransactionDto tx = new TransactionDto(42L, 7L, 8L, "buyer@e.com", "seller@e.com",
                new BigDecimal("100.00"), "USD", EscrowState.DISPUTED, "desc", null, null);
        EvidenceDto evidence = new EvidenceDto(99L, 42L, 7L, UploaderType.BUYER, "receipt.pdf",
                "application/pdf", 1234L, COMMENT, EvidenceStatus.ACTIVE, null, null, null);
        return new DisputeOpenedDto(tx, List.of(evidence));
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
