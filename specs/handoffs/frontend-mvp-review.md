# TicketMaster Frontend MVP — Review

**Stage**: /review (fresh agent, after /verify PASS)
**Date**: 2026-05-18
**Scope**: backend Phase 1 (SSE bridge + section API + CORS + salesStartAt / basePrice) + frontend Phase 2-7 (~70 files under `frontend/`) + spec docs under `specs/frontend-mvp/`
**Out of scope**: `deployment/`, `ticket-master/`, pre-existing controllers / services not touched by this Flow.

---

## Verdict

**APPROVE-WITH-MINOR-FIXES** — Architecture, error handling, test coverage and contract conformance are all strong. There are no Critical or Major issues that block ship; the findings below are tactical fixes (mostly a11y nits, one dead link, one SSE concurrency-safety concern, one Kafka-listener environmental concern) that can land as a small follow-up PR or be folded into the ship commit.

Counts: **0 Critical · 0 Major · 11 Minor · 1 Outstanding-from-verify**.

---

## Strengths

- **Layering is clean and conventions-respecting.** Backend additions slot into the existing `controller / service / repository / po / dto` split exactly (`SectionController`, `SectionAvailabilityService`, `SectionStatusSseService`, `SectionAvailabilityResponse`). The `@Profile({"api","default"})` decorator matches the project existing role-segregation pattern.
- **Threshold ownership is correct.** Status derivation lives in one place (`SectionAvailabilityService.deriveStatus`) and is shared by REST + SSE so the frontend never re-computes thresholds. The contract decision in `frontend-mvp-spec.md` Q3 is faithfully implemented.
- **`SectionStatusSseService` is properly concurrency-aware.** Uses `ConcurrentHashMap` + `CopyOnWriteArrayList` for the (eventId -> emitters) registry, plus per-(event, section) `ConcurrentHashMap<Integer,Integer>` for sub-partition accumulation. Cleanup hooks on `onCompletion / onTimeout / onError` are all wired. Heartbeat runs on a dedicated single-thread daemon executor that is `shutdownNow()` on `@PreDestroy`. `IOException` and `IllegalStateException` are both caught in `broadcast()` and `sendHeartbeats()` (a common omission).
- **Frontend long-poll hook is robust.** `useBookingPoll` uses an `AbortController` per request, a `cancelledRef` for unmount, an explicit `HARD_DEADLINE_MS=60s`, an exponential `BACKOFF_SCHEDULE` for 5xx, and re-issues immediately on 202. The contract matches `api-contract.md` section 3.2 to the letter.
- **SSE hook closes its `EventSource` on unmount** (verified by `sseSectionBadge.test.tsx` third case) and write-throughs into the same React Query cache key the page reads, eliminating dual-source-of-truth drift. A `closed` flag plus inline check in every listener prevents stale state writes after unmount.
- **ConfirmPage state machine handles the confirmed-vs-expired race correctly** at `pages/ConfirmPage.tsx:100` (setPhase guard returns current when already `confirmed`) and is locked down by a dedicated Vitest case.
- **Test coverage is dense for what is in scope.** 30 Vitest specs across pages + hook + SSE integration, 8 Playwright specs (chromium PASS per verify Supplemental), 17 backend specs for the new code paths. The SSE handler removes-on-IOException path is even covered.
- **Bundle hygiene is excellent.** ~91 KB gzipped total, MSW lazy-imported behind `import.meta.env.DEV && VITE_USE_MSW`, prod bundle confirmed free of MSW (`main.tsx:10`). The manual chunk strategy in `vite.config.ts:24` is sensible.
- **Backwards compat is preserved.** `salesStartAt` is nullable on the entity and null-tolerant on every read path. `basePrice` is nullable with a fallback label in the modal and a `null`-guarded `computeTotalPrice` in ConfirmPage.

---

## Findings

### Critical (must fix / block ship)

_None._

---

### Major (strongly recommended)

