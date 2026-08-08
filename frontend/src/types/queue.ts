/**
 * La forme d'une entrée de la file hors ligne.
 *
 * <p><b>Pourquoi un module à part</b>, plutôt que dans `stores/offlineQueue.ts` qui la
 * produit : `utils/frozenEntry.ts` la consomme, et son en-tête est catégorique — « pure
 * functions over raw data, never over stores », une signature qui « denies a consumer the
 * ability to reach for the network ». Un `import type` est effacé à la compilation et ne
 * créerait aucune dépendance à l'exécution, mais il inscrirait quand même l'utilitaire
 * pur dans le graphe des stores, à rebours de ce que ce fichier promet. Le type descend
 * donc d'un cran, là où les deux peuvent le lire sans se voir.
 *
 * <p>Ces formes ne sont PAS le contrat serveur (`types/domain.ts`) : ce sont les
 * structures que le client persiste dans IndexedDB.
 */

/** Les trois seules mutations qui atteignent la file. */
export type QueuedActionType = 'CREATE_TRANSACTION' | 'SEND_EVENT' | 'OPEN_DISPUTE'

/**
 * `userId` et `transactionId` sont volontairement `number | string`.
 *
 * <p>Ce n'est pas un relâchement : le même identifiant fait l'aller-retour par le JSON de
 * localStorage d'un côté et par le clone structuré d'IndexedDB de l'autre, et revient
 * légitimement en nombre d'un côté, en chaîne de l'autre. C'est exactement la raison pour
 * laquelle `ownsEntry` compare `String(a) === String(b)`. Déclarer `number` seul rendrait
 * cette comparaison absurde aux yeux du compilateur et inviterait à la « simplifier ».
 */
export interface QueueEntryMeta {
  type?: QueuedActionType
  userId?: number | string | null
  transactionId?: number | string | null
}

/** Le verdict définitif du serveur sur une entrée, tel que le gel l'enregistre. */
export interface QueueFailure {
  code: string | null
  status: number | null
  message: string | null
  /** Horodatage du gel — comparé aux estampilles de chargement (`frozenEntry.ts`). */
  at: string
}

/**
 * Corps d'une requête mise en file.
 *
 * <p>L'index ouvert porte les charges utiles JSON (création de transaction, événement) ;
 * les deux champs nommés sont les seules parts que `buildFormData` sait convoyer pour une
 * entrée binaire — AD-13 fige ce contrat.
 */
export interface QueuedRequestData {
  comment?: string | null
  clientCapturedAt?: string | null
  [key: string]: unknown
}

/** Ce qu'un appelant remet à `enqueue()`. */
export interface QueuedRequest {
  method: string
  url: string
  data?: QueuedRequestData
  files?: Blob[]
  meta?: QueueEntryMeta
}

/** Une entrée telle qu'elle vit en mémoire et dans IndexedDB. */
export interface QueueEntry extends QueuedRequest {
  id: string
  timestamp: string
  /**
   * Compteur monotone d'enfilement, dans la session.
   *
   * <p>Optionnel parce que les entrées migrées depuis localStorage n'en portent pas :
   * elles sont strictement plus anciennes, et la comparaison d'horodatage les départage
   * avant que le départage par `seq` ne soit atteint.
   */
  seq?: number
  frozen?: boolean
  failure?: QueueFailure
}
