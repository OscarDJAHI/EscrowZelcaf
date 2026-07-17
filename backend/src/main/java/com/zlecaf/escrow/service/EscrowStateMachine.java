package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.domain.EscrowEvent;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.ParticipantRole;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zlecaf.escrow.domain.EscrowEvent.*;
import static com.zlecaf.escrow.domain.EscrowState.*;
import static com.zlecaf.escrow.domain.ParticipantRole.*;

/**
 * The pure, side-effect-free heart of the escrow engine. Encodes the transition
 * matrix from the backend specification and enforces, in one place, both the
 * legality of a (state, event) pair and the role authorised to trigger it.
 *
 * <p>Being pure and stateless, it is trivially unit-testable and is the single
 * source of truth for what may happen to escrowed funds.
 */
@Component
public class EscrowStateMachine {

    /** A permitted transition: where it leads and who may trigger it. */
    private record Transition(EscrowState target, Set<ParticipantRole> allowedRoles) {}

    /** Composite key identifying a transition entry. */
    private record Key(EscrowState from, EscrowEvent event) {}

    /**
     * The transition matrix. Any (state, event) pair absent from this map is, by
     * construction, an illegal transition — a whitelist, never a blacklist.
     */
    private static final Map<Key, Transition> MATRIX = Map.of(
        new Key(INITIATED, PAY_FUNDS),
            new Transition(FUNDS_LOCKED, Set.of(BUYER, ADMIN)),
        new Key(FUNDS_LOCKED, SHIP_GOODS),
            new Transition(SHIPPED, Set.of(SELLER)),
        new Key(FUNDS_LOCKED, OPEN_DISPUTE),
            new Transition(DISPUTED, Set.of(BUYER, SELLER)),
        new Key(SHIPPED, DELIVERY_CONFIRMED),
            new Transition(RELEASED, Set.of(BUYER, ADMIN)),
        new Key(SHIPPED, OPEN_DISPUTE),
            new Transition(DISPUTED, Set.of(BUYER)),
        new Key(DISPUTED, RESOLVE_RELEASE),
            new Transition(RELEASED, Set.of(ADMIN)),
        new Key(DISPUTED, RESOLVE_REFUND),
            new Transition(REFUNDED, Set.of(ADMIN))
    );

    /**
     * Computes the next state for an event, enforcing legality and authorisation.
     *
     * @throws TransitionException with {@code TRANSACTION_TERMINAL} if {@code current}
     *         is terminal, {@code ILLEGAL_TRANSITION} if the event is merely not valid
     *         from a still-live {@code current}, or {@code UNAUTHORIZED_TRANSITION} if
     *         it is valid but the {@code actor} role may not trigger it.
     */
    public EscrowState determineNextState(EscrowState current, EscrowEvent event, ParticipantRole actor) {
        Transition transition = MATRIX.get(new Key(current, event));
        if (transition == null) {
            // A whitelist miss lumps two very different verdicts together: "not yet /
            // not from here" (the caller may reach a state where it works) and "this
            // transaction is over" (nothing will ever work again). Fork them on
            // terminality — a pure function of `current`, so this component keeps its
            // zero dependencies. Messages are unchanged.
            throw new TransitionException(
                current.isTerminal() ? ErrorCode.TRANSACTION_TERMINAL : ErrorCode.ILLEGAL_TRANSITION,
                "Event %s is not permitted from state %s".formatted(event, current));
        }
        if (!transition.allowedRoles().contains(actor)) {
            throw new TransitionException(
                ErrorCode.UNAUTHORIZED_TRANSITION,
                "Role %s is not authorised to trigger %s from state %s".formatted(actor, event, current));
        }
        return transition.target();
    }

    /**
     * The events the given actor may currently trigger from {@code current}.
     * Used to drive the UI (which action buttons to show) without duplicating
     * the matrix on the client.
     */
    public List<EscrowEvent> allowedEvents(EscrowState current, ParticipantRole actor) {
        return MATRIX.entrySet().stream()
            .filter(e -> e.getKey().from() == current)
            .filter(e -> e.getValue().allowedRoles().contains(actor))
            .map(e -> e.getKey().event())
            .toList();
    }
}
