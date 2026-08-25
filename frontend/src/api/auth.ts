import apiClient from './client'
import type { Session } from '@/types/domain'

export interface RegisterPayload {
  email: string
  password: string
  firstName?: string
  lastName?: string
  role?: 'BUYER' | 'SELLER'
  companyName: string
  consentAccepted: boolean
}

/**
 * Inscription (Story 2.4).
 *
 * <p>Ne rend AUCUNE session : le serveur répond 202 sans corps, et le compte naît non
 * vérifié. C'est `verifyEmail` qui ouvre la session. La réponse est volontairement vide —
 * un corps, même neutre, finirait par accueillir un champ qui trahirait l'existence de
 * l'adresse (NFR-P9). Le type de retour `void` porte désormais cette promesse : un
 * appelant qui tenterait d'y lire un jeton ne compile plus.
 */
export function registerUser(payload: RegisterPayload): Promise<void> {
  return apiClient.post('/api/v1/auth/register', payload).then(() => undefined)
}

export interface VerifyEmailPayload {
  email: string
  code: string
}

/** Saisie du code à 6 chiffres — c'est ici que la session est émise (Story 2.4, AC2). */
export function verifyEmail(payload: VerifyEmailPayload): Promise<Session> {
  return apiClient.post<Session>('/api/v1/auth/verify-email', payload).then((res) => res.data)
}

/**
 * Renvoi du code (AC4). 202 sans corps, comme l'inscription.
 *
 * <p>Un dépassement de quota remonte en 429 portant un en-tête `Retry-After` : c'est
 * l'horloge SERVEUR qui dicte le compte à rebours (AD-11). Un minuteur démarré par le
 * client se remettrait à zéro en rechargeant la page.
 */
export function resendVerification(payload: { email: string }): Promise<void> {
  return apiClient.post('/api/v1/auth/resend-verification', payload).then(() => undefined)
}

export interface LoginPayload {
  email: string
  password: string
}

export function loginUser(payload: LoginPayload): Promise<Session> {
  return apiClient.post<Session>('/api/v1/auth/login', payload).then((res) => res.data)
}

/**
 * Déconnexion serveur (Story 1.6, NFR-P5) : révoque la session côté serveur (le
 * jeton présenté n'est plus accepté). Le jeton est passé EXPLICITEMENT pour ne pas
 * dépendre de l'état localStorage : l'appelant peut vider l'état local d'abord.
 *
 * <p>Passe par `fetch` avec `keepalive` et NON par le client axios (revue 1.6) :
 * une XHR en vol est avortée par le navigateur dès que la page navigue, et le seul
 * bouton de déconnexion de l'app enchaîne justement sur `window.location`. La
 * révocation n'atteignait donc souvent jamais le serveur — AC #2 tenait en test,
 * pas en usage. `keepalive` demande au navigateur de mener la requête à terme même
 * après le déchargement du document.
 *
 * <p>Contourne aussi l'intercepteur de réponse d'axios, ce qui est voulu : ce
 * jeton est déjà mort côté client, un 401 tardif ne doit pas purger la session
 * *suivante* si l'utilisateur s'est reconnecté entre-temps.
 *
 * <p>Résolue quelle que soit la réponse ; rejetée sur échec réseau.
 */
export function logoutUser(token: string): Promise<void> {
  const baseURL = apiClient.defaults.baseURL ?? ''
  return fetch(`${baseURL}/api/v1/auth/logout`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    keepalive: true,
  }).then(() => undefined)
}
