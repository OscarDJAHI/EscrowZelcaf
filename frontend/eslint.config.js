import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

// POURQUOI CE FICHIER EXISTE
// --------------------------
// Le code portait six directives `// eslint-disable-next-line` alors qu'AUCUN
// analyseur ne tournait : ni config, ni script `lint`, ni gate CI. Ces
// commentaires décrivaient une barrière imaginaire — un lecteur en déduisait
// qu'un outil avait validé ces choix, personne ne validait rien (ledger,
// bundle QUALITÉ-CI).
//
// Le coût n'est pas théorique : la classe de caractères de contrôle écrite en
// octets bruts qui avait rendu `AuthView.vue` binaire est exactement ce qu'un
// lint attrape. La suite restait verte, `grep` ne voyait plus le fichier.
//
// Deux exigences dictent la configuration ci-dessous :
//   1. les six directives existantes doivent porter sur des règles RÉELLEMENT
//      actives (sinon on remplace une barrière imaginaire par une autre) ;
//   2. une directive devenue inutile doit FAIRE ÉCHOUER le lint
//      (`reportUnusedDisableDirectives`), pour que le décalage constaté ici ne
//      puisse pas se reformer en silence.

export default [
  {
    // `dist/` est un artefact de build, `node_modules` n'appartient à personne.
    ignores: ['dist/**', 'node_modules/**', 'dev-dist/**'],
  },

  js.configs.recommended,

  // `flat/essential` et NON `flat/recommended` : le premier ne porte que des
  // règles de CORRECTION (directive inexistante, `v-for` sans clé, mutation
  // d'une prop…), le second y ajoute du formatage de gabarit
  // (`max-attributes-per-line`, `singleline-html-element-content-newline`) qui
  // produisait 134 avertissements sur du code livré et vert.
  // Arbitrage assumé : cette barrière est posée pour attraper des DÉFAUTS, pas
  // pour réécrire la mise en page de 15 composants la veille du jour où
  // l'Epic 2 (2-1/2-2) les remplace. Le style se traitera avec le formateur que
  // la story 2-1 choisira, sur du code qu'elle aura elle-même écrit.
  ...pluginVue.configs['flat/essential'],

  {
    // Une directive `eslint-disable` qui ne supprime plus rien est un mensonge
    // documentaire de la même famille que celui qui a motivé ce fichier : la
    // règle a pu être renommée, désactivée, ou le code réécrit. Erreur, pas
    // avertissement — un avertissement ne casse pas la CI, donc ne corrige rien.
    linterOptions: {
      reportUnusedDisableDirectives: 'error',
    },
  },

  {
    files: ['**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
      },
    },
    rules: {
      // ACTIVÉE DÉLIBÉRÉMENT, hors `recommended` : c'est la règle que visent
      // cinq des six directives du dépôt (`stores/offlineQueue.js`,
      // `stores/offlineQueue.idb.js`). Sans elle, ces directives ne
      // supprimeraient rien — et `reportUnusedDisableDirectives` ci-dessus les
      // signalerait, à juste titre.
      // Le rejeu de la file hors-ligne est séquentiel PAR CONSTRUCTION (l'ordre
      // des mutations est le contrat), d'où les dérogations explicites.
      'no-await-in-loop': 'error',
    },
  },

  {
    // Fichiers de configuration, d'amorçage et scripts de vérification : contexte Node.
    files: ['*.config.js', 'vitest.setup.js', 'scripts/**/*.mjs'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },

  {
    // Les suites lisent le système de fichiers (garde anti-dérive du contrat
    // ErrorCode) et pilotent des horloges : elles vivent dans les deux mondes.
    files: ['**/__tests__/**/*.js', '**/*.spec.js'],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
  },
]
