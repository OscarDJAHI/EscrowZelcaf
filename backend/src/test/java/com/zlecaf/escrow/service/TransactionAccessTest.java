package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
 *
 * <p><strong>Depuis la Story 1.10, chaque refus est un 404 et non un 403</strong>
 * ({@code ApiExceptions.transactionNotFound()}) : le canal partenaire ne doit pas
 * plus que le canal humain confirmer l'existence d'une transaction a une societe
 * qui n'y est pas partie. C'est le meme type d'exception, et le meme message, qu'un
 * identifiant inconnu — les deux reponses sont indistinguables par construction.
 *
 * <p><strong>Le TYPE d'exception ne suffit pas a asservir la propriete.</strong> Un
 * futur correctif qui remettrait {@code new NotFoundException("Transaction " + id +
 * " not found")} a un site de jet laisserait vertes toutes les assertions
 * {@code isInstanceOf(NotFoundException.class)} du depot tout en rouvrant l'oracle
 * par le message — c'est exactement la derive que la fabrique unique existe pour
 * empecher. Le {@code code} et le {@code message} litteral sont donc asservis ici,
 * sur les DEUX gardes. C'est le seul endroit ou ils le sont sans Docker : la
 * propriete de bout en bout vit dans {@code AntiEnumerationIntegrationTest}, qui
 * exige un conteneur et ne tourne donc pas sur un poste sans Docker.
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
    @DisplayName("A company that is neither buyer nor seller -> 404 (opaque)")
    void unrelatedCompanyIsOpaque404() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.of(userWithCompany(BUYER_COMPANY_ID)));
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.of(userWithCompany(SELLER_COMPANY_ID)));

        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), 999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("A null company id is rejected before any user lookup -> 404 (opaque)")
    void nullCompanyIsOpaque404() {
        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("A party user with no company fails closed -> 404 (opaque)")
    void partyUserWithoutCompanyIsOpaque404() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.of(userWithCompany(null)));
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.of(userWithCompany(null)));

        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), BUYER_COMPANY_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("An unresolved party user fails closed -> 404 (opaque)")
    void unresolvedPartyUserIsOpaque404() {
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.empty());
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), BUYER_COMPANY_ID))
                .isInstanceOf(NotFoundException.class);
    }

    // --- L'autre garde, cote JWT : resolveRole -----------------------------------

    @Test
    @DisplayName("The buyer and the seller resolve to their role; an admin bypasses membership")
    void partiesAndAdminResolve() {
        assertThat(access.resolveRole(new AuthPrincipal(BUYER_USER_ID, "b@x.test", Role.BUYER), tx()))
                .isEqualTo(ParticipantRole.BUYER);
        assertThat(access.resolveRole(new AuthPrincipal(SELLER_USER_ID, "s@x.test", Role.SELLER), tx()))
                .isEqualTo(ParticipantRole.SELLER);
        assertThat(access.resolveRole(new AuthPrincipal(999L, "admin@x.test", Role.ADMIN), tx()))
                .isEqualTo(ParticipantRole.ADMIN);
    }

    @Test
    @DisplayName("A stranger to the transaction gets the SAME code and the SAME literal message as an unknown id")
    void strangerGetsTheOpaqueTransactionNotFound() {
        assertThatThrownBy(() ->
                access.resolveRole(new AuthPrincipal(999L, "c@x.test", Role.BUYER), tx()))
                .isInstanceOf(NotFoundException.class)
                // Le `code` est un oracle au meme rang que le statut (AD-10) : le client
                // classe dessus, donc un attaquant le lit aussi. Un 404 uniforme portant
                // deux codes distincts n'aurait rien ferme.
                .extracting(e -> ((NotFoundException) e).getCode())
                .isEqualTo(ErrorCode.TRANSACTION_NOT_FOUND);

        assertThatThrownBy(() ->
                access.resolveRole(new AuthPrincipal(999L, "c@x.test", Role.BUYER), tx()))
                // Egalite LITTERALE : c'est elle qui interdit le retour d'un
                // "Transaction 42 not found" — meme code, meme statut, mais l'identifiant
                // de retour dans le texte suffit a rouvrir l'oracle.
                .hasMessage("Transaction not found");
    }

    @Test
    @DisplayName("The partner guard answers with the very same code and message — one property, two channels")
    void partnerRefusalIsIdenticalToTheUserOne() {
        // Ce que la story revendique n'est pas « les deux levent une NotFoundException »
        // mais « les deux reponses sont indistinguables ». Deux fabriques divergentes
        // satisferaient la premiere formulation et casseraient la seconde en silence —
        // le defaut deja paye sur le message d'auth partenaire (deferred-work.md).
        when(users.findById(BUYER_USER_ID)).thenReturn(Optional.of(userWithCompany(BUYER_COMPANY_ID)));
        when(users.findById(SELLER_USER_ID)).thenReturn(Optional.of(userWithCompany(SELLER_COMPANY_ID)));

        assertThatThrownBy(() -> access.requireCompanyParticipant(tx(), 999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Transaction not found")
                .extracting(e -> ((NotFoundException) e).getCode())
                .isEqualTo(ErrorCode.TRANSACTION_NOT_FOUND);
    }
}