_None that block ship. Promoting any of the SSE concerns below to Major would require an environmental constraint (multi-replica K8s) that the MVP demo is not running in._

---

### Minor / Suggestions

#### M1. `SectionStatusSseService` Kafka groupId uses `random.uuid` placeholder
- File: `src/main/java/com/keer/ticketmaster/service/SectionStatusSseService.java:129`
- Issue: `groupId = "sse-bridge-${random.uuid}"` relies on Spring `RandomValuePropertySource` substituting at bean-instantiation time. This works in Spring Boot 4, but if the property is resolved more than once (test rebinding, refresh-scope, devtools restart) the same replica could end up with two consumer groups, and the consumer-group cardinality on the Kafka broker grows linearly with replica restarts (no auto-cleanup of stale groups before broker timeout). For a broadcast-to-every-replica pattern, the recommended idiom is `groupId = "sse-bridge-" + UUID.randomUUID()` computed in the bean or, even better, no group via the lower-level consumer API with `assign()` instead of `subscribe()`.
- Why Minor: works correctly in the demo and the MVP load profile is tiny. Promote if the team plans to scale this past a handful of API replicas or run with frequent rolling restarts.
- Suggested fix: switch to `groupId = "#{T(java.util.UUID).randomUUID().toString()}"` so the UUID is computed in Java when the bean initialises, not via property substitution; or move to a manual `KafkaConsumer.assign(...)` so no group is registered with the coordinator at all.

#### M2. `SectionStatusSseService.aggregateForBroadcast` calls JPA on every Kafka event
- File: `src/main/java/com/keer/ticketmaster/service/SectionStatusSseService.java:169`
- Issue: every `section-status` event triggers `availabilityService.findEventForBridge(eventId)`, which is `eventRepository.findById(eventId)` — a DB round-trip on the hot path. With 32 partitions x N seats/sec, an idle replica is fine, but under bursty section-status traffic this turns the SSE bridge into a DB-pressure source.
- Why Minor: backend has a Hikari pool sized at 50 and the MVP load profile is tiny; verify also confirmed the bridge works end-to-end against the real backend.
- Suggested fix: cache the `Event` (only `salesStartAt`, `totalSeats` per section, `basePrice` per section) keyed by eventId with a short TTL (e.g. 30s). The values are immutable for the lifetime of an event for MVP purposes.

#### M3. SSE bridge in-memory `subPartitionState` never expires for closed events
- File: `src/main/java/com/keer/ticketmaster/service/SectionStatusSseService.java:62`
- Issue: `subPartitionState` accumulates (eventId x section x subPartition -> Integer). Once an event finishes there is no eviction; on a long-running API replica this grows monotonically.
- Why Minor: MVP runs short demos; `final.md` section 5 already classifies this as acceptable.
- Suggested fix: when the emitter list for an `eventId` becomes empty AND no Kafka event has arrived for >= N minutes, drop the entry. Or expire entries after, say, 2 hours past `event.eventEndTime`.

#### M4. `useSectionStatusStream` reconnect path re-primes cache via `apiFetch` but does not back off
- File: `frontend/src/hooks/useSectionStatusStream.ts:99`
- Issue: on `error`, the hook increments `reconnectCount` and immediately re-fires `GET /api/events/{id}/sections`. If the backend is down, `EventSource` will keep trying to reconnect (browser default ~3s) and each error event triggers another prime fetch. No exponential backoff, no jitter.
- Why Minor: the `apiFetch` itself is short and idempotent; React Query will not store an error result back into the cache. But during a long backend outage the requests-per-second from a single tab can be wasteful.
- Suggested fix: track a `lastPrimeAt` timestamp and skip re-priming if it ran within the past ~10 seconds; or use React Query `useQuery` with stale-time to short-circuit.

