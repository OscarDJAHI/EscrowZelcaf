package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EscrowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.zlecaf.escrow.domain.EscrowEvent;

import java.util.List;

import static com.zlecaf.escrow.domain.EscrowEvent.*;
import static com.zlecaf.escrow.domain.EscrowState.*;
import static com.zlecaf.escrow.domain.ParticipantRole.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EscrowStateMachineTest {

    private final EscrowStateMachine sm = new EscrowStateMachine();

    // --- Happy-path transitions from the specification matrix ---

    @Test
    @DisplayName("Buyer pays: INITIATED -> FUNDS_LOCKED")
    void buyerPaysLocksFunds() {
        assertThat(sm.determineNextState(INITIATED, PAY_FUNDS, BUYER)).isEqualTo(FUNDS_LOCKED);
    }

    @Test
    @DisplayName("Seller ships: FUNDS_LOCKED -> SHIPPED")
    void sellerShips() {
        assertThat(sm.determineNextState(FUNDS_LOCKED, SHIP_GOODS, SELLER)).isEqualTo(SHIPPED);
    }

    @Test
    @DisplayName("Buyer confirms delivery: SHIPPED -> RELEASED")
    void buyerConfirmsDelivery() {
        assertThat(sm.determineNextState(SHIPPED, DELIVERY_CONFIRMED, BUYER)).isEqualTo(RELEASED);
    }

    @Test
    @DisplayName("Dispute can be opened before and after shipping")
    void disputesOpenable() {
        assertThat(sm.determineNextState(FUNDS_LOCKED, OPEN_DISPUTE, BUYER)).isEqualTo(DISPUTED);
        assertThat(sm.determineNextState(FUNDS_LOCKED, OPEN_DISPUTE, SELLER)).isEqualTo(DISPUTED);
        assertThat(sm.determineNextState(SHIPPED, OPEN_DISPUTE, BUYER)).isEqualTo(DISPUTED);
    }

    @Test
    @DisplayName("Admin arbitration resolves a dispute either way")
    void adminResolvesDispute() {
        assertThat(sm.determineNextState(DISPUTED, RESOLVE_RELEASE, ADMIN)).isEqualTo(RELEASED);
        assertThat(sm.determineNextState(DISPUTED, RESOLVE_REFUND, ADMIN)).isEqualTo(REFUNDED);
    }

    // --- Authorisation enforcement ---

    @Test
    @DisplayName("Seller may not confirm delivery on the buyer's behalf")
    void sellerCannotConfirmDelivery() {
        assertThatThrownBy(() -> sm.determineNextState(SHIPPED, DELIVERY_CONFIRMED, SELLER))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getReason())
                        .isEqualTo(TransitionException.Reason.UNAUTHORIZED));
    }

    @Test
    @DisplayName("A party cannot self-arbitrate a dispute (admin only)")
    void partyCannotResolveDispute() {
        assertThatThrownBy(() -> sm.determineNextState(DISPUTED, RESOLVE_RELEASE, SELLER))
                .isInstanceOf(TransitionException.class);
        assertThatThrownBy(() -> sm.determineNextState(DISPUTED, RESOLVE_REFUND, BUYER))
                .isInstanceOf(TransitionException.class);
    }

    @Test
    @DisplayName("Seller cannot open a dispute after shipping (only the buyer can)")
    void sellerCannotDisputeAfterShipping() {
        assertThatThrownBy(() -> sm.determineNextState(SHIPPED, OPEN_DISPUTE, SELLER))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getReason())
                        .isEqualTo(TransitionException.Reason.UNAUTHORIZED));
    }

    // --- Illegal transitions ---

    @Test
    @DisplayName("Cannot ship before funds are locked")
    void cannotShipBeforeFunding() {
        assertThatThrownBy(() -> sm.determineNextState(INITIATED, SHIP_GOODS, SELLER))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getReason())
                        .isEqualTo(TransitionException.Reason.ILLEGAL_TRANSITION));
    }

    @ParameterizedTest
    @EnumSource(value = EscrowState.class, names = {"RELEASED", "REFUNDED"})
    @DisplayName("Terminal states admit no further transitions")
    void terminalStatesAreFrozen(EscrowState terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThatThrownBy(() -> sm.determineNextState(terminal, PAY_FUNDS, ADMIN))
                .isInstanceOf(TransitionException.class);
        assertThatThrownBy(() -> sm.determineNextState(terminal, OPEN_DISPUTE, BUYER))
                .isInstanceOf(TransitionException.class);
    }

    // --- allowedEvents (drives the UI) ---

    @Test
    @DisplayName("allowedEvents reflects role and state")
    void allowedEventsPerRole() {
        assertThat(sm.allowedEvents(FUNDS_LOCKED, SELLER)).containsExactlyInAnyOrder(SHIP_GOODS, OPEN_DISPUTE);
        assertThat(sm.allowedEvents(FUNDS_LOCKED, BUYER)).containsExactlyInAnyOrder(OPEN_DISPUTE);
        assertThat(sm.allowedEvents(DISPUTED, ADMIN)).containsExactlyInAnyOrder(RESOLVE_RELEASE, RESOLVE_REFUND);
        assertThat(sm.allowedEvents(RELEASED, ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("A buyer at FUNDS_LOCKED has no shipping power")
    void buyerHasNoShipEvent() {
        List<EscrowEvent> events = sm.allowedEvents(FUNDS_LOCKED, BUYER);
        assertThat(events).doesNotContain(SHIP_GOODS);
    }
}
