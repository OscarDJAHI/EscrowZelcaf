# Escrow PWA — Frontend

Mobile-first, offline-capable Progressive Web App for the ZLECAf B2B escrow platform.
Built with **Vue 3** (Composition API), **Vite**, **Pinia**, **TailwindCSS** and **vite-plugin-pwa**.

## Features

- **Auth**: register / login (JWT stored in `localStorage`, attached to every request).
- **Dashboard**: the current user's transactions as colour-coded cards; buyers can open a "New transaction" modal.
- **Transaction detail**: a horizontal **stepper** of the escrow state machine, the audit-log timeline, and **role-aware action buttons** (only the events the current user may trigger from the current state are shown — computed client-side in `src/utils/stateMachine.js`, mirroring the backend).
- **Offline mode**: when `navigator.onLine` is false, create/event actions are pushed onto a persisted **Pinia offline queue** (`src/stores/offlineQueue.js`) and replayed automatically when connectivity returns. An online/offline banner reflects status.
- **PWA**: installable, with a Workbox service worker (`NetworkFirst`) caching the app shell and API for fast loads on unstable 3G.

## Prerequisites

- Node.js 18+ (developed on Node 24)
- The backend running (default `http://localhost:8080`)

## Configuration

Copy `.env.example` to `.env` and adjust if the backend is not on `localhost:8080`:

```
VITE_API_BASE=http://localhost:8080
```

## Run

```bash
npm install
npm run dev      # dev server (Vite), http://localhost:5173
npm run build    # production build into dist/
npm run preview  # serve the production build locally
```

## Structure

```
src/
  api/            axios client + auth/escrow endpoint wrappers
  stores/         Pinia: auth, escrow, offlineQueue
  utils/          client-side state-machine mirror (allowed events, colours)
  components/     StateBadge, StepperEscrow, TransactionCard, AuditTimeline,
                  NewTransactionModal, OnlineBanner
  views/          AuthView, DashboardView, TransactionDetailView
  router/         vue-router routes with auth guard
```

## Demo flow

1. Register two accounts: one **BUYER**, one **SELLER** (and optionally an **ADMIN** for disputes).
2. As the buyer, create a transaction using the seller's email.
3. Walk the state machine: **Pay funds → Mark shipped → Confirm delivery** (or open a dispute and resolve it as admin).
