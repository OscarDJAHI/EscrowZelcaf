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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p><b>Portée : {@code NotFoundException} et {@code ForbiddenException} seules.</b> Ce
 * sont les deux familles par lesquelles un refus peut confirmer une existence. Un 409
 * ({@code ConflictException}) ou un 400 ({@code BadRequestException}) n'est atteint
 * qu'<em>après</em> que les gardes ont admis l'appelant, ou porte sur sa propre entrée :
 * il ne peut rien lui apprendre sur une ressource qu'il n'a pas le droit de connaître.
 * Les élargir noierait la liste blanche sous des dizaines de sites sans rien fermer.
 *
 * <p><b>Reconnaissance par expression régulière et non par sous-chaîne</b> (revue de
 * suivi 1.10) : deux contournements silencieux existaient. Un nom pleinement qualifié
 * ({@code new ApiExceptions.NotFoundException(...)}) ne contenait aucune des deux
 * sous-chaînes cherchées, donc n'était pas même compté ; et l'inspection de la
 * concaténation s'arrêtant à la ligne physique portant le {@code new}, il suffisait que
 * l'appel passe à la ligne — ce que le dépôt fait au-delà de ~120 colonnes — pour que le
 * message et ses {@code +} échappent entièrement. Les deux formes rouvraient l'oracle
 * avec ce test au vert.
 */
class AntiEnumerationConventionTest {

    /** Surefire s'exécute avec le module {@code backend/} pour répertoire de travail. */
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /**
     * Un {@code new} de refus, qualifié ou non. Le préfixe optionnel
     * {@code (Paquet\.)*} est ce qui attrape {@code new ApiExceptions.NotFoundException(}
     * comme {@code new NotFoundException(}. L'ancre après {@code new\s+} garantit qu'un
     * type dont le nom se <em>termine</em> par la même chaîne — {@code new
     * EvidenceNotFoundException(} du port de stockage — ne matche pas : le groupe de
     * qualification exige un point, donc il ne peut pas consommer « Evidence ».
     */
    private static final Pattern REFUSAL_CTOR = Pattern.compile(
            "new\\s+(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*(?:NotFound|Forbidden)Exception\\s*\\(");

    /**
     * Toutes les façons de fabriquer un message variable en Java. La concaténation n'est
     * que la plus évidente : {@code String.format}, {@code "…".formatted(id)},
     * {@code MessageFormat} et {@code concat} portent exactement le même risque.
     */
    private static final Pattern INTERPOLATION = Pattern.compile(
            "\\+|String\\s*\\.\\s*format\\s*\\(|\\.\\s*formatted\\s*\\(|MessageFormat|\\.\\s*concat\\s*\\(");

    /** Borne de sécurité : aucun appel de constructeur réel du dépôt ne s'étale autant. */
    private static final int MAX_STATEMENT_LINES = 12;

    /**
     * Le fichier des fabriques, traité à part et volontairement <b>non plafonné</b>.
     *
     * <p>{@code project-context.md} ordonne à toute story aval d'« ajouter la sienne pour
     * wallet, ticket, fil de litige, dossier KYB » — c'est-à-dire d'ajouter une fabrique
     * <em>ici</em>, le seul endroit que la convention bénit. Un compte exact aurait donc
     * rendu ce test rouge en récompense d'une conformité parfaite, avec pour seul remède
     * d'incrémenter un chiffre : une friction qui n'apprend rien à personne. Le plancher
     * de 2 reste asservi (les deux fabriques d'origine ne peuvent pas disparaître en
     * silence) et la propriété qui compte vraiment — message constant, sans identifiant —
     * s'applique ici comme partout ailleurs.
     */
    private static final String FACTORY_FILE = "com/zlecaf/escrow/web/ApiExceptions.java";

    /**
     * Les <b>seuls</b> fichiers, hors fabriques, autorisés à construire directement un
     * refus, et le nombre exact d'occurrences attendues dans chacun. Le compte fait partie
     * de la liste blanche : sans lui, un fichier déjà autorisé deviendrait un passe-droit
     * permanent où l'on pourrait glisser un littéral fautif de plus.
     *
     * <p>La clé est le chemin <b>relatif à {@code src/main/java}</b> et non le nom de
     * fichier simple (revue de suivi 1.10) : deux classes homonymes dans deux paquets
     * différents fusionnaient leurs compteurs, si bien qu'un site fautif dans l'une
     * pouvait être absorbé par le quota de l'autre.
     *
     * <ul>
     *   <li><b>EvidenceService (1)</b> — la garde « pièce à soi », seul 403 applicatif
     *       survivant et exception <em>documentée</em> : son destinataire est déjà partie à
     *       la transaction et voit déjà cette pièce (par {@code GET /{id}/evidence} et, sans
     *       plafond, par le téléchargement pièce à pièce), donc le refus honnête ne révèle
     *       rien.</li>
     *   <li><b>EscrowService (1)</b> et <b>AuthService (1)</b> —
     *       {@code "Acting user not found"} et {@code "User not found"}. Ils portent sur
     *       l'<b>identité propre de l'appelant</b>, résolue depuis son propre JWT : il ne
     *       peut pas les faire varier pour sonder l'espace des identifiants d'autrui,
     *       donc ils n'énumèrent rien. Cette exemption est celle que la Verification de
     *       la spec 1.10 nomme déjà explicitement (« quatre lignes attendues », dont ces
     *       deux littéraux préexistants).</li>
     * </ul>
     */
    private static final Map<String, Integer> ALLOWED_CONSTRUCTION_SITES = Map.of(
            "com/zlecaf/escrow/service/EvidenceService.java", 1,
            "com/zlecaf/escrow/service/EscrowService.java", 1,
            "com/zlecaf/escrow/service/AuthService.java", 1);

