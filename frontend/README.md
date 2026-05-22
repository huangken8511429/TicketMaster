# TicketMaster Frontend (MVP)

Independent SPA paired with the Spring Boot backend at `../src/`. Greenfield
MVP delivered across Phases 1-7 in `specs/frontend-mvp/plan/done/`.

## Stack

- **Vite 5 + React 18 + TypeScript** (strict)
- **Tailwind CSS** wired to design tokens from `specs/frontend-mvp/design-tokens.md`
- **React Router v6**, route table mirrors `activity-flow.md §7`
- **TanStack Query v5** for server state; **DevTools** mounted in dev
- **MSW v2** with a SSE-capable handler — lazy-loaded only in dev to keep the
  prod bundle free of the worker runtime
- **Framer Motion** available for richer page transitions
- **Inter Tight + JetBrains Mono** loaded via `@fontsource/*`
- **Vitest + Testing Library** for component / page integration tests
- **Playwright** for end-to-end smoke (Phase 7)

## Getting started

```bash
cd frontend
npm install

# One-time: regenerate the MSW worker script that ships with the installed msw version
npx msw init public --save

npm run dev           # http://localhost:5173 with MSW mocking
npm run build         # type-check + production bundle in dist/
npm run preview       # serve the production bundle locally
npm run test          # vitest run (jsdom integration tests)
npm run test:e2e      # playwright (boots dev server with MSW)
npm run lint          # eslint
```

The dev server reads `VITE_API_BASE_URL` and `VITE_USE_MSW` from
`.env.development`. Set `VITE_USE_MSW=false` and point `VITE_API_BASE_URL`
at a live backend to exercise the real API.

When MSW boots you will see `[MSW] Mocking enabled` in the console.

### Running Playwright

Playwright browsers are downloaded on first use:

```bash
npx playwright install chromium       # ~150MB one-off
npm run test:e2e                      # full sweep (boots Vite + MSW)
npm run test:e2e -- --project=chromium e2e/happy-path.spec.ts
npm run test:e2e -- --headed          # watch the browser drive itself
```

E2E specs live in `e2e/`:

| File                       | What it covers                                                            |
| -------------------------- | ------------------------------------------------------------------------- |
| `happy-path.spec.ts`       | / → /events/:id → /queue/:bookingId → /confirm/:bookingId (full journey)  |
| `edge-cases.spec.ts`       | Confirm direct-access redirect, sold-out section guard, SSE tick refresh  |
| `screenshots.spec.ts`      | Captures `screenshots/01-04*.png` (visual sanity reference)               |

## Routes

| Path                    | Page                  | Phase |
| ----------------------- | --------------------- | ----- |
| `/`                     | EventsListPage        | 3     |
| `/events/:id`           | EventDetailPage       | 4     |
| `/queue/:bookingId`     | QueuePage             | 5     |
| `/confirm/:bookingId`   | ConfirmPage           | 6     |
| `*`                     | NotFoundPage          | 2     |

The `/components-demo` sandbox was removed in Phase 7.

## Directory layout

```
frontend/
├── e2e/                          # Playwright specs (Phase 7)
│   ├── happy-path.spec.ts
│   ├── edge-cases.spec.ts
│   ├── screenshots.spec.ts
│   └── tsconfig.json
├── docs/
│   └── sse-deployment-notes.md   # nginx / k8s SSE config (Phase 7)
├── screenshots/                  # visual smoke output (Phase 7)
├── playwright.config.ts
├── tailwind.config.ts            # design-tokens.md §10 expanded
├── tsconfig.json                 # strict + @/* path alias
├── vite.config.ts                # manualChunks for code-split (Phase 7)
├── vitest.config.ts
├── postcss.config.js
├── public/
│   ├── favicon.svg
│   └── mockServiceWorker.js      # placeholder; replace via `npx msw init`
└── src/
    ├── main.tsx                  # lazy-loads MSW in dev only
    ├── App.tsx
    ├── router.tsx
    ├── api/                      # types + fetch wrapper + React Query hooks
    ├── components/               # cross-page primitives
    ├── features/                 # BDD .feature specs (per-page)
    ├── hooks/
    ├── lib/
    ├── mocks/                    # MSW handlers + seed
    ├── pages/
    ├── styles/
    │   ├── globals.css
    │   └── tokens.css            # CSS variable source of truth
    └── test/                     # Vitest integration suites
```

## Design tokens