#### M5. `SectionBadge` renders `aria-label` on a `<div>` when not interactive
- File: `frontend/src/components/SectionBadge.tsx:37` and `:43`
- Issue: `Tag = interactive ? "button" : "div"` and `disabled={!interactive && Tag === "button"}` — the `Tag === "button"` clause is contradictory: when `!interactive` is true, `Tag` is `"div"`, so the attribute resolves to `false` and is dropped. That part is harmless, but the component still leaves `aria-label` on the resulting `<div>`. Combined with the Lighthouse `aria-prohibited-attr` finding on `/events/1` (verify Supplemental), the most-likely fix is: when `Tag === "div"`, either render the element as a button always with `aria-disabled` + `tabIndex=-1`, or drop the `aria-label` from the `<div>` form — `aria-label` on a generic div with no role is exactly what Lighthouse flags as `aria-prohibited-attr`.
- Why Minor: visually correct; does not break interaction.
- Suggested fix: prefer the button-always form with `aria-disabled={!interactive}`, `tabIndex={interactive ? 0 : -1}`, `disabled={!interactive}`, and `onClick={interactive ? onClick : undefined}` — keeps the semantics consistent and a11y-friendly.

#### M6. `Toast` outer `<div>` uses `aria-label` and `aria-live` without a role — the second `aria-prohibited-attr` candidate
- File: `frontend/src/components/Toast.tsx:15-18`
- Issue: the outer container uses both `aria-live="polite"` and `aria-label="通知"` on a generic `<div>`. axe-core considers `aria-label` on a generic div with no role to be a prohibited attribute. Either add `role="region"` (or `role="log"`, the canonical pairing with `aria-live`) or drop `aria-label`. `aria-live` is fine on a bare div, so no harm in just removing `aria-label`.
- Why Minor: actual screen-reader behaviour is fine because each child uses `role="status"` and announces independently.
- Suggested fix: `<div role="log" aria-live="polite" aria-label="通知">` or drop the `aria-label`.

#### M7. Layout has a nav link to `/components-demo` but no such route exists
- Files: `frontend/src/pages/Layout.tsx:7` (defines `NAV_ITEMS`), `frontend/src/router.tsx` (no matching path)
- Issue: clicking the "Components" nav link hits `<NotFoundPage />` via the `path: "*"` fallback.
- Why Minor: easy to miss in demo; not user-facing-critical.
- Suggested fix: delete the nav item, add the route, or gate it behind `import.meta.env.DEV`.

#### M8. `console.log("[MSW] Mocking enabled")` in `mocks/browser.ts`
- File: `frontend/src/mocks/browser.ts:19`
- Issue: dev-only console noise. Acceptable for dev, but the project review criteria flag `console.log` as a code-quality concern.
- Why Minor: behind an `import.meta.env.DEV` + `VITE_USE_MSW` double-guard so prod tree-shakes it.
- Suggested fix: either keep as-is (it is intentional for dev confirmation) or guard with `if (import.meta.env.DEV)` more explicitly inside the function.

#### M9. `QueueOverlay` hard-codes `#3D3D42` for stopped strokes / circle fills
- File: `frontend/src/components/QueueOverlay.tsx:76` and `:90`
- Issue: the token system already exports the same colour as `--border-strong`. Two hex literals slip through the design-token discipline.
- Why Minor: cosmetic; renders correctly; the design-tokens owner can sweep later.
- Suggested fix: `stroke={stopped ? "var(--border-strong)" : "var(--accent)"}` — CSS variables work inside SVG `stroke` / `fill` in all modern browsers.

#### M10. `QueuePage` popstate guard cannot block a determined user
- File: `frontend/src/pages/QueuePage.tsx:82-107`
- Issue: known limitation documented in `frontend-mvp-final.md` section 5.5. The current implementation pushes a sentinel + re-pushes on each `popstate`, but doubling back-tap on some browsers still escapes the queue page. `useBlocker` from react-router-dom 6.27 would tighten this.
- Why Minor: explicit design decision (Phase 7 deferred this to avoid risking the 7 QueuePage Vitest specs). Demo-acceptable.
- Suggested fix: post-MVP migrate to a Data Router with `useBlocker`, and add a confirm dialog instead of a toast. Or accept the current behaviour and remove the comment about `useBlocker` being a planned migration.

