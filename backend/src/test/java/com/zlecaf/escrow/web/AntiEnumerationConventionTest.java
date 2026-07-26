package com.zlecaf.escrow.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrouille la <em>convention</em> anti-énumération, là où
 * {@code ErrorCodeContractTest} ne verrouille que le vocabulaire (Story 1.10, NFR-P9).
 *
 * <p><b>Le trou que ce test ferme.</b> Retirer la constante d'enum a bloqué le
 * {@code code} : plus aucun endpoint ne peut nommer un refus d'appartenance. Mais rien
 * n'empêchait un futur endpoint d'écrire à la main
 * {@code new NotFoundException("Transaction " + id + " not found")} et de rouvrir
 * l'oracle par le <b>message</b> — même statut, même code, et pourtant deux réponses
 * distinguables, donc la propriété morte en silence. Toutes les assertions du dépôt qui
 * ne vérifient que le <em>type</em> d'exception resteraient vertes.
 *
 * <p>La parade est structurelle : les exceptions de refus se construisent dans une
 * <b>fabrique unique</b> ({@link ApiExceptions#transactionNotFound()},
 * {@link ApiExceptions#evidenceNotFound()}), jamais à un site de jet. Ce test asservit
 * cette règle en lisant les sources, faute de quoi elle ne serait qu'une phrase de
 * documentation.
 *
 * <p><b>Il doit échouer à tout ajout</b>, exactement comme {@code ErrorCodeContractTest}
 * : c'est le point. Un nouveau site de construction directe n'est pas interdit, il est
 * rendu <em>délibéré</em> — l'auteur doit venir écrire ici pourquoi son cas n'énumère
 * rien, ce qui est précisément la réflexion qu'un copier-coller saute.
 */
class AntiEnumerationConventionTest {

    /** Surefire s'exécute avec le module {@code backend/} pour répertoire de travail. */
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final String NOT_FOUND_CTOR = "new NotFoundException(";
    private static final String FORBIDDEN_CTOR = "new ForbiddenException(";

    /**
     * Les <b>seuls</b> fichiers autorisés à construire directement un refus, et le
     * nombre exact d'occurrences attendues dans chacun. Le compte fait partie de la
     * liste blanche : sans lui, un fichier déjà autorisé deviendrait un passe-droit
     * permanent où l'on pourrait glisser un littéral fautif de plus.
     *
     * <ul>
     *   <li><b>ApiExceptions.java (2)</b> — les deux fabriques elles-mêmes. C'est
     *       l'endroit dont l'existence rend la règle tenable.</li>
     *   <li><b>EvidenceService.java (1)</b> — la garde « pièce à soi », seul 403
     *       applicatif survivant et exception <em>documentée</em> : son destinataire est
     *       déjà partie à la transaction et voit déjà cette pièce via
     *       {@code GET /{id}/evidence}, donc le refus honnête ne révèle rien.</li>
     *   <li><b>EscrowService.java (1)</b> et <b>AuthService.java (1)</b> —
     *       {@code "Acting user not found"} et {@code "User not found"}. Ils portent sur
     *       l'<b>identité propre de l'appelant</b>, résolue depuis son propre JWT : il ne
     *       peut pas les faire varier pour sonder l'espace des identifiants d'autrui,
     *       donc ils n'énumèrent rien. Cette exemption est celle que la Verification de
     *       la spec 1.10 nomme déjà explicitement (« quatre lignes attendues », dont ces
     *       deux littéraux préexistants).</li>
     * </ul>
     */
    private static final Map<String, Integer> ALLOWED_CONSTRUCTION_SITES = Map.of(
            "ApiExceptions.java", 2,
            "EvidenceService.java", 1,
            "EscrowService.java", 1,
            "AuthService.java", 1);

    @Test
    @DisplayName("Refusals are built ONLY by the factories and by the explicitly whitelisted sites")
    void refusalsAreBuiltOnlyWhereTheWhitelistAllows() {
        Map<String, Integer> actual = new TreeMap<>();
        for (Occurrence occurrence : scanForDirectConstructions()) {
            actual.merge(occurrence.fileName(), 1, Integer::sum);
        }

        // Égalité EXACTE dans les deux directions. Un nouveau site rend le test rouge
        // (le but) ; mais un site qui DISPARAÎT le rend rouge aussi, et c'est voulu :
        // si la garde « pièce à soi » s'évaporait dans un refactor, la frontière assumée
        // de la story serait perdue sans que rien ne le dise.
        assertThat(actual)
                .as("tout site de construction directe hors liste blanche rouvre l'oracle par le message")
                .isEqualTo(new TreeMap<>(ALLOWED_CONSTRUCTION_SITES));
    }

    @Test
    @DisplayName("No whitelisted refusal interpolates anything: a constant literal cannot carry an identifier")
    void whitelistedRefusalsCarryConstantMessagesOnly() {
        List<String> interpolating = scanForDirectConstructions().stream()
                .filter(o -> o.line().contains("+"))
                .map(Occurrence::describe)
                .toList();

        // La seconde moitié de la propriété. La liste blanche dit OÙ l'on construit ; ceci
        // dit CE QU'ON construit. Un `"Transaction " + txId + " not found"` glissé dans un
        // fichier déjà autorisé passerait le test ci-dessus sans problème — et rouvrirait
        // l'oracle tout seul. Un message sans concaténation ne PEUT pas porter
        // d'identifiant : c'est une propriété syntaxique, pas une intention.
        assertThat(interpolating)
                .as("un message concaténé peut porter un identifiant, donc confirmer une existence")
                .isEmpty();
    }

    @Test
    @DisplayName("The deleted membership code is named nowhere in src/main — the grep the javadoc promises")
    void theDeletedMembershipCodeIsGoneFromProductionSources() {
        // La javadoc de TRANSACTION_NOT_FOUND affirme qu'un grep de l'ancien nom revient
        // vide. C'était tenu par une ligne de vérification manuelle dans un markdown de
        // spec, c'est-à-dire par personne dès la story suivante. Ici c'est une assertion.
        //
        // Le nom est épelé par concaténation pour que ce test ne soit pas lui-même la
        // seule occurrence restante et ne s'auto-satisfasse pas s'il finissait un jour
        // sous src/main.
        String deletedCode = "NOT_A" + "_PARTY";
        List<String> offenders = readAllSources()
                .filter(source -> source.content().contains(deletedCode))
                .map(Source::fileName)
                .toList();

        assertThat(offenders)
                .as("une constante que plus rien n'émet est ce que le prochain développeur copiera")
                .isEmpty();
    }

    // --- lecture des sources ------------------------------------------------------

    private record Source(String fileName, String content) {}

    private record Occurrence(String fileName, int lineNumber, String line) {
        String describe() {
            return fileName + ":" + lineNumber + " -> " + line.trim();
        }
    }

    private static List<Occurrence> scanForDirectConstructions() {
        List<Occurrence> occurrences = new ArrayList<>();
        readAllSources().forEach(source -> {
            String[] lines = source.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(NOT_FOUND_CTOR) || lines[i].contains(FORBIDDEN_CTOR)) {
                    occurrences.add(new Occurrence(source.fileName(), i + 1, lines[i]));
                }
            }
        });
        return occurrences;
    }

    private static Stream<Source> readAllSources() {
        assertThat(MAIN_SOURCES)
                .as("répertoire de travail attendu : le module backend/ (convention Surefire)")
                .isDirectory();
        try (Stream<Path> paths = Files.walk(MAIN_SOURCES)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(AntiEnumerationConventionTest::read)
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Lecture en ISO-8859-1 et non en UTF-8 : plusieurs fichiers du dépôt ne sont pas
     * en UTF-8 valide (défaut consigné au ledger), et {@code readString} y lèverait une
     * {@link java.nio.charset.MalformedInputException}. Ce jeu de caractères mappe
     * chaque octet sur un caractère sans jamais échouer — les sous-chaînes ASCII
     * cherchées ici restent exactes quoi qu'il arrive au reste du fichier.
     */
    private static Source read(Path path) {
        try {
            return new Source(path.getFileName().toString(),
                    new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
