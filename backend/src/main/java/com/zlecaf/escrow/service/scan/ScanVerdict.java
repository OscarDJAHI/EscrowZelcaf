package com.zlecaf.escrow.service.scan;

/**
 * Verdict rendu par le port {@link MalwareScanner} (Story 1.8).
 *
 * <p>Deux états seulement, et pas de troisième : « sain » et « infecté ». Un
 * « je ne sais pas » n'a délibérément aucune représentation ici — il se signale
 * par une {@link MalwareScanUnavailableException}, pour qu'aucun appelant ne
 * puisse traiter une absence de verdict comme un verdict favorable.
 *
 * @param infected  vrai si le moteur a déclenché sur ce contenu
 * @param signature nom de la signature déclenchée, {@code null} quand le contenu
 *                  est sain. Destiné au journal serveur et à l'entrée d'audit,
 *                  <b>jamais</b> à une réponse HTTP : le rendre au déposant
 *                  transformerait l'endpoint en banc d'essai d'évasion.
 */
public record ScanVerdict(boolean infected, String signature) {

    private static final ScanVerdict CLEAN = new ScanVerdict(false, null);

    /** Contenu sain. Instance partagée : le record est immuable. */
    public static ScanVerdict clean() {
        return CLEAN;
    }

    /**
     * Contenu infecté par la signature nommée.
     *
     * @throws IllegalArgumentException si la signature est absente ou vide — un
     *         rejet que l'opérateur ne peut pas nommer ne serait pas auditable, et
     *         l'adaptateur doit alors traiter la réponse du moteur comme
     *         incomprise (donc comme une indisponibilité) plutôt que la faire
     *         passer pour un verdict.
     */
    public static ScanVerdict infected(String signature) {
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Un verdict positif doit nommer sa signature");
        }
        return new ScanVerdict(true, signature);
    }
}
