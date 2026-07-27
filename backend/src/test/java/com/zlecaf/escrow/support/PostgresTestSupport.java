package com.zlecaf.escrow.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Conteneur Postgres UNIQUE pour toute la JVM de test, avec une base dédiée par
 * classe de test.
 *
 * <p><b>Le problème.</b> Dix-huit classes de test déclaraient chacune son propre
 * {@code @Container static final PostgreSQLContainer<?>}, avec le même nom d'image
 * et les trois mêmes {@code registry.add}. L'extension {@code @Testcontainers}
 * donne au champ statique un cycle de vie <i>par classe</i> : dix-huit démarrages
 * et dix-huit arrêts de conteneur par build, pour une image identique. Coût direct
 * à chaque exécution de CI, et coût de maintenance à chaque changement d'image ou
 * de propriété — dix-huit endroits à modifier de concert (ledger, bundle
 * QUALITÉ-CI).
 *
 * <p><b>Ce qui est mutualisé, et ce qui ne l'est PAS.</b> Le conteneur est partagé ;
 * la <i>base de données</i> ne l'est pas. Chaque classe reçoit sa base, créée à la
 * demande sur le conteneur commun. C'est délibéré : partager une base unique aurait
 * fait dépendre les suites qui écrivent sans rollback (les {@code @SpringBootTest}
 * passant par MockMvc committent pour de vrai) de leur ordre d'exécution. Ce dépôt
 * a déjà payé ce prix côté frontend — un test de session qui ne passait que si deux
 * autres suites l'avaient précédé. On mutualise le coût, pas l'isolation.
 *
 * <p><b>Arrêt du conteneur.</b> Aucun {@code stop()}, et c'est voulu : le conteneur
 * doit survivre à la dernière classe de test. Testcontainers le confie à son
 * sidecar Ryuk, qui le supprime à la mort de la JVM — c'est le « singleton container
 * pattern » documenté en amont, pas un oubli.
 */
public final class PostgresTestSupport {

    /** Même image que les dix-huit déclarations qu'elle remplace. Un seul endroit désormais. */
    private static final String IMAGE = "postgres:16-alpine";

    /**
     * Conséquence NON évidente du partage, mesurée : dix-huit conteneurs isolés
     * offraient 18 × 100 connexions ; un conteneur partagé n'en offre que 100, alors
     * que les contextes Spring — mis en CACHE et jamais fermés avant la fin de la JVM —
     * conservent chacun leur pool Hikari (10 connexions par défaut). Résultat de la
     * première exécution : {@code FATAL: sorry, too many clients already} et 83 erreurs
     * de chargement de contexte.
     *
     * <p>Le plafond est donc relevé ici, à un seul endroit — ce qui est exactement le
     * bénéfice recherché. Ne PAS « optimiser » en rabotant à la place la taille des
     * pools : les suites de concurrence (nonce, retrait) ont besoin de plusieurs
     * connexions réellement simultanées, et un pool trop étroit les ferait attendre
     * l'une l'autre au lieu de prouver l'arbitrage qu'elles nomment.
     */
    private static final String MAX_CONNECTIONS = "300";

    /**
     * {@code fsync=off} est REPRIS EXPRÈS, il n'est pas décoratif. {@code PostgreSQLContainer}
     * le pose dans son CONSTRUCTEUR (et non dans {@code configure()}, vérifié au bytecode de
     * la 1.21.4 épinglée) : tout appel à {@code withCommand} l'écrase. L'omettre coûterait un
     * vrai fsync à chaque commit de chaque test, régression d'autant plus sournoise ici que le
     * gain sur les démarrages de conteneur la masquait dans le temps total.
     */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE)
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS);

    static {
        POSTGRES.start();
    }

    /** Bases déjà créées — une classe de test peut voir son contexte Spring construit plusieurs fois. */
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();

    private PostgresTestSupport() {}

    /**
     * Enregistre la source de données d'une base FRAÎCHE, dédiée à {@code testClass}.
     *
     * <p>À appeler depuis le {@code @DynamicPropertySource} de la classe de test, qui
     * reste libre d'y ajouter ses propres propriétés :
     *
     * <pre>{@code
     * @DynamicPropertySource
     * static void properties(DynamicPropertyRegistry registry) {
     *     PostgresTestSupport.registerDatabase(registry, MaSuiteTest.class);
     *     registry.add("escrow.auth.ratelimit.max-attempts", () -> "3");
     * }
     * }</pre>
     */
    public static void registerDatabase(DynamicPropertyRegistry registry, Class<?> testClass) {
        String database = databaseNameFor(testClass);
        createIfAbsent(database);

        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Nom de base valide et stable : nom simple en minuscules, suffixé d'une empreinte
     * du nom qualifié. Le suffixe n'est pas cosmétique — deux classes de test homonymes
     * dans des paquets différents partageraient sinon la même base, donc l'isolation
     * qu'on prétend préserver. Tronqué à 63 octets, limite d'identifiant Postgres.
     */
    private static String databaseNameFor(Class<?> testClass) {
        String simple = testClass.getSimpleName().toLowerCase(Locale.ROOT);
        String fingerprint = Integer.toHexString(testClass.getName().hashCode());
        String name = simple + "_" + fingerprint;
        return name.length() <= 63 ? name : name.substring(name.length() - 63);
    }

    private static void createIfAbsent(String database) {
        if (!CREATED.add(database)) {
            return;
        }
        // CREATE DATABASE est interdit dans une transaction : connexion en autocommit,
        // sur la base d'amorçage du conteneur.
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException failed) {
            CREATED.remove(database);
            throw new IllegalStateException(
                    "Création de la base de test " + database + " impossible sur le conteneur partagé",
                    failed);
        }
    }
}