`src/styles/tokens.css` is the single source of truth — every value comes
straight from `specs/frontend-mvp/design-tokens.md` (sections 2–9). Tailwind
reads them indirectly via `var(--…)` references in `tailwind.config.ts`.

Use the semantic class names rather than raw hex values:

| Use                         | Tailwind class                                    |
| --------------------------- | ------------------------------------------------- |
| Page background             | `bg-ink`                                          |
| Card surface                | `bg-surface`, `bg-surface-2`, `bg-surface-elevated` |
| Borders                     | `border-line-subtle`, `border-line-strong`        |
| Foreground text             | `text-fg-primary`, `text-fg-secondary`, `text-fg-tertiary` |
| Accent (CTA, countdowns)    | `bg-accent`, `text-accent`, `hover:bg-accent-hover` |
| Section status              | `text-status-plenty`, `text-status-limited`, `text-status-few`, `text-status-sold-out` |
| Typography scale            | `text-display-xl`, `text-display-lg`, `text-heading-md`, … |
| Motion                      | `duration-fast/base/slow/slower`, `ease-standard/snap` |
| Z-index layers              | `z-modal`, `z-toast`, `z-queue-overlay`           |

The `border` colour key was renamed to `line` so it does not shadow Tailwind's
own `border` width utility.

## API contract

Types and hooks track `specs/frontend-mvp/api-contract.md`. The client (`src/api/client.ts`)
is forgiving about both `{error}` JSON and plain-text error bodies, per §1.

Two hooks are full implementations because plan-stage notes flagged them as risky:

- **`useBookingPoll`** — implements 202 → re-poll, 200 → resolve, 5xx →
  exponential backoff, and 60s hard deadline from §3.2 with `AbortController`
  cleanup on unmount.
- **`useSectionStatusStream`** — opens `EventSource`, primes the React Query
  `['sections', id]` cache via initial GET, merges every `section-status`
  event back into the cache, and re-primes on reconnect.

## MSW

`src/mocks/seed.ts` ships 3 events × 5 sections covering every status state
(plenty / limited / few / sold-out / not-started). `handlers.ts` includes:

- `GET /api/events`, `GET /api/events/:id`
- `GET /api/events/:id/sections`
- `GET /api/events/:id/sections/stream` — real SSE via `ReadableStream`,
  emits initial snapshot + heartbeat + ~4s ticks that mutate inventory
- `POST /api/bookings` (202 + bookingId, or 422 sold-out branch)
- `GET /api/bookings/:bookingId` — up to 10s long-poll, 12% reject rate so
  both success and failure flows are demoable

Run `npx msw init public --save` once after install so the service worker
script in `public/mockServiceWorker.js` matches the installed msw version.

## Production bundle (Phase 7)

`npm run build` now manual-chunks the dependency families. Latest sizes:

| Chunk                     | Raw       | Gzipped |
| ------------------------- | --------- | ------- |
| `index.js` (app code)     | 41 KB     | 13 KB   |
| `react.js`                | 142 KB    | 46 KB   |
| `vendor.js` (framer …)    | 48 KB     | 17 KB   |
| `tanstack.js`             | 36 KB     | 11 KB   |
| `router.js`               | 17 KB     | 6 KB    |
| `index.css`               | 64 KB     | 27 KB   |

MSW is **not bundled into production** — the import is gated behind
`import.meta.env.DEV && VITE_USE_MSW === 'true'`.

## Deployment

See `docs/sse-deployment-notes.md` for the reverse-proxy / ingress
configuration the SSE + long-poll endpoints require. Static hosting options
(GitHub Pages, Vercel, Netlify, nginx serving `dist/`) all work — the bundle
is fully client-rendered.

Environment variables:

| Var                  | Used when            | Example                       |
| -------------------- | -------------------- | ----------------------------- |
| `VITE_API_BASE_URL`  | All builds           | `https://api.ticketmaster…`   |
| `VITE_USE_MSW`       | `npm run dev` only   | `true` / `false`              |

## Hand-off history

- Phase 1: backend additions (BDD) — `specs/frontend-mvp/plan/done/phase-1-…`
- Phase 2: Vite skeleton + tokens + shared components
- Phase 3: events list page
- Phase 4: event detail + SSE wiring
- Phase 5: queue overlay + long-poll
- Phase 6: hold-confirm + 5-min countdown
- Phase 7: E2E + polish + deploy (you are here) — see `phase-7-e2e-polish.md`
