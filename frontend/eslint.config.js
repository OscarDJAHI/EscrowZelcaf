import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
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
//
// MIGRATION TYPESCRIPT — le même défaut a failli se reformer, exactement.
// Les motifs ci-dessous visaient `**/*.{js,vue}`. Le source passé en `.ts`,
// `npm run lint` restait VERT en n'analysant plus que 31 fichiers sur 80 : les
// 49 modules et suites migrés n'étaient plus lus par personne, et les six
// directives `eslint-disable` qu'ils portent ne supprimaient plus rien tout en
// cessant d'être signalées. Un lint qui passe parce qu'il ne regarde rien est
// la barrière imaginaire que ce fichier existe pour interdire. D'où `ts` dans
// chaque motif, et l'analyseur TypeScript branché sous `<script lang="ts">`.

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

  // Règles de CORRECTION pour TypeScript, sans la couche « stylistique » ni la
  // couche « typée » : même arbitrage que pour `flat/essential` ci-dessus — on
  // attrape des défauts, on ne réécrit pas 12 000 lignes qui viennent d'être
  // migrées et dont la suite est verte.
  //
  // RESTREINT aux `.ts` — et le `.map` n'est pas de la coquetterie. La
  // configuration `recommended` de typescript-eslint ne porte AUCUN `files` :
  // elle s'applique donc à tout, y compris aux `.vue`, dont elle écrase
  // l'analyseur posé par `flat/essential`. Résultat observé : les 28 composants
  // échouaient en « Parsing error: '>' expected » dès la première ligne, le
  // SFC étant lu comme du TypeScript. Le bloc `**/*.vue` plus bas rebranche
  // l'analyseur TypeScript LÀ OÙ IL FAUT : à l'intérieur du script.
  ...tseslint.configs.recommended.map((config) => ({ ...config, files: ['**/*.ts'] })),

  {
    // `<script lang="ts">` dans un composant : `vue-eslint-parser` lit le SFC et
    // DÉLÈGUE le contenu du script à l'analyseur ci-dessous. Sans cette
    // délégation, toute annotation de type est une erreur de syntaxe.
    //
    // Les règles sont recopiées ici plutôt qu'héritées, et c'est la conséquence
    // directe du `files: ['**/*.ts']` posé plus haut : ainsi restreinte, la
    // configuration `recommended` ne couvrait plus les composants. Vérifié par
    // mutation — un `const x: any` glissé dans un `<script setup lang="ts">`
    // ne déclenchait RIEN, pendant que le même défaut dans un `.ts` était bien
    // signalé. Un linter qui ne lit qu'une moitié du code est le défaut que
    // l'en-tête de ce fichier interdit ; il s'était reformé d'un cran plus bas.
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
    plugins: {
      '@typescript-eslint': tseslint.plugin,
    },
    rules: Object.assign({}, ...tseslint.configs.recommended.map((config) => config.rules ?? {})),
  },

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
    files: ['**/*.{js,ts,vue}'],
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
    files: ['*.config.js', '*.config.ts', 'vitest.setup.ts', 'scripts/**/*.mjs'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },

  {
    // Les suites lisent le système de fichiers (garde anti-dérive du contrat
    // ErrorCode) et pilotent des horloges : elles vivent dans les deux mondes.
    files: ['**/__tests__/**/*.ts', '**/*.spec.ts'],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
  },
]
