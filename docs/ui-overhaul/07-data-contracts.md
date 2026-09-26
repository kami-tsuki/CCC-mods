# 07: Data Contracts (server and network)

The UI can only guide players if the server tells it *what matters*. Everything stays in `Service` (commands and GUI share it); the GUI never computes rules the server doesn't confirm.

---

## 1. Snapshot split

Today one JSON `Snap` carries everything on every change. New: **topics**, each versioned, sent only when changed and only when the client has that page open (plus a small always-on core).

| Topic | Contents | Sent |
|---|---|---|
| `core` | identity, rank, treasury, net/day, runway, chunk counts, alert count + max severity, caps, delegable caps | always while the app is open |
| `alerts` | alert list (§3) | open app |
| `dashboard` | next steps, activity feed (last 20) | Dashboard open |
| `stats` | history series (§6) for a requested range | Statistics/Budget open |
| `budget` | income/spending breakdown, forecast inputs | Budget open |
| `ledger` | page of entries (§7), filter params | Ledger open |
| `chunks` | claim lines (today `claimList`) | Chunks/Map open |
| `plots` | plot lines + lapse states | Plots open |
| `citizens` | members, requests, invitations | Citizens open |
| `jobs` | jobs + workers + payroll | Jobs open |
| `laws` | type rules, flags, presets, plot law | Protection/Plot law open |
| `diplomacy` | relations, family tree, province offers/requests, independence state | Relations/Provinces open |
| `world` | countries, players | World open |
| `detail` | chunk detail incl. `access` for the viewer (§4.2) | on request |

Wire format: keep the kotlinx JSON `Snapshot` packet but add `topic` and `rev`; the client `ClaimsStore` merges. Payload size limit per topic (e.g. 256 KB), with pagination for ledger/players.

## 2. Requests and results

### 2.1 `Act` gets a request id
```kotlin
Act(name: String, args: List<String>, asCountry: String, rid: Int)
```
### 2.2 `Result` packet (new)
```kotlin
Result(rid: Int, ok: Boolean, severity: String, message: String,
       reason: String?,          // machine code: NOT_CONNECTED, RESERVED, NO_FUNDS, RANK, ...
       field: String?,           // form field to mark invalid
       target: Target?,          // chunk / player / country / page to link
       partial: List<ItemResult>?)  // for area actions: per-chunk outcome
```
`Fail` in `Service` gets a `reason` code and optional `field`/`target`. Message texts move to lang keys with arguments so the client can render them with glyphs and links.

### 2.3 Batch
`ActBatch(rid, actions: List<Act>)` for staged edits (Protection, Jobs, Plot law): applied in order in one tick; the result lists per-action outcome; nothing is applied after the first hard failure unless marked independent.

## 3. Alerts (`kami.claims.service.Alerts`)

Computed server-side per viewer (respecting caps and visibility), refreshed when `Realm.rev` changes and once a minute.

```kotlin
Alert(id: String, severity: Severity, icon: String, title: LangText, body: LangText,
      action: AlertAction?, link: Route, since: Long, dismissible: Boolean)
AlertAction(label: LangText, act: String, args: List<String>, confirmTier: Int)
```

| Id | Severity | Condition | Recommended action |
|---|---|---|---|
| `debt.chunks` | danger | chunks with debt > 0 | Deposit the shortfall / Show chunks |
| `runway.low` | warning (< 14 d) / danger (< 3 d) | runway from forecast | Deposit / Open budget |
| `billing.short` | warning | treasury < next bill | Deposit exact shortfall |
| `reserve.expiring` | info | own reserved chunks expiring < 1 d | Reclaim on map |
| `plot.lapse` (own) | warning/danger | your plot tax overdue / locked soon | Pay / Open plot |
| `plots.lapsing` (staff) | info | plots in lapse | Open plot law |
| `requests.join` | info | pending join requests | Review |
| `province.offer` | warning | incoming province offer | Review (opens the wizard) |
| `province.request` | info | a country asks to be your province | Review |
| `province.independence` | warning | a province requests independence | Grant / Decline |
| `province.tributeDebt` | warning | your tribute debt > 0 (province side) or a province's (overlord side) | Deposit / Forgive / Release |
| `jobs.unpaid` | warning | payroll hit the job-share cap or the treasury was empty | Open jobs |
| `jobs.unassigned` | info | jobs with no workers | Assign |
| `succession.soon` | warning | president inactive > 24 d (of 31) | Info: who becomes president |
| `free.expiring` | danger | no member online for > 24 d (free chunks vanish at 31) | Info |
| `capital.missing` | danger | no capital | Set capital on map |
| `nextsteps` | info | onboarding items open | Dashboard checklist |