#### M11. CORS `allowedHeaders("*")` plus `exposedHeaders("Location")` plus `allowCredentials(false)` is permissive
- File: `src/main/java/com/keer/ticketmaster/config/CorsConfig.java:30`
- Issue: `allowedHeaders("*")` accepts any header. With `allowCredentials(false)` and the explicit method whitelist this is not a security hole, but it would be tighter to enumerate the actually-needed headers: `Content-Type`, `Accept`. The exposed `Location` header is unused (no endpoint returns one).
- Why Minor: still safer than `allowedOriginPatterns("*")`; origins are an explicit allow-list.
- Suggested fix: `.allowedHeaders("Content-Type", "Accept")` and drop `.exposedHeaders("Location")` until a controller actually sets it.

---

### Outstanding from `/verify`

#### V1. `/events/1` Lighthouse a11y = 88 — `aria-prohibited-attr` + `color-contrast`
- Source: `frontend-mvp-verify.md` Supplemental Lighthouse table
- Two failing audits (weight 7 each):
  - **`aria-prohibited-attr`** — the most likely culprits are M5 (`SectionBadge` `aria-label` on a sold-out `<div>`) and M6 (`Toast` viewport `<div>` with `aria-label`). Either one alone is enough to trigger the rule. Fix one or both.
  - **`color-contrast`** — Acid Lime `#D6FF3D` on `bg-surface` (`#141416`) computes a contrast ratio of ~12:1, well above WCAG AA. The likely failure is the `text-fg-tertiary` (`#6E6E73`) caption pairs at `text-caption` (12px) size on `bg-surface`: ratio ~3.5, which fails AA for body text (4.5 required) and only just passes AA-Large at 14pt+. Fix by either bumping `--fg-tertiary` to `#85858B` (~4.55:1 on `bg-surface`) or only using `fg-tertiary` at sizes >= 18.66px / 24px+ bold.
- Suggested follow-up: ship one PR titled "a11y: fix /events/1 Lighthouse audits" that lands M5 + M6 + a tertiary-foreground contrast bump. Should reach a11y >= 95.

---

## Performance / Bundle observations

- 91 KB gzip total is comfortably under any reasonable budget. Largest non-React chunk is `vendor.js` (17 KB gz) which already excludes framer / tanstack / router into their own buckets.
- `react.js` at 142 KB raw / 46 KB gz is the floor (React 18.3.1 + react-dom). No regression vector unless someone adds a heavy state lib.
- `EventDetailPage` re-renders on every SSE merge because `setSections` lives in `useSectionStatusStream` and React Query `setQueryData` notifies subscribers. The page reads `sectionsQuery.data` (React Query) — that is what re-renders the grid. `SectionBadge` is memo-free but its props (`section`, `status`) are primitives; React fiber diff is cheap. Not flagged as a problem; if SSE traffic ever grows 100x, consider `React.memo(SectionBadge, (a,b) => a.status === b.status && a.section === b.section)`.
- The `setInterval(..., TICK_MS)` in `mocks/handlers.ts:67` is module-scope and never cleared — fine for dev but means HMR reloads pile up timers in the worker. Not shipped to prod.

---

## Security checklist

