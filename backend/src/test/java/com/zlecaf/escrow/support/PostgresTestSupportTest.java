package com.zlecaf.escrow.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Vérifie que {@link PostgresTestSupport} tient ses promesses — <b>telles que le serveur
 * les applique</b>, et non telles que le code a cru les configurer.
 *
 * <p><b>Pourquoi ce test existe.</b> Il a été écrit après s'être fait prendre :
 * {@code withCommand(...)} écrase la commande que {@code PostgreSQLContainer} pose dans
 * son CONSTRUCTEUR, si bien qu'ajouter {@code max_connections} avait silencieusement
 * supprimé {@code fsync=off}. Rien n'était rouge — la suite restait verte et le temps
 * total avait même baissé, le gain sur les démarrages de conteneur masquant la
 * régression. Une option de conteneur qu'aucune assertion ne relit est une option dont
 * personne ne sait si elle est appliquée ; une montée de version de Testcontainers qui
 * changerait l'ordre ou la forme de cette commande repasserait exactement ici.
 *
 * <p><b>Pourquoi PAS de contexte Spring.</b> Le support n'est qu'un conteneur et une
 * chaîne JDBC : le faire vérifier par une tranche {@code @DataJpaTest} n'apportait que
 * des dépendances étrangères au sujet (la première tentative est morte sur H2, puis sur
 * le métamodèle JPA). On instancie donc un registre qui capture, et on relit par une
 * connexion directe.
 */
class PostgresTestSupportTest {

    private static final Map<String, Object> PROPERTIES = new LinkedHashMap<>();

    @BeforeAll
    static void registerAsARealSuiteWould() {
        // Exactement l'appel que chaque suite écrit dans son @DynamicPropertySource ;
        // le registre se contente de retenir ce que le support y dépose.
        PostgresTestSupport.registerDatabase(new DynamicPropertyRegistry() {
            @Override
            public void add(String name, Supplier<Object> valueSupplier) {
                PROPERTIES.put(name, valueSupplier.get());
            }
        }, PostgresTestSupportTest.class);
    }

    private static String property(String name) {
        Object value = PROPERTIES.get(name);
        assertThat(value).as("propriété %s enregistrée par le support", name).isNotNull();
        return value.toString();
    }

    private static String queryOne(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        property("spring.datasource.url"),
                        property("spring.datasource.username"),
                        property("spring.datasource.password"));
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("la requête %s a rendu une ligne", sql).isTrue();
            return rows.getString(1);
        }
    }

    @Test
    @DisplayName("le plafond de connexions relevé est bien celui que le serveur applique")
    void raisedConnectionCeilingIsTheOneTheServerApplies() throws SQLException {
        // 100 (le défaut) ferait revenir le « FATAL: sorry, too many clients already »
        // qui a produit 83 erreurs de chargement de contexte lors de la migration.
        assertThat(queryOne("select setting from pg_settings where name = 'max_connections'"))
                .isEqualTo("300");
    }

    @Test
    @DisplayName("fsync=off survit à withCommand — c'est précisément ce qui avait été perdu")
    void fsyncStaysDisabledDespiteTheCustomCommand() throws SQLException {
        assertThat(queryOne("select setting from pg_settings where name = 'fsync'")).isEqualTo("off");
    }

    @Test
    @DisplayName("la classe reçoit SA base, pas la base d'amorçage partagée du conteneur")
    void eachTestClassGetsItsOwnDatabase() throws SQLException {
        // Si cette valeur était « test » (la base d'amorçage), toutes les suites
        // écriraient au même endroit et l'isolation que ce support revendique — la
        // raison même de ne pas partager une base unique — n'existerait pas.
        assertThat(queryOne("select current_database()")).startsWith("postgrestestsupporttest_");
    }
}