Dismissible alerts are hidden per player until the condition changes (hash of the condition values).

## 4. Previews (dry runs)

### 4.1 `claim_preview`
```
Act("claim_preview", [type, x1, z1, x2, z2] | [type, "cells", "x:z,x:z,…"])
→ Preview(items: List<ItemResult(x, z, outcome: CLAIM|FREE|RETYPE|BLOCKED, reason?, price)>,
          totalNow: Long, upkeepDelta: Long, runwayBefore: String, runwayAfter: String, freeUsed: Int)
```
Implemented by running the same checks as `claimrect` in `Service` without mutating (refactor the checks into a pure `planClaim(...)` used by both). Rate-limited separately (it's read-only), capped at 1024 cells.

### 4.2 `detail.access`
`Detail` gets `access: Map<Action, Boolean>` for the viewing player, computed with `Guard.allowed`, so the map and chunk drawer can say "You can: break ✔ …".

### 4.3 Other previews
`unclaim_preview`, `retype_preview`, `tax_preview` (income delta from plot count), `province_terms_preview` (tribute estimate from the target's income).

## 5. Provinces

- `Snap.core.delegableCaps` = `Service.delegableCaps` names → the warning wizard lists them.
- Request flow change (D2): `province_approve` creates a `ProvinceOffer` on the requester instead of finalizing; the requester completes it with `province_accept … confirm` (§5.1).
- New `province_independence_decline <province>`: clears `independenceRequested`, records `independenceDeclinedAt` (cooldown before a new request, config `provinceIndependenceCooldownDays`, default 3), mails the province.
- New `province_independence_withdraw`.
- Offers carry `until` (exists) → UI countdown.

### 5.1 Confirm tokens (commands and GUI alike)
Authority-losing actions (`province_accept`, `province_give`, `disband`, `president`) require a final `confirm` argument. Without it the command answers with the warning text (same content as the wizard, from lang keys) and a clickable `[Confirm]` that runs the command with `confirm`. The GUI sends `confirm` after the wizard. So command users can't skip the warning either.

## 6. History (`kami.claims.economy.History`)

Per country, one `DayStat` per UTC day, ring buffer of 90 (D8), saved in `kami_claims.json`:
```kotlin
DayStat(day: Long, treasury: Long, income: Long, upkeep: Long, jobs: Long, tributeIn: Long, tributeOut: Long,
        deposits: Long, withdrawals: Long, chunks: Int, chunksByType: Map<String, Int>, members: Int, plots: Int, debtChunks: Int)
```
Written by `Upkeep` at billing time (it already computes most of these). The forecast (Budget, Dashboard) is computed from the current daily rates (deterministic, same formula as `runway`), not extrapolated noise.

## 7. Ledger (`kami.claims.economy.Ledger`)

Per country, last 300 entries (D8):
```kotlin
Entry(at: Long, kind: Kind, amount: Long, balance: Long, actor: String?, counterparty: String?, ref: String?)
enum Kind { UPKEEP, PLOT_TAX, TRIBUTE_IN, TRIBUTE_OUT, JOB_PAY, DEPOSIT, WITHDRAW, CLAIM, UNCLAIM, DEBT, FORGIVE, REFUND }
```
Written at every treasury mutation (`Bank`, `Upkeep`, `Service` deposit/withdraw/claim). Paged to the client (50/page).

## 8. Next steps (onboarding)
Server-computed checklist per country: capital set, ≥ 1 paid claim, plot tax set, ≥ 1 job, ≥ 2 members, colour changed, treasury ≥ 7 days of upkeep. Stored as a dismiss flag per country.

## 9. Denials
`Denied` payload (06-world-feedback §3.1) from `Guard.check` to modded clients; vanilla clients get the improved action-bar text.

## 10. Compatibility
- The mod stays optional on clients: every new payload has a text fallback, chat links degrade to plain text, glyphs use fallbacks (01-design-system §7).
- Old clients with an older protocol version: bump the payload protocol version; mismatch → server sends a chat notice "Update KamiClaims to use the country screen" and falls back to commands.
- Tests: `planClaim` purity (preview equals real claim outcome), alerts per condition, ledger/history written on billing, province offer→accept flow, independence decline/cooldown, confirm tokens.
