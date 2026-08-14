package com.zlecaf.escrow.observability;

import java.util.OptionalDouble;

/**
 * Source par défaut : <b>il n'y a pas de circuit financier</b>.
 *
 * <p>C'est l'état réel du dépôt tant que l'Epic 4 n'a pas livré le grand livre : ni
 * wallet, ni séquestre, ni compte cantonné, ni job de rapprochement. Rendre
 * {@link OptionalDouble#empty()} fait afficher {@code NaN} à la jauge, ce qui se lit
 * « inconnu » — et non « écart nul », qui serait un mensonge tranquille.
 *
 * <p><b>Pourquoi une classe et pas une lambda.</b> Elle porte le contrat de
 * remplacement : la Story 4.3 déclare son propre bean {@link SegregationDeviationSource}
 * et celui-ci s'efface (voir {@code SegregationMetrics}). Une lambda anonyme dans une
 * configuration n'aurait offert aucun endroit où écrire cela, ni aucun nom à chercher.
 *
 * <p><b>Pourquoi aucun endpoint ne pilote cette valeur.</b> Interdiction posée par la
 * Story 1.10 (anti-énumération) et reconduite par le T0 de la 2.4 : aucune surface de
 * test n'entre en production. Un {@code POST /actuator/reconciliation-deviation} aurait
 * rendu l'AC4 démontrable en trois lignes et ouvert une porte permanente. L'écart
 * factice de l'AC4 est donc injecté par REMPLACEMENT DE BEAN dans le test, jamais par
 * un appel — décision Q3 du 2026-08-11.
 */
public final class NoFinancialCircuitDeviationSource implements SegregationDeviationSource {

    @Override
    public OptionalDouble currentDeviation() {
        return OptionalDouble.empty();
    }
}
