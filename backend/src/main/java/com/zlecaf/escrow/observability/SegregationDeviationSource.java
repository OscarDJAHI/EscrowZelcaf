package com.zlecaf.escrow.observability;

import java.util.OptionalDouble;

/**
 * Source de l'écart de rapprochement de ségrégation des fonds (AD-16, NFR-P25).
 *
 * <p><b>CONTRAT POUR L'EPIC 4 — c'est le seul point à implémenter.</b> L'AC4 de la
 * Story 11.4 dit que la règle d'alerte doit être en place « avant la mise en service
 * du circuit financier, qui n'aura qu'à émettre la métrique ». Cette interface EST
 * cette promesse : la Story 4.3 (grand livre en partie double) fournit un bean qui
 * remplace {@link NoFinancialCircuitDeviationSource}, et rien d'autre ne bouge — ni
 * la jauge, ni la règle d'alerte, ni le tableau de bord.
 *
 * <p><b>Ce que la valeur doit être.</b> L'écart de la formule AD-16, à ne pas
 * réinventer : {@code somme(soldes wallets) + somme(séquestres) + somme(réservations)
 * + somme(fonds en transit PSP) − compte miroir du cantonnement}. En USD (devise
 * unique au MVP, FR-P26). <b>Signe compris</b> : un écart négatif est aussi grave
 * qu'un écart positif — il dit que le compte cantonné porte MOINS que ce que le grand
 * livre prétend, ce qui est le sens le plus dangereux des deux. La règle d'alerte
 * teste {@code != 0} et non {@code > 0} précisément pour cela.
 *
 * <p><b>Ce que la valeur ne doit PAS être.</b> Jamais {@code 0} pour dire « je ne sais
 * pas ». Zéro signifie « rapprochement fait, invariant tenu » — c'est une affirmation
 * forte, et la rendre par défaut transformerait l'absence de circuit financier en
 * certitude que tout va bien. D'où {@link OptionalDouble} plutôt qu'un {@code double} :
 * l'absence de valeur est un état représentable, pas une convention à deviner.
 *
 * <p><b>Fréquence.</b> Aucune n'est gravée ici, et c'est délibéré (SOLUTION-DESIGN
 * §101 : paramètre par corridor). Cette source est LUE à chaque scrape ; c'est le job
 * de rapprochement de l'Epic 4 qui décide quand il RECALCULE — quotidien fin de jour
 * ouvrable au MVP sur tous les corridors, plancher réglementaire réel tranché par
 * AR-P2 (Instruction BCEAO 001-01-2024 art. 48.4), resserrable par corridor.
 *
 * <p><b>⚠️ CE QUE CETTE MÉTRIQUE NE PEUT PAS DIRE, et qui reste à l'Epic 4.</b> Une
 * jauge à {@code NaN} ne distingue pas « pas encore de circuit financier » de « le job
 * de rapprochement est mort ». Le second cas est un incident silencieux : plus aucun
 * écart n'est calculé, donc plus aucune alerte, et tout paraît calme. Y répondre exige
 * un horodatage de dernière exécution réussie — donc un job, qui n'existe pas encore.
 * La Story 4.3 doit l'exposer et lui adosser sa propre règle de fraîcheur.
 */
@FunctionalInterface
public interface SegregationDeviationSource {

    /**
     * @return l'écart courant en USD, ou {@link OptionalDouble#empty()} tant qu'aucun
     *         circuit financier n'est en service. Ne rend jamais {@code 0} par défaut.
     */
    OptionalDouble currentDeviation();
}
