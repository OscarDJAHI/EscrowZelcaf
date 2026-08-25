package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.web.ApiExceptions;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Single, reusable transaction-membership check. Every evidence endpoint (this
 * epic and epics 2-4) loads the transaction from the URL and resolves the
 * acting principal's role here — two endpoints with divergent access rules
 * would be a defect. Extracted from {@code EscrowService} without any change to
 * observable behaviour.
 *
 * <p><strong>Anti-enumeration (Story 1.10, NFR-P9).</strong> Both methods below
 * refuse a non-party with {@code ApiExceptions.transactionNotFound()} — the very
 * same 404 an unknown id produces — and they are the <em>only</em> two throw sites
 * for a membership refusal on the whole escrow surface. That is what makes the
 * property hold on all eight endpoints at once (seven user routes plus the partner
 * deposit), and what makes it hold on the partner (HMAC) channel as well as on the
 * human (JWT) one: an oracle left open on either channel is an oracle.
 *
 * <p><strong>The claim is bounded to what the caller reads, not to how long they
 * wait.</strong> The two answers are equal in status, {@code code}, {@code message}
 * and headers; they are <em>not</em> equal in cost. The non-party path has already
 * loaded the transaction and still has to resolve a role — plus, in
 * {@link #requireCompanyParticipant}, up to two {@code users.findById} that the
 * unknown-id path never reaches. And because callers load the row through
 * {@code findByIdForUpdate}, two concurrent probes on a <b>real</b> id serialise on
 * a {@code PESSIMISTIC_WRITE} lock while probes on a fictitious id never contend —
 * an attacker can therefore provoke the difference instead of waiting to measure
 * it. Closing that is a platform-level decision (equalise the work, or floor the
 * latency), explicitly out of this story's scope and carried by the "canal
 * temporel" entry of {@code deferred-work.md}.
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
     * @throws NotFoundException if the actor is neither an admin nor a party — the
     *         identical 404 an unknown id yields. Not a lie: a caller with no right
     *         to this transaction has no right to learn that it exists either
     *         (Story 1.10). A 403 here would say "it exists and is denied to you",
     *         which is false half the time and an oracle the other half.
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
        throw ApiExceptions.transactionNotFound();
    }

    /**
     * Authorises a machine partner deposit: the company behind the inbound HMAC key
     * must be a party (buyer OR seller) to {@code tx}. A transaction is keyed on
     * {@code users.id}, not company ids, so each party's user is loaded and its
     * {@link User#getCompany()} compared to {@code companyId}. Centralised here so
     * partner and user access rules never diverge (anti-IDOR at company scope).
     *
     * @throws NotFoundException if neither party's company matches {@code companyId}
     *         (404, Story 1.10) — byte for byte the answer an unknown transaction id
     *         gives. The partner channel is authenticated but not trusted: a valid
     *         signature buys the right to act on one's own transactions, never the
     *         right to map the platform's id space.
     */
    public void requireCompanyParticipant(EscrowTransaction tx, Long companyId) {
        if (companyId == null
                || (!belongsToCompany(tx.getBuyerId(), companyId)
                        && !belongsToCompany(tx.getSellerId(), companyId))) {
            throw ApiExceptions.transactionNotFound();
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
