<script setup lang="ts">
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { HOME_BY_SPACE, spaceForRole } from '@/router/spaces'

/**
 * Réponse UNIQUE à tout accès refusé — et à toute route inconnue.
 *
 * <p>C'est délibérément le même écran dans les deux cas. Servir « introuvable » pour une
 * route inexistante et « interdit » pour l'espace d'un autre rôle donnerait un oracle :
 * il suffirait de comparer les deux réponses pour cartographier les espaces auxquels on
 * n'a pas droit. C'est exactement le défaut que la Story 1.10 a passé une story entière à
 * fermer côté API (NFR-P9) ; le front n'a aucune raison de le rouvrir.
 *
 * <p>Le texte ne nomme donc ni l'espace visé, ni le rôle de l'utilisateur, ni la raison.
 * Le seul lien proposé ramène l'utilisateur chez lui — ou vers la connexion s'il n'a
 * aucun espace.
 */
const router = useRouter()
const auth = useAuthStore()

function goHome() {
  const space = spaceForRole(auth.user?.role)
  router.replace(space ? { name: HOME_BY_SPACE[space] } : { name: 'auth' })
}
</script>

<template>
  <main class="mx-auto flex w-full max-w-[720px] flex-1 items-center p-4 lg:p-6">
    <AppCard>
      <h1 class="text-heading">{{ $t('access.deniedTitle') }}</h1>
      <p class="mt-2 text-body text-text-muted">{{ $t('access.deniedBody') }}</p>
      <div class="mt-4">
        <AppButton label-key="access.backHome" @click="goHome" />
      </div>
    </AppCard>
  </main>
</template>
