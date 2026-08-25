package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.service.TransitionException;
import com.zlecaf.escrow.service.scan.MalwareScanUnavailableException;
import com.zlecaf.escrow.service.storage.EvidenceStorageException;
import com.zlecaf.escrow.web.ApiExceptions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The single envelope builder for every branch below. {@code code} is the
     * stable machine token clients classify against (AD-10); {@code error} stays
     * the HTTP reason phrase and {@code message} stays human-facing text — the
     * addition is purely additive, nothing existing changes name or meaning.
     *
     * <p>{@link Map#of} throws on a null value, so a branch that forgot its code
     * would turn a clean business rejection into a 500. The {@code requireNonNull}
     * makes that failure loud and local instead.
     */
    private ResponseEntity<Map<String, Object>> body(HttpStatus status, ErrorCode code, String message) {
        return body(status, code, message, null);
    }

    /**
     * Same envelope, plus the protocol headers a given status is expected to carry.
     *
     * <p>This overload exists because this advice does <b>not</b> extend
     * {@code ResponseEntityExceptionHandler}: a {@code ResponseEntity} we build by
     * hand replaces Spring's own handling entirely, so anything
     * {@code DefaultHandlerExceptionResolver} used to add — {@code Allow} on a 405,
     * {@code Accept} on a 415 — is simply lost unless we add it back here.
     *
     * <p><b>Le {@code Content-Type} est épinglé pour TOUTES les branches</b> (revue de
     * suivi 1.10), et pas seulement pour le 406 qui l'avait introduit. Sans type concret,
     * l'écriture de l'enveloppe repasse par la négociation de contenu : un appelant qui
     * envoie un {@code Accept} excluant JSON — un client mal configuré, un proxy, une
     * sonde — recevrait alors une réponse <em>nue</em>, sans {@code code}, donc inclassable
     * par AD-10, sur n'importe laquelle des branches. Le raisonnement écrit pour le 406
     * valait déjà pour ses quinze voisines ; il n'y avait aucune raison de le réserver à
     * une seule. Un type concret court-circuite la négociation dans
     * {@code AbstractMessageConverterMethodProcessor}, et il est exact : le corps est
     * toujours cette {@code Map} sérialisée en JSON.
     */
    private ResponseEntity<Map<String, Object>> body(HttpStatus status, ErrorCode code, String message,
                                                     HttpHeaders headers) {
        Objects.requireNonNull(code, "code");
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers);
        }
        builder.contentType(MediaType.APPLICATION_JSON);
        return builder.body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "code", code.name(),
                "message", message));
    }

    @ExceptionHandler(TransitionException.class)
    public ResponseEntity<Map<String, Object>> onTransition(TransitionException ex) {
        // The code now drives the status fork the Reason enum used to drive: only
        // a role rejection is a 403, every other lifecycle rejection is a 409.
        HttpStatus status = ex.getCode() == ErrorCode.UNAUTHORIZED_TRANSITION
                ? HttpStatus.FORBIDDEN
                : HttpStatus.CONFLICT;
        return body(status, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> onConflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> onForbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage());
    }

    /**
     * Quota d'envoi dépassé (Story 2.4, AC4) : 429 portant son délai de réessai.
     *
     * <p>{@code Retry-After} est un en-tête standard, calculé sur l'horloge SERVEUR
     * (AD-11) : c'est lui qui alimente le compte à rebours affiché, et non un minuteur
     * démarré par le client — celui-là se remet à zéro en rechargeant la page.
     *
     * <p>Le corps ne dit ni quel compte, ni combien d'envois restent : l'inscription
     * refusant de confirmer qu'une adresse existe, un quota bavard le confirmerait à sa
     * place.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> onTooManyRequests(TooManyRequestsException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        return body(HttpStatus.TOO_MANY_REQUESTS, ex.getCode(), ex.getMessage(), headers);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> onUnauthorized(UnauthorizedException ex) {
        // Partner signature auth failure (unknown/inactive key, bad signature,
        // stale timestamp, replayed nonce): 401 in the standard envelope. All six
        // failure sites carry the same opaque AUTH_FAILED code — telling them
        // apart would let a caller enumerate which key-ids exist and are active.
        return body(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> onOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        // A concurrent writer won the @Version race. Previously this escaped the
        // advice entirely and surfaced as an uncoded 500, which a client cannot
        // tell from a genuine server fault: it is a 409, and it is retryable.
        // No SQL, entity or SDK detail is leaked — a fixed, neutral message.
        return body(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "The transaction was modified concurrently; please retry");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> onMaxUploadSize(MaxUploadSizeExceededException ex) {
        // Container multipart cap breached: report as a client size error (400),
        // consistent with the service-arbitrated per-file limit — and with the same
        // EVIDENCE_INVALID code, since it is the same "this file is unacceptable"
        // verdict arbitrated one layer earlier.
        return body(HttpStatus.BAD_REQUEST, ErrorCode.EVIDENCE_INVALID,
                "Uploaded file exceeds the maximum permitted size");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, Object>> onMissingPart(Exception ex) {
        // A required multipart part, request parameter or request header is absent:
        // return the standard 400 envelope rather than Spring's default error body.
        return body(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_REQUEST_PART, ex.getMessage());
    }

    @ExceptionHandler(EvidenceStorageException.class)
    public ResponseEntity<Map<String, Object>> onEvidenceStorage(EvidenceStorageException ex) {
        // Object store unreachable/failed (not a missing object): report a 502 in
        // the standard envelope. No SDK detail is leaked — a fixed, neutral message.
        return body(HttpStatus.BAD_GATEWAY, ErrorCode.STORAGE_UNAVAILABLE, "Stockage de preuves indisponible");
    }

    @ExceptionHandler(MalwareScanUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onMalwareScanUnavailable(MalwareScanUnavailableException ex) {
        // Analyse antivirus impossible (moteur injoignable, timeout, réponse
        // incomprise) : 502 dans l'enveloppe standard, sur le modèle exact de l'autre
        // dépendance sortante du dépôt. Le code est TRANSITOIRE — la file offline
        // rejouera au lieu de geler une preuve légitime — et la cause détaillée reste
        // dans le journal serveur : ni l'hôte, ni le port, ni la réponse du moteur ne
        // franchissent la frontière HTTP.
        // Message en anglais comme ses voisins (revue 1.8) : le backend n'émet pas de
        // texte localisé, le libellé utilisateur est porté par le frontend (AD-23).
        LOG.warn("Evidence ingestion refused: malware scan unavailable", ex);
        return body(HttpStatus.BAD_GATEWAY, ErrorCode.SCAN_UNAVAILABLE, "Malware scanning is unavailable");
    }

    /**
     * Les rejets « natifs » de Spring MVC, ramenés dans l'enveloppe (Story 1.10).
     *
     * <p>Sans ces sept branches, une sonde qui se trompe d'URL, de méthode, de type
     * d'identifiant, de {@code Content-Type}, de {@code Accept}, de corps JSON ou de
     * frontière multipart tombait dans le filet {@code Exception} ci-dessous et recevait
     * un <b>500</b>. Sur une surface dont toute la valeur est que ses réponses soient
     * <em>indistinguables</em>, un 500 est un signal parfaitement distinguable : il dit
     * « ta requête a fait quelque chose d'inhabituel ici », ce qui est exactement
     * l'information qu'on prétend retirer.
     *
     * <p>Pire encore côté client : {@code INTERNAL_ERROR} est classé <b>TRANSIENT</b>
     * (AD-10), donc la file de rejeu offline réessaierait indéfiniment une requête
     * définitivement cassée. Ce n'est pas théorique : {@code frontend/src/stores/escrow.js}
     * met en file hors ligne {@code POST /api/v1/escrow}, {@code POST /{id}/event} (corps
     * JSON) et {@code POST /{id}/dispute} (multipart), et les rejoue tels quels — un corps
     * indésérialisable (valeur d'enum {@code event} retirée par un déploiement) ou une
     * frontière multipart corrompue sont donc des rejeux réels, et éternels tant qu'ils
     * répondent 500. Les deux codes utilisés ici existent déjà et sont PERMANENT — aucune
     * valeur d'enum n'est ajoutée, donc aucun miroir frontend à synchroniser.
     *
     * <p><b>La contrepartie, qui est un choix et non un oubli.</b> Passer de 500 à 4xx
     * <em>reclasse</em> ces échecs de « transitoire » à « permanent » côté client
     * ({@code replayFailure.js} : {@code status >= 500} ⇒ transitoire, {@code INVALID_REQUEST}
     * et {@code RESOURCE_NOT_FOUND} ⇒ permanent). Une PWA au shell précaché qui rejoue
     * contre un backend dont les routes ont bougé <b>gèle</b> donc l'entrée au lieu de
     * guérir seule au déploiement suivant. C'est le comportement voulu par AD-10 : geler
     * n'est pas perdre — l'entrée et son binaire sont <em>conservés</em>, l'utilisateur est
     * notifié avec un motif lisible, et la Story 4.5 fournit la reprise explicite. Le
     * rejeu infini, lui, n'a aucune sortie et brûle la batterie et le quota d'un
     * utilisateur qui ne verra jamais rien.
     *
     * <p>Messages FIXES et neutres : ni le chemin demandé, ni les méthodes réellement
     * supportées, ni le nom du paramètre mal typé, ni le type de média reçu, ni la
     * position de l'octet fautif dans le JSON. Le message par défaut de Spring les nomme
     * tous — et c'est précisément une cartographie gratuite de la surface d'API. Ce que
     * le <em>protocole</em> exige reste posé en en-tête ({@code Allow}, {@code Accept}
     * ci-dessous) : c'est une information que le client possède déjà, pas une découverte.
     *
     * <p><b>Journalisation en DEBUG, jamais WARN ni ERROR.</b> Ces branches sont les
     * seules à recevoir un {@code ex} porteur du seul signal serveur restant : depuis
     * qu'elles existent, un contrôleur mal enregistré après un refactor rend un 404
     * propre et <em>silencieux</em>, là où il atteignait le {@code LOG.error} du filet.
     * DEBUG parce qu'un balayage d'identifiants produit une ligne par sonde : à
     * WARN/ERROR il inonderait les journaux d'exploitation, c'est-à-dire exactement le
     * vecteur d'inondation que cette story a re-routé vers le ledger. Le diagnostic reste
     * disponible en abaissant le niveau du logger, ce qui est un geste d'exploitant.
     *
     * <p><b>Frontière délibérée : {@code MissingPathVariableException} n'a PAS de branche.</b>
     * Elle ne signale pas une requête fautive mais un défaut de mapping côté serveur (un
     * {@code @PathVariable} qui ne figure pas dans le patron d'URI) : aucune requête ne
     * peut la provoquer, et un 500 y est honnête. La faire tomber dans l'enveloppe 4xx
     * mentirait au client et retirerait un {@code LOG.error} utile.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> onNoResource(NoResourceFoundException ex) {
        LOG.debug("No handler for the requested resource", ex);
        return body(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> onMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        // 405 et non 404 : la méthode est la faute, pas la ressource. Prétendre 404
        // ici ne cacherait rien de plus — l'appelant connaît déjà l'URL qu'il vient
        // d'écrire — et casserait un contrat HTTP standard.
        //
        // L'en-tête `Allow` est posé ICI et non par le conteneur : cette advice
        // n'étend pas ResponseEntityExceptionHandler, donc la ResponseEntity construite
        // à la main REMPLACE le travail de DefaultHandlerExceptionResolver, `Allow`
        // compris. Sans cette ligne, le 405 serait non conforme (RFC 9110 §15.5.6 :
        // « the server MUST generate an Allow header »).
        LOG.debug("Method not supported for the requested resource", ex);
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
        if (!isEmpty(allowed)) {
            headers.setAllow(allowed);
        }
        return body(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.INVALID_REQUEST, "Method not supported", headers);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // Ex. /api/v1/escrow/abc : l'identifiant n'est pas un Long. C'est une requête
        // malformée (400), jamais un défaut serveur — et le message ne répète pas la
        // valeur reçue, qui est de la donnée d'attaquant renvoyée telle quelle.
        LOG.debug("Path or request parameter could not be converted", ex);
        return body(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid request parameter");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> onMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        // `Accept` en réponse à un 415 : même raison que `Allow` sur le 405 — c'est
        // DefaultHandlerExceptionResolver qui le posait, et il ne tourne plus ici.
        LOG.debug("Unsupported request media type", ex);
        HttpHeaders headers = new HttpHeaders();
        List<MediaType> supported = ex.getSupportedMediaTypes();
        if (!isEmpty(supported)) {
            headers.setAccept(supported);
        }
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.INVALID_REQUEST, "Unsupported media type", headers);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onUnreadableBody(HttpMessageNotReadableException ex) {
        // Corps de requête indésérialisable : JSON tronqué, mais aussi et surtout une
        // valeur d'enum que le serveur ne connaît plus. Le cas réel est un rejeu :
        // `POST /{id}/event` mis en file hors ligne avec un `event` qu'un déploiement a
        // retiré depuis. Sans cette branche c'est un 500 / INTERNAL_ERROR, donc TRANSIENT,
        // donc rejoué indéfiniment pour une requête qui ne guérira jamais.
        //
        // Message fixe : celui de Jackson nomme le champ, la classe cible et parfois les
        // valeurs acceptées — une cartographie du modèle interne offerte à l'appelant.
        LOG.debug("Request body could not be read", ex);
        return body(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> onMalformedMultipart(MultipartException ex) {
        // Frontière multipart corrompue sur /dispute et /evidence — un rejeu tronqué,
        // un proxy qui réécrit le corps. Même raisonnement que ci-dessus : 400 PERMANENT
        // plutôt qu'un 500 TRANSIENT rejoué sans fin.
        //
        // Branche DISTINCTE de celle de MaxUploadSizeExceededException, qui en est une
        // sous-classe : Spring choisit toujours le handler le plus spécifique, donc le
        // dépassement de taille garde son propre verdict (EVIDENCE_INVALID) et n'est pas
        // absorbé ici. Les fusionner rendrait « fichier trop gros » indiscernable de
        // « requête cassée », alors que l'utilisateur n'a pas la même chose à faire.
        LOG.debug("Multipart request could not be parsed", ex);
        return body(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Malformed multipart request");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> onMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        // En-tête `Accept` insatisfiable : 406, et non le 500 qu'un filet générique
        // rendait.
        //
        // C'est la branche où l'épinglage du Content-Type par body() est vital et non
        // seulement prudent : sans type concret, l'écriture de cette réponse repasserait
        // par la négociation de contenu, échouerait sur le même `Accept` qui a causé le
        // 406, et le client recevrait une réponse nue — sans `code`, donc inclassable par
        // AD-10, ce que la branche existe justement pour éviter. Depuis la revue de suivi
        // l'épinglage vaut pour toutes les branches, pour la même raison : un `Accept`
        // hostile n'est pas réservé aux 406.
        LOG.debug("No representation acceptable to the client", ex);
        return body(HttpStatus.NOT_ACCEPTABLE, ErrorCode.INVALID_REQUEST, "Not acceptable");
    }

    /** {@code null}-safe emptiness test: both Spring getters above are {@code @Nullable}. */
    private static boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
    }

    /**
     * Filet de sécurité (revue 1.6) : toute exception non prévue sort quand même
     * dans l'enveloppe standard, avec un {@code code}.
     *
     * <p>Sans lui, un défaut inattendu produisait le corps d'erreur par défaut de
     * Spring Boot — <b>sans le champ {@code code}</b>, pourtant obligatoire — et le
     * client AD-10, qui classe transitoire vs permanent sur ce seul champ, n'avait
     * rien à lire. Le message est fixe : aucun détail interne (message d'exception,
     * pile, SQL) ne franchit la frontière HTTP.
     *
     * <p>Depuis la Story 1.10 il ne voit plus les rejets natifs de Spring MVC (route
     * inconnue, méthode non supportée, identifiant mal typé, type de média refusé ou
     * inacceptable, corps JSON illisible, multipart cassé) : ils ont leurs propres
     * branches ci-dessus. Ce filet ne reste donc que pour ce qui est réellement
     * inattendu — ce qui est aussi la seule façon qu'un 500 redevienne un signal utile
     * en exploitation. Il conserve délibérément {@code MissingPathVariableException} et
     * ses semblables : ce sont des défauts de mapping serveur, pour lesquels le 500 et
     * le {@code LOG.error} ci-dessous sont la réponse honnête.
     *
     * <p><b>Portée</b> : le chemin MVC. Une exception levée par un filtre servlet
     * (ex. panne DB pendant le lookup de {@code JwtAuthFilter}) s'échappe avant
     * {@code @ControllerAdvice} et reste servie par le {@code /error} du conteneur.
     * Ce reliquat est consigné au ledger.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception ex) {
        LOG.error("Unhandled exception escaping to the error envelope", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Internal server error");
    }
}
