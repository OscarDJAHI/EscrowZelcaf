package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.domain.WebhookSubscription;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.repository.WebhookSubscriptionRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.dto.WebhookDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Partner self-service for outbound webhook subscriptions. Partners register a
 * callback URL and a shared secret; every matching state change is then
 * delivered to them signed with HMAC-SHA256 (see {@code WebhookService}).
 *
 * <p><b>Tenant scoping (rétrospective Epic 1, 2026-07-27).</b> Les deux routes
 * étaient ouvertes à travers les sociétés : {@code list()} appelait
 * {@code findAll()}, si bien que tout porteur d'un JWT lisait l'URL de rappel de
 * <em>toutes</em> les sociétés ; et {@code create()} prenait le {@code companyId}
 * dans le <em>corps</em> de la requête, si bien que le même appelant pouvait
 * s'abonner aux événements d'une société tierce vers sa propre URL. La seconde
 * est la plus grave : c'est un canal d'exfiltration, pas une simple fuite de
 * configuration.
 *
 * <p>La société n'est donc plus une donnée d'entrée : elle est <b>dérivée du JWT</b>,
 * comme partout ailleurs dans le dépôt. Le champ a été retiré de
 * {@code SubscriptionRequest} plutôt que gardé — un champ qu'on valide reste un
 * champ qu'on peut oublier de valider au prochain endpoint, alors qu'un champ
 * absent ne se contourne pas.
 *
 * <p>Cette classe parle encore au repository sans service intermédiaire (héritage
 * POC). C'est précisément ce court-circuit qui a laissé passer le défaut : la
 * garde de société n'existe nulle part côté service parce qu'aucun service n'est
 * traversé. Le passage à un {@code WebhookSubscriptionService} est porté au
 * ledger — hors périmètre d'un correctif de rétrospective.
 */
@RestController
@RequestMapping("/api/v1/webhooks/subscriptions")
public class WebhookController {

    private final WebhookSubscriptionRepository subscriptions;
    private final UserRepository users;

    public WebhookController(WebhookSubscriptionRepository subscriptions, UserRepository users) {
        this.subscriptions = subscriptions;
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<SubscriptionDto> create(@AuthenticationPrincipal AuthPrincipal actor,
                                                  @Valid @RequestBody SubscriptionRequest request) {
        Long companyId = callerCompanyId(actor);
        if (companyId == null) {
            // Refus honnête, et il le reste au sens de la convention anti-énumération
            // (Story 1.10) : il porte sur l'identité PROPRE de l'appelant, résolue
            // depuis son propre JWT. Il ne peut donc pas servir à sonder l'existence
            // d'une ressource d'autrui — le message est constant et ne nomme rien.
            throw new BadRequestException("Subscriber must belong to a company");
        }
        WebhookSubscription sub = new WebhookSubscription();
        sub.setCompanyId(companyId);
        sub.setTargetUrl(request.targetUrl());
        sub.setSecretKey(request.secretKey());
        sub.setEventType(request.eventType().toUpperCase());
        sub.setActive(true);
        sub = subscriptions.save(sub);
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionDto.from(sub));
    }

    @GetMapping
    public List<SubscriptionDto> list(@AuthenticationPrincipal AuthPrincipal actor) {
        Long companyId = callerCompanyId(actor);
        if (companyId == null) {
            // Liste vide plutôt qu'un refus : il n'y a rien à cacher et rien à révéler,
            // et un 4xx ici distinguerait « sans société » de « société sans abonnement »
            // sans aucun gain.
            return List.of();
        }
        return subscriptions.findByCompanyId(companyId).stream().map(SubscriptionDto::from).toList();
    }

    /**
     * La société de l'appelant, ou {@code null} s'il n'en a pas — y compris quand le
     * JWT est valide mais que la ligne utilisateur a disparu depuis son émission.
     * Ce cas rend {@code null} au lieu de lever : un jeton dont le porteur n'existe
     * plus n'est pas un oracle d'existence de ressource, et lever ici ajouterait un
     * site de construction directe de refus là où la convention n'en veut pas.
     */
    private Long callerCompanyId(AuthPrincipal actor) {
        User user = users.findById(actor.userId()).orElse(null);
        if (user == null) {
            return null;
        }
        Company company = user.getCompany();
        return company == null ? null : company.getId();
    }
}
