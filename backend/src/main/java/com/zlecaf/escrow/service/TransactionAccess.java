package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import org.springframework.stereotype.Component;

/**
 * Single, reusable transaction-membership check. Every evidence endpoint (this
 * epic and epics 2-4) loads the transaction from the URL and resolves the
 * acting principal's role here — two endpoints with divergent access rules
 * would be a defect. Extracted from {@code EscrowService} without any change to
 * observable behaviour.
 */
@Component
public class TransactionAccess {

    /**
     * Resolves the capacity in which {@code actor} relates to {@code tx}.
     *
     * @return {@link ParticipantRole#ADMIN} for platform admins, otherwise
     *         {@code BUYER}/{@code SELLER} derived from the transaction parties.
     * @throws ForbiddenException if the actor is neither an admin nor a party.
     */
    public ParticipantRole resolveRole(AuthPrincipal actor, EscrowTransaction tx) {
        if (actor.role() == Role.ADMIN) {
            return ParticipantRole.ADMIN;
        }
        if (actor.userId().equals(tx.getBuyerId())) {
            return ParticipantRole.BUYER;
        }
        if (actor.userId().equals(tx.getSellerId())) {
            return ParticipantRole.SELLER;
        }
        throw new ForbiddenException("You are not a party to this transaction");
    }
}