- **CORS**: explicit origin allow-list, env-overridable, `allowCredentials(false)`. See M11 for a Minor tightening on `allowedHeaders("*")`. **OK for MVP.**
- **Auth on SSE endpoint**: none. Documented as MVP scope. The SSE endpoint emits only aggregated availability counts that are also fetchable via `GET /sections`, so the leak surface is zero. **Acceptable.**
- **SSE endpoint DoS**: any client can hold an emitter for 30 minutes. No per-IP rate limit. For demo, fine. For prod, add a connection-count guard or push behind a CDN / ingress with limits.
- **XSS**: every user-visible string in pages and components is interpolated via React JSX (auto-escaped). No `dangerouslySetInnerHTML` sites. Booking IDs are sliced to 8 chars before display. Section names come from backend. **OK.**
- **`useAnonymousUserId` / localStorage**: stores a UUID under `tm.anonymousUserId`. No PII. Stable across sessions; lets a single user replay bookings, which is acknowledged as MVP-acceptable in `frontend-mvp-spec.md` section 5. `crypto.randomUUID()` is used when available; the fallback (`Math.random() + Date.now()`) is collision-tolerant for an anon ID but is NOT cryptographically random — fine because it is not used as a security token. **OK.**
- **Secrets in source**: none found. `application.properties` has a DB password (`secret`) but this is a known dev convention paired with `compose.yaml`; not new in this Flow.
- **Path traversal / SQLi**: backend uses JPA repositories with parameter-bound queries (`existsById`, `findById`). The SSE Lua script uses parameterised `KEYS` / `ARGV`. **OK.**

---

## Test coverage gaps

Coverage of the new code is good; the gaps below are nice-to-have:

1. **No test for `SectionStatusSseService` heartbeat path.** `sendHeartbeats` removes emitters on `IOException` — covered indirectly via the `handleEvent_removesEmitterOnSendFailure` test, but a dedicated heartbeat-failure test would lock down the heartbeat-thread side of the cleanup.
2. **No test for `SectionStatusSseService.onSectionStatus` catch-all.** The Kafka listener wraps `handleEvent` in a try/catch + `log.warn`. If a future change in `handleEvent` starts throwing on a specific subPartition, regression visibility is just a log line.
3. **`useBookingPoll` has no Vitest spec.** The Phase 7 build handoff lists "Unit (hooks) — Vitest — count 1", which is `useCountdown`. The much more complex `useBookingPoll` (202 re-issue, 5xx backoff, 60s deadline, AbortController on unmount) is exercised only indirectly via the QueuePage tests (which mock the hook). A small direct test against `vi.stubGlobal("fetch", ...)` returning 202 -> 200 BOOKED, then a 5xx -> 5xx -> 5xx -> fail path, would catch regressions cheaply.
4. **No SSE-reconnect test.** `useSectionStatusStream` increments `reconnectCount` and re-primes the cache on `error`. The `sseSectionBadge.test.tsx` covers `open` and `section-status` and `unmount` but not `error -> reconnect`.
5. **No 422 sold-out path test.** `EventDetailPage` has an `if (e instanceof ApiError && e.status === 422)` branch that locally marks the section sold-out — there is no Vitest or Playwright spec exercising it.
6. **No long-poll 60s-deadline E2E.** The 60s `HARD_DEADLINE_MS` exists in code and is referenced by tests as a constant, but no test pushes the clock past 60s. (Doable with `vi.useFakeTimers()`.)
7. **`SectionBadge` not-interactive click handler.** When `Tag === "div"`, the prop `onClick={interactive ? onClick : undefined}` means click events do nothing — but there is no assertion that clicking a SOLD_OUT badge does not open the modal. The Playwright edge-cases spec covers this externally (`e2e/edge-cases.spec.ts:42`); a unit-level assert would be cheaper to keep green.
8. **`CorsConfig` integration test.** Verified via reflection only; the comment in `CorsConfigTest.java:18` even calls out that "full HTTP preflight is exercised via a `@SpringBootTest` CORS integration test" — but no such file exists. Either delete the comment or add the `@SpringBootTest` that drives an `OPTIONS` request through Spring CORS filter chain.

---

## Suggested commit / PR strategy

The Flow produced ~90 files of frontend + 8 of backend + 6 of docs. One mega-PR makes for an unreviewable diff; one PR per phase risks the reviewer losing the integration context. Recommended middle path: **3 PRs, in this order**:

