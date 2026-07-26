package com.zlecaf.escrow.domain;

import com.zlecaf.escrow.domain.ErrorCode.Retryability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the authoritative error vocabulary. The offline replay queue (AD-10)
 * mirrors these names and this partition on the client; nothing in the type
 * system connects the two copies, so a rename or a reclassification here would
 * otherwise drift the client into silently misclassifying rejections — retrying
 * a permanently doomed request forever, or dropping a recoverable one.
 *
 * <p>This test is that connection. It is meant to fail on any change, so that the
 * change is a deliberate, coordinated act rather than an accident.
 */
class ErrorCodeContractTest {

    /**
     * The permanent partition the client mirrors: every code on which the offline
     * queue must freeze rather than retry (AD-10's rule). Ten today.
     *
     * <p>Deliberately <em>not</em> described as "the list AD-10 spells out": the AD
     * states the rule, and this set is the living roster the rule applies to. Stories
     * add to it (1.6 WEAK_PASSWORD, 1.8 EVIDENCE_MALWARE_DETECTED) and remove from it
     * (1.10), so a claim to quote a document would go stale the next sprint and this
     * test would then describe something that no longer exists — which is the very
     * failure it was written to prevent.
     */
    private static final Set<ErrorCode> AD10_PERMANENT = EnumSet.of(
            ErrorCode.DISPUTE_ALREADY_RESOLVED,
            ErrorCode.TRANSACTION_TERMINAL,
            ErrorCode.WINDOW_CLOSED,
            ErrorCode.EVIDENCE_INVALID,
            // Story 1.10 : NOT_A_PARTY RETIRE de l'ensemble ET de l'enum. Le refus
            // d'appartenance repond desormais TRANSACTION_NOT_FOUND, ci-dessous —
            // c'est le point entier de la story, l'oracle vivait dans le `code`
            // autant que dans le statut. Le cardinal passe de 11 a 10.
            ErrorCode.TRANSACTION_NOT_FOUND,
            ErrorCode.EVIDENCE_FLOOR_VIOLATION,
            ErrorCode.COMMENT_TOO_SHORT,
            ErrorCode.TOO_MANY_FILES,
            // Story 1.6 : rejouer le meme mot de passe faible echoue a l'identique.
            // Ajoute A L'ENSEMBLE (revue 1.6) et pas seulement teste isolement : une
            // assertion qui recite le litteral de l'enum ne peut jamais detecter de
            // derive, puisqu'on editerait les deux lignes ensemble. Seule
            // l'appartenance a cet ensemble + son cardinal force le classement
            // delibere du prochain code.
            ErrorCode.WEAK_PASSWORD,
            // Story 1.8 : rejouer le MEME fichier redeclenchera a l'identique, donc la
            // file doit geler l'entree et afficher le motif. Range dans l'ensemble pour
            // la meme raison que WEAK_PASSWORD ci-dessus : c'est l'appartenance et le
            // cardinal qui forcent le classement delibere du prochain code.
            ErrorCode.EVIDENCE_MALWARE_DETECTED);

    /** Every code the queue is allowed to replay. Exhaustive by construction below. */
    private static final Set<ErrorCode> TRANSIENT = EnumSet.of(
            ErrorCode.CONCURRENT_MODIFICATION,
            ErrorCode.FILE_READ_ERROR,
            ErrorCode.STORAGE_UNAVAILABLE,
            // Story 1.3 : ajout coordonne (miroir frontend replayFailure.js mis a jour).
            ErrorCode.RATE_LIMITED,
            // Revue 1.6 : filet de securite du GlobalExceptionHandler. Un defaut interne
            // est circonstanciel — la file a raison de reessayer (miroir frontend mis a jour).
            ErrorCode.INTERNAL_ERROR,
            // Story 1.8 : ajout coordonne (miroir frontend replayFailure.js mis a jour
            // dans le meme commit). Sans lui, une panne d'antivirus gelerait
            // DEFINITIVEMENT une preuve parfaitement legitime.
            ErrorCode.SCAN_UNAVAILABLE);

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("Every code declares a retryability — there is no implicit default")
    void everyCodeIsClassified(ErrorCode code) {
        assertThat(code.retryability()).isNotNull();
    }

    @Test
    @DisplayName("The ten codes of the client-mirrored permanent partition are PERMANENT")
    void ad10PermanentCodesArePermanent() {
        assertThat(AD10_PERMANENT).hasSize(10);
        assertThat(AD10_PERMANENT).allSatisfy(code ->
                assertThat(code.retryability()).isEqualTo(Retryability.PERMANENT));
    }

