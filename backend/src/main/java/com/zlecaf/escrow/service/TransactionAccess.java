package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
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

    private final UserRepository users;

    public TransactionAccess(UserRepository users) {
        this.users = users;
    }

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
        throw new ForbiddenException(ErrorCode.NOT_A_PARTY, "You are not a party to this transaction");
    }

    /**
     * Authorises a machine partner deposit: the company behind the inbound HMAC key
     * must be a party (buyer OR seller) to {@code tx}. A transaction is keyed on
     * {@code users.id}, not company ids, so each party's user is loaded and its
     * {@link User#getCompany()} compared to {@code companyId}. Centralised here so
     * partner and user access rules never diverge (anti-IDOR at company scope).
     *
     * @throws ForbiddenException if neither party's company matches {@code companyId} (403).
     */
    public void requireCompanyParticipant(EscrowTransaction tx, Long companyId) {
        if (companyId == null
                || (!belongsToCompany(tx.getBuyerId(), companyId)
                        && !belongsToCompany(tx.getSellerId(), companyId))) {
            throw new ForbiddenException(ErrorCode.NOT_A_PARTY,
                    "The partner company is not a party to this transaction");
        }
    }

    /** True iff {@code userId} resolves to a user whose company id equals {@code companyId} (null-safe). */
    private boolean belongsToCompany(Long userId, Long companyId) {
        return users.findById(userId)
                .map(User::getCompany)
                .map(Company::getId)
                .map(companyId::equals)
                .orElse(false);
    }
}
