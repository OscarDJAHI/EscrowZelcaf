package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.EmailVerificationCode;
import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.domain.LegalConsent;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.CompanyRepository;
import com.zlecaf.escrow.repository.EmailVerificationCodeRepository;
import com.zlecaf.escrow.repository.LegalConsentRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.service.notification.EmailVerificationSender;
import com.zlecaf.escrow.web.ApiExceptions;
import com.zlecaf.escrow.security.JwtService;
import com.zlecaf.escrow.security.PasswordPolicy;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.AuthDtos.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AuthService {

    /** Durée de vie du code. Assez court pour qu'un code intercepté périme vite, assez long
     *  pour survivre à un e-mail lent sur un réseau mobile africain. */
    private static final Duration CODE_TTL = Duration.ofMinutes(15);

    /** Délai minimal entre deux envois — alimente le compte à rebours affiché (UX-DR21). */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** Plafond d'envois par fenêtre, et la fenêtre. Porté par le DESTINATAIRE : voir sendCode. */
    private static final int MAX_SENDS_PER_WINDOW = 5;
    private static final Duration SEND_WINDOW = Duration.ofHours(1);

    /** Tentatives de vérification avant invalidation du code (AC3). */
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    /** Version des documents légaux par défaut, quand le client n'en déclare pas. */
    private static final String DEFAULT_CONSENT_VERSION = "v1";

    /**
     * Hash bcrypt jetable, comparé quand aucun code réel n'existe.
     *
     * <p>Sans lui, « adresse inconnue » répondrait en une microseconde là où « mauvais
     * code » coûte un bcrypt complet. Le code d'erreur a beau être identique, le
     * CHRONOMÈTRE rouvrirait l'oracle que tout le reste ferme. Le hash est calculé une
     * fois au démarrage sur une valeur qui n'est le code de personne.
     */
    private final String decoyHash;

    private final SecureRandom random = new SecureRandom();

    private final UserRepository users;
    private final CompanyRepository companies;
    private final EmailVerificationCodeRepository codes;
    private final LegalConsentRepository consents;
    private final EmailVerificationSender sender;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final Clock clock;

    public AuthService(UserRepository users, CompanyRepository companies,
                       EmailVerificationCodeRepository codes, LegalConsentRepository consents,
                       EmailVerificationSender sender, PasswordEncoder passwordEncoder,
                       JwtService jwtService, PasswordPolicy passwordPolicy, AuditService audit,
                       Clock clock) {
        this.users = users;
        this.companies = companies;
        this.codes = codes;
        this.consents = consents;
        this.sender = sender;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode("decoy-" + java.util.UUID.randomUUID());
    }

    /**
     * Inscription (Story 2.4, AC1 et AC4). Ne rend AUCUNE session : le compte naît non
     * vérifié, et c'est la saisie du code qui ouvre la session.
     *
     * <p><b>La réponse est identique que l'adresse existe ou non</b> (NFR-P9). L'ancienne
     * version levait {@code ConflictException("Email already registered")} : n'importe qui
     * pouvait ainsi tester l'appartenance d'une adresse au fichier des utilisateurs. La
     * branche « adresse connue » ne crée donc ni entreprise, ni consentement, ni code — et
     * ne se voit pas de l'extérieur.
     *
     * <p><b>Y compris au chronomètre.</b> Le hachage du mot de passe coûte un bcrypt
     * complet ; ne l'exécuter que dans la branche « création » rendrait les deux cas
     * distinguables à la milliseconde près, ce qui suffit à reconstituer l'oracle qu'on
     * vient de fermer. Il est donc calculé AVANT le branchement, et simplement jeté quand
     * il ne sert pas.
     *
     * <p>Les refus qui portent sur ce que l'appelant a SOUMIS — mot de passe faible, rôle
     * privilégié, raison sociale absente, consentement non coché — restent explicites :
     * ils ne disent rien sur l'existence de l'adresse, et les taire empêcherait un
     * utilisateur honnête de corriger sa saisie.
     */
    @Transactional
    public void register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        // Politique de robustesse (Story 1.6, NFR-P5) : autorité unique, avant le hachage.
        passwordPolicy.validate(request.password());

        // Le rôle n'est JAMAIS attribué par le client à l'inscription. Liste BLANCHE :
        // seuls les rôles non-privilégiés {BUYER, SELLER} sont acceptés ; tout autre rôle
        // (ADMIN — arbitre, contrôle des fonds via RESOLVE_RELEASE/REFUND — ou un futur
        // rôle privilégié ajouté à l'enum) est rejeté, jamais ignoré silencieusement. La
        // garde ne dépend donc pas d'une liste noire à maintenir. L'absence de rôle retombe
        // sur BUYER. L'ADMIN est provisionné côté serveur (AdminBootstrap, puis Epic 7).
        Role role = (request.role() == null) ? Role.BUYER : request.role();
        if (role != Role.BUYER && role != Role.SELLER) {
            throw new BadRequestException("Ce rôle ne peut pas être auto-attribué à l'inscription");
        }

        if (request.companyName() == null || request.companyName().isBlank()) {
            throw new BadRequestException("La raison sociale est obligatoire");
        }
        // Le consentement se PROUVE, il ne se présume pas : un booléen absent ou faux est
        // un refus, jamais un défaut permissif (FR-P27).
        if (!Boolean.TRUE.equals(request.consentAccepted())) {
            throw new BadRequestException("Le consentement aux documents légaux est obligatoire");
        }

        // Coût constant entre les deux branches — voir la javadoc.
        String passwordHash = passwordEncoder.encode(request.password());

        if (users.existsByEmail(email)) {
            // Silence délibéré. Rien n'est créé, rien n'est envoyé, et l'appelant obtient
            // la même réponse que s'il venait de créer un compte.
            return;
        }

        Company company = new Company();
        company.setName(request.companyName().trim());
        company = companies.save(company);

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setRole(role);
        user.setCompany(company);
        // FR-P9 : le créateur est gestionnaire de l'entreprise qu'il déclare.
        user.setCompanyManager(true);
        user.setEmailVerified(false);
        user = users.save(user);

        LegalConsent consent = new LegalConsent();
        consent.setUser(user);
        consent.setDocumentVersion(
                request.consentDocumentVersion() == null || request.consentDocumentVersion().isBlank()
                        ? DEFAULT_CONSENT_VERSION
                        : request.consentDocumentVersion().trim());
        // Horodatage SERVEUR (AD-11) : une date venue du client serait une date choisie par
        // le signataire lui-même.
        consent.setConsentedAt(clock.instant());
        consents.save(consent);

        issueCode(user);
        audit.recordAccountSecurityEvent("REGISTERED_PENDING_VERIFICATION", user.getId(), "otp sent");
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        // Identifiants invalides = 401 (AUTH_FAILED), pas 400 : c'est une vraie
        // issue d'authentification (le rate-limiter la distingue d'un corps
        // malformé) et le message reste opaque (email inconnu vs mauvais mot de
        // passe indistinguables — anti-énumération).
        User user = users.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        // AC2 : un compte non vérifié n'entre JAMAIS dans l'application. Il repart vers
        // l'étape OTP, et le code porté ici est celui-là même que rend une vérification
        // ratée — sinon un attaquant muni d'identifiants valides apprendrait, à la seule
        // lecture du code, qu'un compte existe et attend sa vérification.
        if (!user.isEmailVerified()) {
            throw ApiExceptions.otpInvalid();
        }
        return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));
    }

    /**
     * Vérification du code (AC2, AC3).
     *
     * <p><b>Une seule réponse d'échec</b>, produite par {@code ApiExceptions.otpInvalid()} :
     * code faux, expiré, déjà consommé, invalidé par le plafond, ou adresse sans code en
     * attente. Distinguer « expiré » de « faux » confirmerait qu'un code a été émis pour
     * cette adresse — donc qu'elle est inscrite — et rouvrirait à l'arrivée l'oracle que
     * l'inscription ferme au départ.
     *
     * <p>Le plafond de tentatives est PERSISTÉ, pas gardé en mémoire : le code, lui, vit en
     * base et survit à un redémarrage. Un compteur en mémoire offrirait un plafond qu'il
     * suffit de faire redémarrer pour réarmer, tout en ayant l'air d'une protection.
     *
     * <p>Atteindre le plafond DÉTRUIT le code plutôt que de le laisser mourir de sa belle
     * mort : un code qu'on sait attaqué ne doit pas rester valide jusqu'à son expiration.
     *
     * <p><b>{@code noRollbackFor} n'est pas un détail de configuration : sans lui, le
     * plafond ne compte rien.</b> Spring défait la transaction sur toute exception non
     * vérifiée. L'incrément de {@code attempts} — et la destruction du code au plafond —
     * étaient donc annulés par l'{@code UnauthorizedException} levée juste après, à chaque
     * essai. Le compteur repartait de zéro indéfiniment et le code restait valide jusqu'à
     * son expiration : un forçage à 15 minutes sur un espace de 10^6, sans aucune limite.
     * Le code avait l'air correct, le plafond était décoratif, et seule l'exécution du test
     * de l'AC3 l'a montré.
     */
    @Transactional(noRollbackFor = ApiExceptions.CodedException.class)
    public AuthResponse verifyEmail(VerifyEmailRequest request) {
        String email = request.email().trim().toLowerCase();
        Optional<User> found = users.findByEmail(email);
        Optional<EmailVerificationCode> pending = found.flatMap(u -> codes.findByUserId(u.getId()));

        if (pending.isEmpty()) {
            // Comparaison factice : sans elle, une adresse inconnue répondrait bien plus
            // vite qu'un code faux, et le chronomètre trahirait ce que le code d'erreur tait.
            passwordEncoder.matches(request.code(), decoyHash);
            throw ApiExceptions.otpInvalid();
        }

        EmailVerificationCode code = pending.get();
        User user = found.get();

        if (clock.instant().isAfter(code.getExpiresAt()) || code.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            passwordEncoder.matches(request.code(), decoyHash);
            codes.delete(code);
            throw ApiExceptions.otpInvalid();
        }

        if (!passwordEncoder.matches(request.code(), code.getCodeHash())) {
            code.setAttempts(code.getAttempts() + 1);
            if (code.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
                codes.delete(code);
                audit.recordAccountSecurityEvent("OTP_ATTEMPTS_EXHAUSTED", user.getId(), "code invalidated");
            } else {
                codes.save(code);
            }
            throw ApiExceptions.otpInvalid();
        }

        // Usage unique : le code disparaît au succès, il ne peut donc pas être rejoué.
        codes.delete(code);
        user.setEmailVerified(true);
        users.save(user);
        audit.recordAccountSecurityEvent("EMAIL_VERIFIED", user.getId(), "otp accepted");
        return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));
    }

    /**
     * Renvoi du code (AC4).
     *
     * <p>Même silence qu'à l'inscription pour une adresse inconnue ou déjà vérifiée : on ne
     * confirme rien. Le dépassement de quota, lui, est REJETÉ en 429 avec son délai — l'AC
     * l'exige (« tout dépassement est rejeté avec un message daté »).
     *
     * <p><b>Oracle résiduel, assumé et consigné.</b> Ces deux exigences se contredisent en
     * partie : un attaquant qui parvient à déclencher le 429 apprend qu'un code est en
     * attente pour cette adresse. Le quota porte sur le DESTINATAIRE et non sur l'appelant,
     * ce qui est précisément ce qui ferme le canal de harcèlement ouvert par
     * l'anti-énumération — mais qui en fait aussi un signal observable. Fermer complètement
     * demanderait un quota simulé pour les adresses inexistantes, donc un stockage pour des
     * adresses arbitraires, c'est-à-dire un autre vecteur de déni de service. L'écart est
     * porté aux notes de complétion plutôt que maquillé.
     */
    @Transactional
    public void resendVerificationCode(ResendVerificationRequest request) {
        String email = request.email().trim().toLowerCase();
        Optional<User> found = users.findByEmail(email);
        if (found.isEmpty() || found.get().isEmailVerified()) {
            return;
        }
        issueCode(found.get());
    }

    /**
     * Émet un code neuf pour ce compte, en appliquant le quota d'envoi.
     *
     * <p>Le code REMPLACE le précédent. Laisser plusieurs codes valides multiplierait
     * mécaniquement les chances d'un attaquant à chaque renvoi, et ferait porter le plafond
     * de tentatives sur un code au lieu du compte.
     */
    private void issueCode(User user) {
        Instant now = clock.instant();
        EmailVerificationCode entry = codes.findByUserId(user.getId()).orElseGet(() -> {
            EmailVerificationCode fresh = new EmailVerificationCode();
            fresh.setUser(user);
            fresh.setSendWindowStart(now);
            fresh.setSendCount(0);
            return fresh;
        });

        if (entry.getId() != null) {
            // Fenêtre glissante : une fenêtre échue repart à zéro d'elle-même, sans tâche
            // de purge — une purge oubliée laisserait un compte verrouillé pour toujours.
            if (now.isAfter(entry.getSendWindowStart().plus(SEND_WINDOW))) {
                entry.setSendWindowStart(now);
                entry.setSendCount(0);
            }
            Instant nextAllowed = entry.getLastSentAt().plus(RESEND_COOLDOWN);
            if (now.isBefore(nextAllowed)) {
                throw ApiExceptions.resendTooSoon(Duration.between(now, nextAllowed).toSeconds());
            }
            if (entry.getSendCount() >= MAX_SENDS_PER_WINDOW) {
                Instant windowEnds = entry.getSendWindowStart().plus(SEND_WINDOW);
                throw ApiExceptions.resendTooSoon(Duration.between(now, windowEnds).toSeconds());
            }
        }

        String code = generateCode();
        entry.setCodeHash(passwordEncoder.encode(code));
        entry.setExpiresAt(now.plus(CODE_TTL));
        // Tentatives remises à zéro AVEC le code : elles comptaient les essais contre le
        // code précédent, qui n'existe plus.
        entry.setAttempts(0);
        entry.setSendCount(entry.getSendCount() + 1);
        entry.setLastSentAt(now);
        codes.save(entry);

        // Hors de la base : le code ne doit exister en clair que le temps de cet appel.
        sender.sendVerificationCode(user.getEmail(), code);
    }

    /**
     * Six chiffres tirés d'un {@link SecureRandom}.
     *
     * <p>{@code Math.random()} ou un {@code Random} nu seraient prédictibles à partir de
     * quelques tirages observés — sur une credential, c'est disqualifiant. Le zéro de tête
     * est conservé : « 004821 » est un code valide, et le tronquer diviserait l'espace.
     */
    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /**
     * Change le mot de passe d'un utilisateur authentifié (Story 1.6, AC #3).
     * Vérifie l'ancien mot de passe, applique la politique au nouveau, puis
     * <b>révoque toutes les sessions</b> — le jeton courant (ancienne version)
     * devient invalide, l'utilisateur doit se reconnecter.
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            // L'utilisateur est déjà authentifié (on sait qui il est) : pas de risque
            // d'énumération, un message clair est légitime. Distinct de WEAK_PASSWORD.
            // Message en anglais comme ses voisins : le backend n'émet pas de texte
            // localisé, le libellé utilisateur est porté par le frontend (AD-23).
            throw new BadRequestException(ErrorCode.INVALID_REQUEST, "Current password is incorrect");
        }
        passwordPolicy.validate(request.newPassword());
        // Refuser la rotation vers le MÊME secret (revue 1.6). Sans cette garde, le
        // changement répondait 204 et révoquait toutes les sessions sans rien changer :
        // l'utilisateur qui se croit compromis est déconnecté partout, croit avoir
        // tourné son mot de passe, et le secret fuité reste vivant.
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException(ErrorCode.INVALID_REQUEST,
                    "New password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.save(user);
        audit.recordAccountSecurityEvent("PASSWORD_CHANGED", userId, "change-password");
        // Passe par la primitive unique (Task 4/5) au lieu de dupliquer l'incrément :
        // c'est ce qui garantit que 2.6 (reset) et 7.2 (changement de rôle) révoqueront
        // exactement de la même façon.
        revokeSessions(userId, "password-changed");
    }

    /**
     * Révoque toutes les sessions d'un compte en incrémentant sa version de jeton
     * (Story 1.6). Primitive unique réutilisée par le logout, le changement de mot
     * de passe (ci-dessus), et plus tard le changement de rôle (7.2, AD-21) / la
     * désactivation.
     *
     * <p>L'incrément est fait par un UPDATE atomique et non par un
     * lire-modifier-écrire : voir {@link UserRepository#incrementTokenVersion} pour
     * la perte de mise à jour que cela ferme. Un compte absent est un no-op — un
     * logout doit rester idempotent et ne jamais échouer en 404.
     */
    @Transactional
    public void revokeSessions(Long userId, String reason) {
        if (users.incrementTokenVersion(userId) > 0) {
            audit.recordAccountSecurityEvent("SESSIONS_REVOKED", userId, reason);
        }
    }
}