1. **PR-1: `feat(backend): SSE bridge + section availability API + CORS + salesStartAt/basePrice`** (Phase 1).
   - All `src/main/java/...` additions and edits.
   - `application.properties` SSE + CORS keys.
   - `src/test/java/...` for `SectionController` / `SectionAvailabilityService` / `SectionStatusSseService` / `CorsConfig`.
   - `src/test/resources/features/event/活動開賣時間.feature` + EventThenSteps / EventWhenSteps deltas.
   - **Reviewer checklist**: contract matches `specs/frontend-mvp/api-contract.md` section 4; M1 / M2 / M3 discussed.
2. **PR-2: `feat(frontend): MVP SPA — Phase 2 skeleton + design tokens + shared components + hooks`** (Phase 2 scaffolding + reusable layer).
   - `frontend/{package.json, vite.config.ts, tailwind.config.ts, tsconfig.json, .env.*, .eslintrc.cjs, .prettierrc, .gitignore, index.html, postcss.config.js, vitest.config.ts}`
   - `frontend/src/{styles, lib, api, hooks, components, mocks, test/setup.ts}`
   - 4 BDD `.feature` source files under `frontend/src/features/`
   - **Reviewer checklist**: design tokens vs hardcoded values (M9); SSE / long-poll hook contracts (M4); test setup.
3. **PR-3: `feat(frontend): pages + routes + E2E + Playwright config`** (Phases 3-7).
   - `frontend/src/{pages, App.tsx, router.tsx, main.tsx}`
   - `frontend/{playwright.config.ts, e2e/}`
   - `frontend/{README.md, docs/sse-deployment-notes.md}`
   - `frontend/src/test/**` page-level specs
   - **Reviewer checklist**: M5 / M6 / M7 / V1 a11y fixes; M10 popstate caveat.

Each PR is independently testable: PR-1 runs `./gradlew test`; PR-2 runs `npm test`; PR-3 runs `npm run test:e2e` (chromium project) once PR-2 is merged.

Alternatively, a single PR with a clear narrative works if the team prefers one merge — but tag the commits internally so a future bisect can find where SSE / long-poll / a11y regressions land.

---

## Hints for `/ship`

- **No blockers.** Approve-with-minor-fixes means `/ship` can proceed with this commit set and open one follow-up issue covering M1-M11 + V1.
- **One real bug worth fixing pre-ship** if time allows: M7 (`/components-demo` dead link). One-line delete in `Layout.tsx`. Otherwise demo viewers may click it and hit the 404 page.
- **Most valuable single follow-up**: an a11y PR addressing V1 (M5 + M6 + tertiary-fg contrast). Pushes `/events/1` from 88 -> 95+ and unblocks the original >=90 all-routes target.
- **Demo gotcha to brief the team on**: MSW seed event #3 (`Static Cathedral`) has 4 of 5 sections SOLD_OUT; event #2 is NOT_STARTED with a 2h30m countdown. The happy demo flow is event #1 (`Aurora Wavelength`).
- **Real-backend demo gotcha** (per verify Supplemental.4): `:8080` was left running by verify (PID 23970). Kill before `/ship` if not needed.
- **Known limitations to keep on the README**:
  - 5-minute hold countdown is pure-UX; backend does not enforce it.
  - SSE on Cloudflare Free is capped at 100s — needs Pro tier or polling fallback (already in `frontend/docs/sse-deployment-notes.md` section 6).
  - popstate guard is best-effort.
  - Event "posters" are gradient placeholders.

`/ship` can move directly. If the follow-up a11y PR is preferred before tagging a release, a single ~30-line patch should land it.

---

## References

- Build handoff: `specs/handoffs/frontend-mvp-final.md`
- Verify handoff: `specs/handoffs/frontend-mvp-verify.md` (PASS after supplemental run)
- Spec: `specs/handoffs/frontend-mvp-spec.md`, `specs/frontend-mvp/{api-contract,activity-flow,component-spec,design-tokens}.md`
- BDD workflow: `CLAUDE.md`