    @Test
    @DisplayName("Exactly CONCURRENT_MODIFICATION, FILE_READ_ERROR, STORAGE_UNAVAILABLE, RATE_LIMITED, INTERNAL_ERROR and SCAN_UNAVAILABLE are TRANSIENT")
    void transientPartitionIsExact() {
        Set<ErrorCode> actual = Arrays.stream(ErrorCode.values())
                .filter(c -> c.retryability() == Retryability.TRANSIENT)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ErrorCode.class)));
        // Exact, not a superset: a new code silently landing in TRANSIENT would make
        // the client retry something it has never been taught to retry.
        assertThat(actual).isEqualTo(TRANSIENT);
    }

    @Test
    @DisplayName("FILE_READ_ERROR is TRANSIENT although it is served under a 400 — the code is not the status")
    void codeIsIndependentOfStatus() {
        // The whole reason this enum exists: EVIDENCE_INVALID and FILE_READ_ERROR are
        // both 400s, yet one must never be retried and the other should be. A client
        // classifying on the HTTP status cannot tell them apart.
        assertThat(ErrorCode.FILE_READ_ERROR.retryability()).isEqualTo(Retryability.TRANSIENT);
        assertThat(ErrorCode.EVIDENCE_INVALID.retryability()).isEqualTo(Retryability.PERMANENT);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("Every code name is SCREAMING_SNAKE_CASE and leaks no internal detail")
    void namesAreStableTokens(ErrorCode code) {
        assertThat(code.name()).matches("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
    }

    @Test
    @DisplayName("The partner auth failures share ONE opaque code: no key-id enumeration oracle")
    void partnerAuthHasExactlyOneCode() {
        // Anti-enumeration is the point: unknown key, inactive key, bad signature,
        // stale timestamp and replayed nonce all answer AUTH_FAILED. Any code named
        // after a specific check would hand an attacker the oracle the uniform 401
        // message deliberately withholds.
        assertThat(Arrays.stream(ErrorCode.values())
                .map(ErrorCode::name)
                .filter(n -> n.contains("KEY") || n.contains("SIGNATURE")
                        || n.contains("NONCE") || n.contains("TIMESTAMP")))
                .isEmpty();
        assertThat(ErrorCode.AUTH_FAILED.retryability()).isEqualTo(Retryability.PERMANENT);
    }

    @Test
    @DisplayName("A membership refusal has NO dedicated code: unknown and not-yours share TRANSACTION_NOT_FOUND")
    void membershipRefusalHasNoDedicatedCode() {
        // Calque de partnerAuthHasExactlyOneCode ci-dessus, applique au canal JWT
        // (Story 1.10, NFR-P9). Le canal partenaire avait deja fondu ses cinq echecs
        // d'authentification dans AUTH_FAILED ; le canal humain, lui, gardait
        // NOT_A_PARTY a cote de TRANSACTION_NOT_FOUND — soit un oracle d'existence
        // parfait pour tout porteur d'un jeton valide, meme sous un 404 uniforme.
        //
        // CE QUE CE FILTRE GARDE, EXACTEMENT : qu'aucun nom de code ne contienne PARTY,
        // MEMBER, OWNER ou FOREIGN. Il attrape donc la reintroduction sous les noms les
        // plus probables (NOT_A_PARTY lui-meme, MEMBERSHIP_DENIED, NOT_OWNER,
        // FOREIGN_TRANSACTION) — c'est-a-dire le copier-coller et le renommage paresseux.
        //
        // CE QU'IL NE GARDE PAS : un nom qui evite ce vocabulaire. ACCESS_DENIED,
        // NOT_YOURS et NON_PARTICIPANT passeraient tous les quatre mots-cles. Un filtre
        // de noms ne peut pas faire mieux — il ignore ce que le code SIGNIFIE et ou il
        // est emis. La fermeture reelle du trou est ailleurs, et elle est structurelle :
        // AntiEnumerationConventionTest (paquet `web`) asservit que les refus ne se
        // construisent QUE dans une liste blanche de fichiers, avec des messages
        // constants. Un code de refus d'appartenance sous un nom neuf devrait bien etre
        // emis quelque part, et c'est la qu'il devient rouge.
        assertThat(Arrays.stream(ErrorCode.values())
                .map(ErrorCode::name)
                .filter(n -> n.contains("PARTY") || n.contains("MEMBER")
                        || n.contains("OWNER") || n.contains("FOREIGN")))
                .isEmpty();
        // Le code survivant est bien celui de « inexistante », donc celui qui ne
        // confirme rien — un code d'existence uniforme (« EXISTS_BUT_DENIED ») aurait
        // uniformise le vocabulaire tout en gardant l'oracle intact.
        assertThat(ErrorCode.TRANSACTION_NOT_FOUND.retryability()).isEqualTo(Retryability.PERMANENT);
    }
}
