package com.zlecaf.escrow.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jauge de l'invariant de ségrégation des fonds (Story 11.4, T6 — AC4, AD-16, NFR-P25).
 *
 * <p><b>Ce que cette classe livre, et ce qu'elle ne livre pas.</b> Elle pose la
 * MÉTRIQUE et rien d'autre. Le job de rapprochement — planifié quotidiennement en fin
 * de jour ouvrable et déclenchable à la demande — appartient à l'Epic 4, aux côtés des
 * jobs SLA dans {@code scheduler/} (ARCHITECTURE-SPINE §36). L'AC4 demande que la règle
 * d'alerte soit « en place avant la mise en service du circuit financier, qui n'aura
 * qu'à émettre la métrique » : c'est exactement ce découpage.
 *
 * <p><b>Le nom de la métrique est une interface, pas un détail.</b>
 * {@value #METRIC_NAME}, exposée par Prometheus sous {@code escrow_segregation_deviation_usd}.
 * La règle d'alerte {@code SegregationInvariantBreached} (infra/observability/rules/)
 * et le tableau de bord la désignent par ce nom : le renommer casse les deux, en
 * silence — une règle sur une série inexistante ne se déclenche jamais et ne rougit
 * jamais non plus. La constante est publique pour que le test l'assère par référence
 * plutôt qu'en recopiant la chaîne, ce qui rendrait le renommage indolore côté test et
 * fatal côté alerte.
 *
 * <p><b>Pourquoi {@code strongReference}.</b> Micrometer ne retient par défaut qu'une
 * référence FAIBLE vers l'objet d'état d'une jauge : si rien d'autre ne le tient, le
 * ramasse-miettes l'emporte et la jauge se met à rendre {@code NaN} pour toujours —
 * sans erreur, sans log. Le bean Spring est déjà fortement référencé par le contexte,
 * donc le cas ne se produirait pas ici ; la mention est explicite parce que ce défaut
 * est invisible à la relecture et se manifeste des semaines plus tard, sous charge.
 */
@Configuration
public class SegregationMetrics {

    /** Nom Micrometer. Rendu {@code escrow_segregation_deviation_usd} côté Prometheus. */
    public static final String METRIC_NAME = "escrow.segregation.deviation";

    /**
     * Source par défaut, effacée dès que l'Epic 4 déclare la sienne.
     *
     * <p>{@link ConditionalOnMissingBean} EST le contrat de passation : la Story 4.3
     * n'a rien à supprimer ici, elle déclare son bean et celui-ci disparaît.
     */
    @Bean
    @ConditionalOnMissingBean(SegregationDeviationSource.class)
    public SegregationDeviationSource noFinancialCircuitDeviationSource() {
        return new NoFinancialCircuitDeviationSource();
    }

    @Bean
    public MeterBinder segregationDeviationGauge(SegregationDeviationSource source) {
        return registry -> Gauge.builder(METRIC_NAME, source,
                        s -> s.currentDeviation().orElse(Double.NaN))
                .description("Ecart du rapprochement de segregation des fonds (AD-16) : "
                        + "wallets + sequestres + reservations + transit - miroir cantonnement. "
                        + "NaN tant qu'aucun circuit financier n'est en service ; tout ecart non nul est une alerte critique.")
                .baseUnit("usd")
                .strongReference(true)
                .register(registry);
    }
}