    @Test
    @DisplayName("Refusals are built ONLY by the factories and by the explicitly whitelisted sites")
    void refusalsAreBuiltOnlyWhereTheWhitelistAllows() {
        Map<String, Integer> actual = new TreeMap<>();
        for (Occurrence occurrence : scanForDirectConstructions()) {
            actual.merge(occurrence.file(), 1, Integer::sum);
        }

        Integer factories = actual.remove(FACTORY_FILE);
        assertThat(factories)
                .as("les deux fabriques d'origine ne peuvent pas disparaître ; en ajouter est la convention")
                .isNotNull()
                .isGreaterThanOrEqualTo(2);

        // Égalité EXACTE dans les deux directions pour tout le reste. Un nouveau site rend
        // le test rouge (le but) ; mais un site qui DISPARAÎT le rend rouge aussi, et c'est
        // voulu : si la garde « pièce à soi » s'évaporait dans un refactor, la frontière
        // assumée de la story serait perdue sans que rien ne le dise.
        assertThat(actual)
                .as("tout site de construction directe hors liste blanche rouvre l'oracle par le message")
                .isEqualTo(new TreeMap<>(ALLOWED_CONSTRUCTION_SITES));
    }

    @Test
    @DisplayName("No refusal interpolates anything: a constant literal cannot carry an identifier")
    void whitelistedRefusalsCarryConstantMessagesOnly() {
        List<String> interpolating = scanForDirectConstructions().stream()
                .filter(o -> INTERPOLATION.matcher(o.statement()).find())
                .map(Occurrence::describe)
                .toList();

        // La seconde moitié de la propriété. La liste blanche dit OÙ l'on construit ; ceci
        // dit CE QU'ON construit. Un `"Transaction " + txId + " not found"` glissé dans un
        // fichier déjà autorisé passerait le test ci-dessus sans problème — et rouvrirait
        // l'oracle tout seul. Un message sans interpolation ne PEUT pas porter
        // d'identifiant : c'est une propriété syntaxique, pas une intention.
        //
        // L'inspection porte sur l'appel ENTIER, reconstruit jusqu'à sa parenthèse
        // fermante, et non sur la seule ligne du `new` : la forme
        //     throw new NotFoundException(
        //             "Transaction " + txId + " not found");
        // est celle que produit spontanément un formateur à 120 colonnes, et elle passait
        // au vert.
        assertThat(interpolating)
                .as("un message interpolé peut porter un identifiant, donc confirmer une existence")
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
                .map(Source::file)
                .toList();

        assertThat(offenders)
                .as("une constante que plus rien n'émet est ce que le prochain développeur copiera")
                .isEmpty();
    }

    // --- lecture des sources ------------------------------------------------------

    private record Source(String file, String content) {}

    private record Occurrence(String file, int lineNumber, String statement) {
        String describe() {
            return file + ":" + lineNumber + " -> " + statement.trim();
        }
    }

    private static List<Occurrence> scanForDirectConstructions() {
        List<Occurrence> occurrences = new ArrayList<>();
        readAllSources().forEach(source -> {
            String[] lines = source.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = REFUSAL_CTOR.matcher(stripLineComment(lines[i]));
                if (matcher.find()) {
                    occurrences.add(new Occurrence(source.file(), i + 1,
                            statementFrom(lines, i, matcher.start())));
                }
            }
        });
        return occurrences;
    }

    /**
     * Reconstruit l'appel depuis son {@code new} jusqu'à la parenthèse qui l'équilibre,
     * en traversant les sauts de ligne.
     *
     * <p>Deux approximations assumées, sûres dans le seul sens qui compte : les
     * commentaires de fin de ligne sont retirés avant l'analyse (un {@code //} dans un
     * littéral tronquerait la ligne — aucun message de refus n'en contient), et les
     * parenthèses à l'intérieur d'un littéral compteraient dans l'équilibre. Dans les deux
     * cas l'appel reconstruit serait plus <em>long</em> que le vrai, donc au pire une
     * interpolation voisine serait signalée à tort — un faux positif bruyant, jamais un
     * faux négatif silencieux.
     */
    private static String statementFrom(String[] lines, int startLine, int startColumn) {
        StringBuilder statement = new StringBuilder();
        int depth = 0;
        boolean opened = false;
        int lastLine = Math.min(lines.length - 1, startLine + MAX_STATEMENT_LINES - 1);
        for (int i = startLine; i <= lastLine; i++) {
            String code = stripLineComment(lines[i]);
            for (int c = (i == startLine ? startColumn : 0); c < code.length(); c++) {
                char ch = code.charAt(c);
                statement.append(ch);
                if (ch == '(') {
                    depth++;
                    opened = true;
                } else if (ch == ')' && --depth == 0 && opened) {
                    return statement.toString();
                }
            }
            statement.append(' ');
        }
        return statement.toString();
    }

    private static String stripLineComment(String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
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
     *
     * <p>La clé est le chemin relatif à {@code src/main/java}, séparateurs normalisés en
     * {@code /} pour que la liste blanche reste lisible et portable.
     */
    private static Source read(Path path) {
        try {
            String relative = MAIN_SOURCES.relativize(path).toString().replace('\\', '/');
            return new Source(relative, new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
