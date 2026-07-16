package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct unit proof of the partner anti-IDOR gate ({@link TransactionAccess#requireCompanyParticipant}),
 * the single company-scope membership authority for Story 3.2. Every partner deposit passes through it,
 * yet it was previously exercised only indirectly through a mocked {@code EvidenceService}; this pins its
 * allow/deny branches (buyer, seller, neither, null company, unresolved user) so a future refactor cannot
 * silently loosen the gate without turning CI red.
 */
class TransactionAccessTest {

    private static final Long BUYER_USER_ID = 10L;
    private static final Long SELLER_USER_ID = 20L;
    private static final Long BUYER_COMPANY_ID = 100L;
    private static final Long SELLER_COMPANY_ID = 200L;

    private final UserRepository users = mock(UserRepository.class);
    private final TransactionAccess access = new TransactionAccess(users);

    private static EscrowTransaction tx() {
        EscrowTransaction tx = new EscrowTransaction();
        tx.setBuyerId(BUYER_USER_ID);
        tx.setSellerId(SELLER_USER_ID);
        return tx;
    }

    private static User userWithCompany(Long companyId) {
        User u = new User();
        if (companyId != null) {
            Company c = new Company();
            c.setId(companyId);
            u.setCompany(c);
        }
        return u;
    }

    @Test
    @DisplayName("The buyer's company is a party -> allowed")
    void buyerCompanyAllowed() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.of(userWithCompany(BUYER_COMPANY_ID)));

        assertThatCode(() -> access.requireCompanyParticipant(tx(), BUYER_COMPANY_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The seller's company is a party (buyer checked first, then seller) -> allowed")
    void sellerCompanyAllowed() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.of(userWithCompany(BUYER_COMPANY_ID)));
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.of(userWithCompany(SELLER_COMPANY_ID)));

        assertThatCode(() -> access.requireCompanyParticipant(tx(), SELLER_COMPANY_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A company that is neither buyer nor seller -> 403")
    void unrelatedCompanyForbidden() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.of(userWithCompany(BUYER_COMPANY_ID)));
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.of(userWithCompany(SELLER_COMPANY_ID)));

        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), 999L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("A null company id is rejected before any user lookup -> 403")
    void nullCompanyForbidden() {
        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("A party user with no company fails closed -> 403")
    void partyUserWithoutCompanyForbidden() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.of(userWithCompany(null)));
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.of(userWithCompany(null)));

        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), BUYER_COMPANY_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("An unresolved party user fails closed -> 403")
    void unresolvedPartyUserForbidden() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.empty());
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), BUYER_COMPANY_ID))
                .isInstanceOf(ForbiddenException.class);
    }
}
