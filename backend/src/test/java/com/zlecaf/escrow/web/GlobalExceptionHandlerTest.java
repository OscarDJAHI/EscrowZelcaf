package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.service.TransitionException;
import com.zlecaf.escrow.service.storage.EvidenceStorageException;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Locks the wire contract of the error envelope: every branch of
 * {@link GlobalExceptionHandler} serialises a non-null {@code code} alongside the
 * unchanged {@code timestamp/status/error/message}. Clients (AD-10) classify on
 * that code alone — the status is too coarse and the message is interpolated
 * prose no contract pins.
 *
 * <p>A throwing stub controller stands in for the real ones: the mapping under
 * test is exception → envelope, and routing an arbitrary exception through a real
 * endpoint would only add coupling to that endpoint's signature. Standalone
 * setup, per {@link EvidenceStorageErrorMappingTest} — no Spring context.
 */
class GlobalExceptionHandlerTest {

    /** Set per test; the stub rethrows it from a trivial GET. */
    private final AtomicReference<RuntimeException> toThrow = new AtomicReference<>();
    private MockMvc mvc;

    @RestController
    class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            throw toThrow.get();
        }
    }

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // --- The envelope contract itself ---

    @Test
    @DisplayName("The envelope is additive: code joins timestamp/status/error/message, none of which change")
    void envelopeIsAdditive() throws Exception {
        toThrow.set(new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction 42 not found"));

        mvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                // `error` stays the HTTP reason phrase; `code` is a different thing in
                // a different field. Conflating them is exactly the defect being fixed.
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Transaction 42 not found"));
    }

    @Test
    @DisplayName("A throw site with no explicit code still gets a non-null default from its type")
    void defaultCodePerType() throws Exception {
        // Map.of rejects a null value: a code-less branch would turn a clean 400 into
        // a 500. The per-type default is what makes the envelope total.
        toThrow.set(new BadRequestException("Buyer and seller must be different users"));

        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- Branch-by-branch codes ---

    @Test
    @DisplayName("404: an explicitly coded NotFoundException carries its code")
    void notFoundCarriesCode() throws Exception {
        toThrow.set(new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction 1 not found"));
        mvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("400: an unacceptable file is EVIDENCE_INVALID")
    void badRequestCarriesCode() throws Exception {
        toThrow.set(new BadRequestException(ErrorCode.EVIDENCE_INVALID, "Uploaded file is empty"));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INVALID"));
    }

    @Test
    @DisplayName("400: an unreadable upload is FILE_READ_ERROR — same status, different verdict")
    void sameStatusDifferentCode() throws Exception {
        // The proof that the code is not a function of the status: this 400 is
        // retryable and the one above is not.
        toThrow.set(new BadRequestException(ErrorCode.FILE_READ_ERROR, "Could not read the uploaded file"));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_READ_ERROR"));
    }

    @Test
    @DisplayName("409: a ConflictException carries its code")
    void conflictCarriesCode() throws Exception {
        toThrow.set(new ConflictException(ErrorCode.WINDOW_CLOSED,
                "Evidence cannot be deposited while the transaction is INITIATED"));
        mvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("403: a non-party is NOT_A_PARTY")
    void forbiddenCarriesCode() throws Exception {
        toThrow.set(new ForbiddenException(ErrorCode.NOT_A_PARTY, "You are not a party to this transaction"));
        mvc.perform(get("/boom"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_A_PARTY"));
    }

    @Test
    @DisplayName("401: a partner auth failure is the opaque AUTH_FAILED")
    void unauthorizedCarriesOpaqueCode() throws Exception {
        toThrow.set(new UnauthorizedException("Invalid partner credentials"));
        mvc.perform(get("/boom"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid partner credentials"));
    }

    @Test
    @DisplayName("502: a storage failure is STORAGE_UNAVAILABLE with the message unchanged")
    void storageFailureCarriesCode() throws Exception {
        toThrow.set(new EvidenceStorageException("42/abc", new RuntimeException("boom")));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Stockage de preuves indisponible"));
    }

    // --- The TransitionException status fork, now driven by the code ---

    @Test
    @DisplayName("403: only UNAUTHORIZED_TRANSITION forks the status to Forbidden")
    void unauthorizedTransitionIs403() throws Exception {
        toThrow.set(new TransitionException(ErrorCode.UNAUTHORIZED_TRANSITION,
                "Role SELLER is not authorised to trigger OPEN_DISPUTE from state SHIPPED"));
        mvc.perform(get("/boom"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_TRANSITION"));
    }

    @Test
    @DisplayName("409: every other lifecycle rejection stays a Conflict, each with its own code")
    void otherTransitionsAre409() throws Exception {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.ILLEGAL_TRANSITION, ErrorCode.TRANSACTION_TERMINAL,
                ErrorCode.DISPUTE_ALREADY_RESOLVED}) {
            toThrow.set(new TransitionException(code, "Event OPEN_DISPUTE is not permitted from state RELEASED"));
            mvc.perform(get("/boom"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    // --- The new optimistic-lock branch ---

    @Test
    @DisplayName("409: an optimistic-lock collision is a coded, retryable Conflict — no longer an uncoded 500")
    void optimisticLockIs409() throws Exception {
        // Previously this escaped the advice and surfaced as a bare 500, which a
        // client cannot distinguish from a genuine server fault: it would either
        // retry a real bug forever or drop a request that was merely unlucky.
        toThrow.set(new ObjectOptimisticLockingFailureException("EscrowTransaction", 42L));

        mvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message").value("The transaction was modified concurrently; please retry"));
    }

    @Test
    @DisplayName("The optimistic-lock envelope leaks no entity, SQL or class detail")
    void optimisticLockLeaksNothing() throws Exception {
        toThrow.set(new ObjectOptimisticLockingFailureException("EscrowTransaction", 42L));
        mvc.perform(get("/boom"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("EscrowTransaction"))));
    }
}
