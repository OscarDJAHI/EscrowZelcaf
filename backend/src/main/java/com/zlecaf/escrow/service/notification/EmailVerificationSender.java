package com.zlecaf.escrow.service.notification;

/**
 * Port d'envoi du code de vérification e-mail (Story 2.4, décision T0).
 *
 * <p><b>Pourquoi un port et pas un client SMTP.</b> Aucun transport e-mail n'existe dans ce
 * dépôt, et ce n'est pas un oubli : la Story 8.1 est le spike dont c'est l'objet — choix du
 * fournisseur email/SMS jugé sur la délivrabilité par corridor ZLECAf, les coûts et la
 * conformité. Une story d'onboarding n'a pas à trancher cela au passage. Le domaine parle
 * donc à cette interface, et 8.1 branchera son adaptateur derrière sans rien réécrire ici.
 *
 * <p><b>Ce que cela coûte, dit franchement :</b> tant que le seul adaptateur est celui de
 * développement, <b>aucun e-mail n'atteint une vraie boîte</b>. Le parcours est complet et
 * asservissable de bout en bout ; sa dernière marche est une ligne d'outbox et un journal.
 *
 * <p><b>Le code passe en paramètre, jamais par la base.</b> L'outbox consigne qu'un envoi
 * a eu lieu, pas ce qu'il contenait : une credential lisible par tout ce qui lit la base
 * n'en est plus une. Corollaire opposable à toute « facilité de développement » future :
 * il n'existe et n'existera <b>aucun endpoint de lecture d'OTP</b>, fût-il gardé par un
 * profil. Ce serait une porte dérobée d'authentification, et
 * {@code ProductionApiSurfaceIntegrationTest} existe pour interdire ce genre de surface.
 */
public interface EmailVerificationSender {

    /**
     * Remet le code à son destinataire.
     *
     * <p>Un échec de transport ne doit jamais interrompre le flux métier appelant (AC de la
     * Story 8.1) : l'inscription est déjà persistée quand cette méthode est appelée, et la
     * faire échouer laisserait un compte créé dont l'utilisateur n'apprendrait rien.
     */
    void sendVerificationCode(String email, String code);
}
