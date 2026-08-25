package com.zlecaf.escrow.observability;

import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MÉTRIQUES EXPOSÉES ET SCRAPABLES (Story 11.4, AC1 — premier volet).
 *
 * <p><b>Ce que ce fichier garde.</b> L'AC1 nomme quatre familles de métriques : JVM, HTTP,
 * pool Hikari et files du broker. Une assertion « la réponse n'est pas vide » les
 * satisferait toutes les quatre par accident — l'endpoint rend des centaines de séries dès
 * qu'il répond. Chaque famille est donc assérée par un nom de métrique PRÉCIS, celui que
 * Prometheus devra effectivement scraper.
 *
 * <p><b>Pourquoi la famille « broker » n'est pas assérée ici, et où elle l'est.</b> Spring
 * AMQP n'expose aucune métrique de PROFONDEUR DE FILE côté client : la profondeur est un
 * fait du broker, pas de son client, et aucune dépendance ajoutée à cette application ne la
 * ferait apparaître. Elle vient du plugin `rabbitmq_prometheus` du broker lui-même, scrapé
 * séparément. L'écart est nommé plutôt que maquillé par une assertion sur une métrique
 * cliente qui ne dit pas ce que l'AC demande.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, ObservabilityIntegrationTest.class);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposeLEndpointDeMetriquesPourLeScraper() throws Exception {
        // Appariée d'entrée : l'endpoint RÉPOND. Sans ce temps, toutes les assertions de
        // contenu ci-dessous porteraient sur une réponse d'erreur et échoueraient pour la
        // mauvaise raison — on croirait à des métriques manquantes là où c'est l'exposition
        // entière qui serait tombée.
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void publieLesTroisFamillesDeMetriquesQueCetteApplicationPeutPorter() throws Exception {
        // Une requête RÉELLE d'abord, et ce n'est pas une précaution de confort.
        // `http_server_requests_seconds` n'existe pas tant qu'aucune requête n'a été
        // enregistrée : la série naît du TRAFIC, pas de la configuration. Le premier jet de
        // ce test scrutait un contexte neuf et échouait sur cette seule famille — le
        // diagnostic a de la valeur, parce qu'une règle d'alerte assise sur une série qui
        // n'apparaît qu'au premier appel ne dit rien d'une application au repos.
        //
        // Assérer APRÈS avoir généré du trafic prouve donc l'enregistrement effectif, là où
        // une assertion sur le seul nom aurait prouvé qu'il est compilé quelque part.
        //
        // Le trafic est un PREMIER SCRAPE, et non un appel à `/actuator/health` comme la
        // version précédente le faisait : la santé rend 503 sous la suite, faute de broker
        // joignable, et le test échouait alors sur le générateur de trafic plutôt que sur
        // ce qu'il mesure. Se scraper soi-même est déterministe et n'ajoute aucune
        // dépendance — le premier appel produit la série que le second observe.
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());

        String corps = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // JVM — la mémoire est le premier symptôme d'un incident de production.
        assertThat(corps).contains("jvm_memory_used_bytes");
        // HTTP — c'est la série qui porte le taux de 5xx, donc la première règle d'alerte
        // de l'AC3. Une alerte assise sur une série absente ne se déclenche jamais ET ne
        // rougit jamais : la vérifier ici est ce qui empêche cette preuve creuse.
        assertThat(corps).contains("http_server_requests_seconds");
        // Pool Hikari — la saturation du pool est la troisième règle minimale de l'AC3, et
        // ce dépôt a DÉJÀ touché un plafond de connexions une fois (83 erreurs, bundle
        // QUALITÉ-CI). Ce n'est pas une métrique théorique.
        assertThat(corps).contains("hikaricp_connections");
    }

    @Test
    void marqueChaqueSerieDuNomDeLApplication() throws Exception {
        // Sans ce tag, deux déploiements scrapés par le même Prometheus rendent des séries
        // indistinguables, et une alerte ne sait plus qui elle accuse.
        String corps = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(corps).contains("application=\"escrow-core\"");
    }

    @Test
    void nOuvreAucunAutreEndpointActuator() throws Exception {
        // Assertion négative APPARIÉE aux positives ci-dessus : elles prouvent que
        // l'actuator répond, celle-ci prouve qu'il ne répond pas à tout. Sans le couple,
        // un actuator entièrement cassé satisferait celle-ci et rien ne le signalerait.
        //
        // `env` et `beans` sont choisis nommément : le premier publie la configuration
        // résolue, secrets compris ; le second la topologie interne du contexte.
        //
        // ⚠️ CE QUE CE TEST GARDE, ET CE QU'IL NE GARDE PAS. Il garde la COUCHE SÉCURITÉ :
        // ces chemins ne sont pas dans la liste `permitAll` de `SecurityConfig`, donc ils
        // retombent sur `anyRequest().authenticated()`. C'est bien un 403 et non un 404 —
        // la sécurité tranche AVANT que l'absence d'endpoint ne se voie. Il ne garde donc
        // PAS la liste d'exposition d'`application.yml` : ajouter `env` à `include` laisse
        // ce test vert, puisque la sécurité le refuserait toujours. Les deux couches sont
        // réelles, ce test n'en couvre qu'une, et le dire vaut mieux que le laisser croire.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isForbidden());
    }
}
