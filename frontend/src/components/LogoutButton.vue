<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AppButton from '@/components/AppButton.vue'
import { REVOCATION_WAIT_SECONDS, endSession } from '@/stores/session'

/**
 * La déconnexion, ÉCRITE UNE FOIS et placée dans les deux shells (Story 2.7, décision D-C).
 *
 * <p><b>Pourquoi un composant et non deux copies.</b> Le bouton vivait dans
 * `views/DashboardView.vue`, c'est-à-dire dans UN écran d'UN espace. Conséquence relevée
 * par D-C et vérifiée : les rôles ARBITRATOR et ADMIN, servis par `DesktopShell`,
 * n'avaient aucun moyen de se déconnecter par l'interface. UX-DR20 pose « une seule auth
 * pour trois espaces » — une politique de session qui ne s'applique qu'à un espace n'est
 * pas une politique. Le porter dans les deux shells sans le factoriser aurait dupliqué le
 * `endSession`, le filet de navigation et l'état d'attente : trois choses dont la story
 * exige qu'elles soient prouvées, et qu'il aurait fallu prouver deux fois.
 *
 * <p><b>Toujours en contexte authentifié.</b> `App.vue` ne rend un shell que pour une
 * route portant `meta.space`, et la garde de `router/index.ts` renvoie vers la connexion
 * tant que la session n'est pas exploitable. Ce bouton n'a donc pas à tester `isAuthenticated`
 * pour décider de s'afficher — la question est tranchée en amont, et la reposer ici
 * introduirait un second prédicat de session à maintenir en accord avec le premier.
 */
const router = useRouter()

/**
 * La déconnexion est en cours — et elle est DITE (UX-DR26).
 *
 * <p>L'attente est bornée (`REVOCATION_WAIT_SECONDS`), mais une attente bornée reste une
 * attente : entre le clic et la navigation, les stores sont déjà remis à zéro et l'écran
 * se vide. Sans état visible, l'utilisateur voit l'interface perdre son contenu et un
 * bouton qui ne réagit plus, sans savoir si son geste a été pris en compte. Le libellé
 * porte le motif ET le délai annoncé, comme UX-DR26 l'exige — d'où `labelParams`, sans
 * lequel `common.loggingOut` s'afficherait avec son gabarit `{seconds}` nu.
 */
const signingOut = ref(false)

async function logout() {
  // Plus de rechargement complet (Story 1.9) : il n'a jamais rien purgé d'IndexedDB
  // ni du cache de lecture — c'est `endSession` qui le fait, explicitement — et une
  // navigation de routeur rend le parcours testable.
  //
  // NE JAMAIS RECÂBLER SUR `auth.logout()`. `stores/auth.ts:213-224` porte l'avertissement :
  // cette action ne purge que deux clés et rouvrirait quatre reports fermés par la 1.9 —
  // avec une suite verte, puisque rien n'observe ce qu'elle omet.
  //
  // POURQUOI ON ATTEND `endSession` (AC4). D'abord `endSession` purge TOUT le local —
  // mémoire, IndexedDB, cache de lecture, marqueur — AVANT d'attendre le réseau : ne pas
  // l'attendre ferait naviguer sur un appareil qui n'est pas encore propre. Ensuite
  // l'attente est BORNÉE (`REVOCATION_WAIT_SECONDS`, `stores/session.ts`) : ce qui a motivé
  // le report au ledger — un portail captif retenant la navigation aussi longtemps qu'il
  // retient la requête — n'existe plus. `keepalive` mène la révocation à terme après la
  // navigation ; c'est lui qui rend l'abandon de l'attente acceptable.
  //
  // UNE SEULE GARDE CONTRE LE SECOND CLIC, et elle est DÉLÉGUÉE à `AppButton` (`pending`
  // désactive le bouton et court-circuite l'émission). Aucune condition n'est ajoutée ici :
  // la passe de mutation de T5 avait montré qu'une seconde garde dans le gestionnaire
  // rattrapait le cas de la première, si bien qu'aucune mutation d'une seule ligne ne les
  // distinguait — une garde improuvable en double d'une garde prouvée n'est pas de la
  // défense en profondeur, c'est une preuve creuse en attente d'être citée.
  signingOut.value = true
  try {
    await endSession({ reason: 'logout' })
    try {
      await router.replace({ name: 'auth' })
    } catch (err) {
      // Toutes les routes sont des chunks paresseux : `replace` rejette si celui
      // d'`AuthView` ne se charge pas (déploiement qui invalide le nom haché,
      // précache évincé hors ligne). La session vient d'être détruite — rester ici
      // laisserait l'utilisateur sur un écran vide sans jeton et sans issue, et le
      // rejet s'échapperait d'un gestionnaire de clic `async`. Un rechargement
      // complet est le filet : il repart du serveur et la garde de routeur renverra
      // sur `/auth`.
      console.error('[session] navigation vers la connexion impossible, rechargement', err)
      window.location.assign('/auth')
    }
  } finally {
    // Dans un `finally` et non après la navigation : sur le chemin du filet ci-dessus, le
    // composant n'est PAS démonté — un rechargement complet met du temps à venir, et un
    // bouton resté figé sur « déconnexion en cours » y mentirait indéfiniment.
    signingOut.value = false
  }
}
</script>

<template>
  <AppButton
    data-testid="logout-button"
    variant="secondary"
    label-key="common.logout"
    pending-label-key="common.loggingOut"
    :label-params="{ seconds: REVOCATION_WAIT_SECONDS }"
    :pending="signingOut"
    :aria-busy="signingOut"
    @click="logout"
  />
</template>
