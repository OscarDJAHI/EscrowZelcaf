package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.service.TransitionException;
import com.zlecaf.escrow.service.storage.EvidenceStorageException;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Locks the wire contract of the error envelope: every branch of
 * {@link GlobalExceptionHandler} serialises a non-null {@code code} alongside the
 * unchanged {@code timestamp/status/error/message}. Clients (AD-10) classify on
 * that code alone — the status is too coarse and the message is interpolated
 * prose no contract pins.
 *
 * <p>A throwing stub controller stands in for the real ones: the mapping under
 * test is exception → envelope, and routing an arbitrary exception through a real
 * endpoint would only add coupling to that endpoint's signature. Standalone
 * setup, per {@link EvidenceStorageErrorMappingTest} — no Spring context.
 */
class GlobalExceptionHandlerTest {

    /** Set per test; the stub rethrows it from a trivial GET. */
    private final AtomicReference<RuntimeException> toThrow = new AtomicReference<>();
    private MockMvc mvc;

    /** A value a deployment could later remove — the real cause of an unreadable replay. */
    enum StubEvent { PAY_FUNDS }

    record StubEventRequest(StubEvent event) {}

    @RestController
    class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            throw toThrow.get();
        }

        /**
         * Story 1.10 : cinq surfaces qui ne LEVENT rien elles-memes. Les rejets
         * testes plus bas (405, 400 de typage, 415, 406, corps JSON illisible) sont
         * produits par Spring MVC lui-meme au routage, a la negociation de contenu ou
         * a la resolution d'argument — les fabriquer a la main prouverait le mapping
         * d'une exception que Spring ne leve peut-etre pas.
         */
        @GetMapping("/typed/{id}")
        String typed(@PathVariable Long id) {
            return "ok";
        }

        @PostMapping(value = "/jsonOnly", consumes = MediaType.APPLICATION_JSON_VALUE)
        String jsonOnly() {
            return "ok";
        }

        /** Produces JSON only: un `Accept: application/xml` y devient un vrai 406. */
        @GetMapping(value = "/jsonProduced", produces = MediaType.APPLICATION_JSON_VALUE)
        String jsonProduced() {
            return "{}";
        }

        /**
         * Corps type par un enum : poster une valeur inconnue fait lever a Jackson,
         * donc a Spring, une vraie HttpMessageNotReadableException — exactement ce
         * qu'un rejeu hors ligne produit apres qu'un deploiement a retire la valeur.
         */
        @PostMapping(value = "/typedBody", consumes = MediaType.APPLICATION_JSON_VALUE)
        String typedBody(@RequestBody StubEventRequest request) {
            return "ok";
        }

        /**
         * Seule des branches natives « de routage » a devoir etre levee explicitement :
         * {@code standaloneSetup} n'installe aucun handler de ressources statiques,
         * donc une URL inconnue y rend un 404 nu sans jamais lever l'exception. Le cas
         * reel (« GET /api/v1/escrow/1/inexistant ») est couvert de bout en bout par
         * {@code AntiEnumerationIntegrationTest}, sur la vraie chaine.
         */
        @GetMapping("/noSuchResource")
        String noSuchResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/api/v1/escrow/1/inexistant");
        }
    }

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // --- The envelope contract itself ---

    @Test
    @DisplayName("The envelope is additive: code joins timestamp/status/error/message, none of which change")
    void envelopeIsAdditive() throws Exception {
        toThrow.set(new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction 42 not found"));

        mvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                // `error` stays the HTTP reason phrase; `code` is a different thing in
                // a different field. Conflating them is exactly the defect being fixed.
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Transaction 42 not found"));
    }

    @Test
    @DisplayName("A throw site with no explicit code still gets a non-null default from its type")
    void defaultCodePerType() throws Exception {
        // Map.of rejects a null value: a code-less branch would turn a clean 400 into
        // a 500. The per-type default is what makes the envelope total.
        toThrow.set(new BadRequestException("Buyer and seller must be different users"));

        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- Branch-by-branch codes ---

    @Test
    @DisplayName("404: an explicitly coded NotFoundException carries its code")
    void notFoundCarriesCode() throws Exception {
        toThrow.set(new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction 1 not found"));
        mvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("400: an unacceptable file is EVIDENCE_INVALID")
    void badRequestCarriesCode() throws Exception {
        toThrow.set(new BadRequestException(ErrorCode.EVIDENCE_INVALID, "Uploaded file is empty"));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INVALID"));
    }

    @Test
    @DisplayName("400: an unreadable upload is FILE_READ_ERROR — same status, different verdict")
    void sameStatusDifferentCode() throws Exception {
        // The proof that the code is not a function of the status: this 400 is
        // retryable and the one above is not.
        toThrow.set(new BadRequestException(ErrorCode.FILE_READ_ERROR, "Could not read the uploaded file"));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_READ_ERROR"));
    }

    @Test
    @DisplayName("409: a ConflictException carries its code")
    void conflictCarriesCode() throws Exception {
        toThrow.set(new ConflictException(ErrorCode.WINDOW_CLOSED,
                "Evidence cannot be deposited while the transaction is INITIATED"));
        mvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("403: the only surviving applicative Forbidden is the own-piece guard, coded FORBIDDEN")
    void forbiddenCarriesCode() throws Exception {
        // Story 1.10 : le cas d'origine ici etait un refus d'appartenance code
        // NOT_A_PARTY. Ce code n'existe plus — un non-partie recoit desormais le 404
        // opaque, prouve de bout en bout par AntiEnumerationIntegrationTest. Le seul
        // 403 applicatif restant est la garde « piece a soi », qui ne revele rien :
        // son destinataire est deja partie et voit deja la piece par list().
        toThrow.set(new ForbiddenException("You can only withdraw your own evidence"));
        mvc.perform(get("/boom"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("401: a partner auth failure is the opaque AUTH_FAILED")
    void unauthorizedCarriesOpaqueCode() throws Exception {
        toThrow.set(new UnauthorizedException("Invalid partner credentials"));
        mvc.perform(get("/boom"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid partner credentials"));
    }

    @Test
    @DisplayName("502: a storage failure is STORAGE_UNAVAILABLE with the message unchanged")
    void storageFailureCarriesCode() throws Exception {
        toThrow.set(new EvidenceStorageException("42/abc", new RuntimeException("boom")));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Stockage de preuves indisponible"));
    }

    // --- The TransitionException status fork, now driven by the code ---

    @Test
    @DisplayName("403: only UNAUTHORIZED_TRANSITION forks the status to Forbidden")
    void unauthorizedTransitionIs403() throws Exception {
        toThrow.set(new TransitionException(ErrorCode.UNAUTHORIZED_TRANSITION,
                "Role SELLER is not authorised to trigger OPEN_DISPUTE from state SHIPPED"));
        mvc.perform(get("/boom"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_TRANSITION"));
    }

    @Test
    @DisplayName("409: every other lifecycle rejection stays a Conflict, each with its own code")
    void otherTransitionsAre409() throws Exception {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.ILLEGAL_TRANSITION, ErrorCode.TRANSACTION_TERMINAL,
                ErrorCode.DISPUTE_ALREADY_RESOLVED}) {
            toThrow.set(new TransitionException(code, "Event OPEN_DISPUTE is not permitted from state RELEASED"));
            mvc.perform(get("/boom"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    // --- The new optimistic-lock branch ---

    @Test
    @DisplayName("409: an optimistic-lock collision is a coded, retryable Conflict — no longer an uncoded 500")
    void optimisticLockIs409() throws Exception {
        // Previously this escaped the advice and surfaced as a bare 500, which a
        // client cannot distinguish from a genuine server fault: it would either
        // retry a real bug forever or drop a request that was merely unlucky.
        toThrow.set(new ObjectOptimisticLockingFailureException("EscrowTransaction", 42L));

        mvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message").value("The transaction was modified concurrently; please retry"));
    }

    @Test
    @DisplayName("The optimistic-lock envelope leaks no entity, SQL or class detail")
    void optimisticLockLeaksNothing() throws Exception {
        toThrow.set(new ObjectOptimisticLockingFailureException("EscrowTransaction", 42L));
        mvc.perform(get("/boom"))
                .andExpect(jsonPath("$.message").value(not(containsString("EscrowTransaction"))));
    }

    // --- Story 1.10: Spring's own rejections, pulled back into the envelope ---
    //
    // Elles tombaient toutes dans le filet `Exception` et sortaient en 500
    // INTERNAL_ERROR. Deux defauts distincts : (1) sur une surface dont toute la
    // valeur est l'indistinguabilite, un 500 est un signal parfaitement distinguable ;
    // (2) INTERNAL_ERROR est classe TRANSIENT (AD-10), donc la file offline rejouerait
    // indefiniment une requete definitivement cassee. Aucune valeur d'enum n'est
    // ajoutee : les deux codes utilises existent deja et sont PERMANENT.

    @Test
    @DisplayName("404: an unmatched sub-resource is a coded RESOURCE_NOT_FOUND, no longer a 500")
    void noResourceFoundIs404() throws Exception {
        mvc.perform(get("/noSuchResource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                // Le chemin demande ne revient PAS dans le corps : le message par
                // defaut de Spring le nomme, ce qui rend la sonde auto-confirmante.
                .andExpect(jsonPath("$.message").value(not(containsString("escrow"))));
    }

    @Test
    @DisplayName("405: an unsupported method is a coded INVALID_REQUEST carrying Allow, no longer a 500")
    void methodNotSupportedIs405() throws Exception {
        mvc.perform(delete("/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                // Les methodes reellement supportees ne sont pas enumerees dans le CORPS.
                .andExpect(jsonPath("$.message").value("Method not supported"))
                // ... mais l'en-tete Allow, lui, doit etre la. Cette advice n'etend pas
                // ResponseEntityExceptionHandler : la ResponseEntity construite a la main
                // REMPLACE DefaultHandlerExceptionResolver, qui posait cet en-tete. Sans
                // la ligne correspondante dans le handler, le 405 serait non conforme
                // (RFC 9110 : le serveur DOIT generer Allow) et cette assertion rouge.
                // Ce n'est pas une fuite : l'appelant connait deja l'URL qu'il a ecrite.
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    @DisplayName("400: a non-numeric path id is a coded INVALID_REQUEST, no longer a 500")
    void typeMismatchIs400() throws Exception {
        mvc.perform(get("/typed/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                // Ni le nom du parametre, ni la valeur recue (donnee d'attaquant
                // renvoyee telle quelle) ne franchissent la frontiere.
                .andExpect(jsonPath("$.message").value("Invalid request parameter"));
    }

    @Test
    @DisplayName("415: an unsupported media type is a coded INVALID_REQUEST carrying Accept, no longer a 500")
    void mediaTypeNotSupportedIs415() throws Exception {
        mvc.perform(post("/jsonOnly").contentType(MediaType.TEXT_PLAIN).content("nope"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unsupported media type"))
                // Meme raison que l'Allow du 405 : c'est DefaultHandlerExceptionResolver
                // qui posait cet en-tete, et il ne tourne plus.
                .andExpect(header().string("Accept", containsString(MediaType.APPLICATION_JSON_VALUE)));
    }

    @Test
    @DisplayName("400: an unreadable JSON body is a coded INVALID_REQUEST — the replay that would never have healed")
    void unreadableBodyIs400() throws Exception {
        // Le cas reel n'est pas un JSON tronque mais une valeur d'enum retiree par un
        // deploiement : `POST /{id}/event` mis en file hors ligne est rejoue tel quel.
        // En 500 / INTERNAL_ERROR il etait classe TRANSIENT, donc rejoue A L'INFINI pour
        // une requete que rien ne reparera.
        mvc.perform(post("/typedBody")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"REMOVED_BY_A_DEPLOY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                // Le message de Jackson nomme le champ, la classe cible et les valeurs
                // acceptees : une cartographie du modele interne, jamais publiee.
                .andExpect(jsonPath("$.message").value(not(containsString("StubEvent"))))
                .andExpect(jsonPath("$.message").value(not(containsString("event"))));
    }

    @Test
    @DisplayName("400: a broken multipart boundary is a coded INVALID_REQUEST, no longer a 500")
    void malformedMultipartIs400() throws Exception {
        toThrow.set(new MultipartException("Failed to parse multipart servlet request"));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed multipart request"));
    }

    @Test
    @DisplayName("The size cap keeps its OWN verdict although it subclasses MultipartException")
    void maxUploadSizeKeepsItsOwnBranch() throws Exception {
        // Spring resout toujours le @ExceptionHandler le plus specifique. Si la nouvelle
        // branche MultipartException absorbait celle-ci, « fichier trop gros » (que
        // l'utilisateur peut corriger) deviendrait indiscernable de « requete cassee ».
        toThrow.set(new MaxUploadSizeExceededException(10_485_760L));
        mvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INVALID"))
                .andExpect(jsonPath("$.message").value("Uploaded file exceeds the maximum permitted size"));
    }

    @Test
    @DisplayName("406: an unsatisfiable Accept is a coded INVALID_REQUEST — and the envelope still reaches the client")
    void mediaTypeNotAcceptableIs406() throws Exception {
        // Le piege de cette branche : l'ecriture de la reponse repasse par la
        // negociation de contenu et echouerait sur le MEME `Accept`, rendant un 406 nu
        // — sans `code`, donc inclassable par AD-10. Le Content-Type fixe explicitement
        // par le handler court-circuite la negociation ; c'est ce que prouvent les deux
        // assertions sur le corps ci-dessous, pas seulement le statut.
        mvc.perform(get("/jsonProduced").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Not acceptable"));
    }

    @Test
    @DisplayName("Un `Accept` hostile ne denude AUCUNE branche, pas seulement le 406")
    void hostileAcceptLeavesEveryBranchCoded() throws Exception {
        // Le test ci-dessus ne garde que la branche 406. Or l'epinglage du Content-Type
        // vit dans body(), donc il vaut pour les seize branches — et rien ne le prouvait :
        // le remettre sur le seul 406, comme il l'etait avant la revue de suivi, laisse
        // la suite verte tout en rouvrant le trou partout ailleurs. Un `Accept` hostile
        // n'est pas reserve aux 406 : un proxy, une sonde, un client mal configure en
        // envoie un sur n'importe quelle route, et recevrait alors un refus NU — sans
        // `code`, donc inclassable par AD-10, ce que toute la story vise a empecher.
        toThrow.set(ApiExceptions.transactionNotFound());
        mvc.perform(get("/boom").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Transaction not found"));
    }
}
