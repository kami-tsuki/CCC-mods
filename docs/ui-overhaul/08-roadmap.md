# 08: Roadmap

Seven phases. Each ends in a playable build on the test server (`scripts/restart.ps1`) and has acceptance criteria that must pass before the next phase starts. The old `ClaimsScreen` stays reachable (`/claims legacy`) until phase 4 is accepted, then gets deleted.

---

## Phase 0: Foundations in KamiLibs (framework, no art yet)

| Task | Files |
|---|---|
| Tokens + theme JSON loader, severity enum | `kami/libs/ui/theme/Tokens.kt`, `ThemeLoader.kt`, `assets/kami_libs/kami_theme/atlas.json` |
| Render helpers: nine-slice via `blitSprite`, text styles, icon draw, shadow, clip stack, `Anim` clock/easing | `ui/render/Draw.kt`, `Text.kt`, `Anim.kt` |
| Layout engine (Row/Column/Grid/Stack/Split/Scroll) with unit tests | `ui/layout/*`, `src/test/.../LayoutTest.kt` |
| Input: focus manager, shortcut registry, drag controller | `ui/input/*` |
| App shell: `KamiApp`, TopBar, Sidebar, Router, Page, Breadcrumbs, history | `ui/app/*` |
| Overlay hosts: modal, toast, tooltip, popover | `ui/overlay/*` |
| Formatters (money, %, duration, relative, compact), rich text + glyph table | `ui/data/Format.kt`, `ui/text/Rich.kt`, `Glyphs.kt` |
| Placeholder sprite generator (Gradle task writing flat PNGs + `.mcmeta` from tokens) | `KamiLibs/build.gradle.kts`, `tools/SpriteGen.kt` |
| Icon font provider + placeholder glyph sheet | `assets/kami_libs/font/ui.json`, `textures/font/icons8.png` |
| Component gallery screen (`/kamiui gallery`) | `ui/gallery/Gallery.kt` |

**Accept**: gallery opens and shows the shell, empty pages, a modal, a toast and tooltips at GUI scales 1–4 and window widths 400/600/900 without overlap; layout tests pass.

## Phase 1: Component library

All components of 02-components with every state, in the gallery. Priority order: Button family → TextField/NumberField/CurrencyField → Checkbox/Radio/Toggle/Segmented → Select/Combobox/Search → PropertyRow/StatTile/Meter/Progress/Badge/Chip/StatusPill/Avatar/Emblem/Money/Duration/Timeline/KeyHint/Callout → DataTable/List → Card/Drawer/SubTabs/FieldGroup → Charts (port `ui/graph/Chart.kt`) → ContextMenu/Coachmark/Skeleton.

**Accept**: each component reachable by keyboard, narrates, shows a disabled reason, survives a data update without losing focus/scroll; DataTable handles 5,000 rows at 60 fps.

## Phase 2: Safety and guidance (server + first pages)

| Task | Files |
|---|---|
| `rid` in `Act`, `Result` packet, `Fail.reason/field/target`, lang keys for messages | `net/Net.kt`, `net/Sync.kt`, `service/Service.kt`, `lang/en_us.json` |
| Topic snapshots + `ClaimsStore` on the client | `net/Sync.kt`, `client/store/*` |
| Alerts service | `service/Alerts.kt` |
| Province changes: request→offer, decline/withdraw independence, cooldown, confirm tokens, `delegableCaps` in core | `service/Service.kt`, `Model.kt`, `command/Commands.kt`, `Config.kt`, tests in `ProvinceTest.kt` |
| Confirm tokens for disband/president/give | `service/Service.kt`, `command/Commands.kt` |
| Pages: shell with sidebar, **Dashboard**, **Provinces** (incl. agreement wizard §17.4), **Citizens** | `client/pages/*`, `client/ClaimsApp.kt` |

**Accept**: a player can't become a province without seeing the wizard (GUI) or the warning (command); an overlord can grant/decline independence; every destructive action in these pages is tier ≥ 3; alerts appear for debt, offers, independence, join requests; `ProvinceTest` covers the new flows.

## Phase 3: In-world feedback

| Task | Files |
|---|---|
| `BorderFx` (modes, triggers, particle curtain, budget), keybind `B` | `client/world/BorderFx.kt`, `client/ClientHooks.kt` |
| `BlockHint` (red/amber outline, Alt tooltip) | `client/world/BlockHint.kt` |
| `Denied` payload + `Guard.check` reasons + rate limit + vanilla fallback | `world/Guard.kt`, `net/Net.kt` |
| Improved server `/claims border` fallback | `world/Effects.kt` |
| HUD territory pill with border distance, territory banner toast | `client/hud/*` |

**Accept**: standing next to a tree that crosses into nomansland, the curtain visibly cuts through the canopy and the out-of-claim logs have an amber outline before breaking; a denied break names the owner/reason and the distance past the border; particle budget holds (no frame drop > 1 ms on the test machine).

## Phase 4: Strategic map

| Task | Files |
|---|---|
| `TerrainSampler`, `TerrainCache` (memory + disk), `TerrainTiles` (mips, LRU) | `client/map/*` |
| `MapView` with modes, layers, borders, markers, labels, legend | `client/map/MapView.kt`, `MapModes.kt`, `MapLayers.kt` |
| Tools (select/area/brush/measure/pan), side panel contexts | `client/map/MapTools.kt`, `client/pages/MapPage.kt` |
| `planClaim` refactor + `claim_preview`/`unclaim_preview`/`retype_preview` + `Detail.access` | `service/Service.kt`, `service/View.kt`, tests |
| MiniMap component for drawers/dialogs | `client/map/MiniMap.kt` |
| Optional Xaero tile adapter (soft) | `client/map/XaeroTiles.kt`, mixin config |

**Accept**: explored terrain shows and persists across restarts; claim preview matches the real outcome in tests (`planClaim` shared); map frame ≤ 1.5 ms; each mode renders with a correct legend; old screen removed.

## Phase 5: Economy and law pages

| Task | Files |
|---|---|
| History + Ledger storage, writes in `Upkeep`/`Bank`/`Service` | `economy/History.kt`, `economy/Ledger.kt`, `service/Upkeep.kt`, `economy/Bank.kt`, tests in `UpkeepTest.kt` |
| Pages: Statistics, Budget (with what-if), Ledger, Chunks, Plots, Jobs, Ranks, Protection (staged + presets), Plot law, Relations, World | `client/pages/*` |
| `ActBatch` for staged edits | `net/Net.kt`, `service/Service.kt` |
| Country emblem designer (D7) + emblem sync | `Model.kt`, `client/pages/IdentityPage.kt` |

**Accept**: every former tab's function is reachable in the new pages; staged edits apply atomically with per-field errors; budget forecast equals the server runway.

## Phase 6: Onboarding, help, polish

Welcome + found-country wizard, Next steps, coachmark tour, help overlays per page, guide and glossary, settings page, sounds, reduce-motion and colour-blind modes, chat deep links (`/claims open <route>`), world event toasts, optional in-world claim mode.

**Accept**: a new tester with no instructions founds a country, claims land, sets tax, invites someone and understands the province warning, without asking anything (run this test with 2–3 people who haven't seen the mod).

## Phase 7: Art pass and other mods

- Replace placeholder sprites, icons, glyphs and illustrations with final hand-drawn art (lists in 01-design-system §6, §8, §9). No code changes needed.
- Migrate **KamiEconomy** (`MarketScreen`, its `Ui.kt`), **KamiGeology** (`HeatmapScreen`) and KamiEssentials screens to the framework; delete their private `Ui` helpers.

---

## Testing strategy

| Level | What |
|---|---|
| Unit (no MC) | layout engine, formatters, rich text parser, glyph fallback, store diffing, table sort/filter |
| Unit (server) | `planClaim`, alerts conditions, province flows, confirm tokens, ledger/history, `ActBatch` |
| Gallery | visual review of every component state at GUI scales 1–4, each breakpoint, both themes, colour-blind modes |
| Manual scripts | per-phase checklist on the test server with two clients (leader + citizen) and one vanilla client (fallbacks) |
| Performance | `F3` frame-time graph + a debug overlay (`/kamiui perf`) showing UI render ms, map ms, particle count |
| Usability | phase 6 test with fresh players; note every question they ask, and treat each question as a bug |

## Risks

| Risk | Mitigation |
|---|---|
| Scope is large | phases ship independently; phases 2–3 fix the reported pain points first |
| Art bottleneck | placeholder generator; art is a pure asset swap |
| Pixel font limits (sizes) | integer scales + weight/case hierarchy (01 §2) |
| Map memory | mips + LRU + disk cache limits |
| Particle performance | per-second budget, distance density, setting |
| Protocol changes break older clients | protocol version + fallback to commands |
| Other GUI mods / resource packs | sprites in our own namespace, theme JSON overridable, no global GUI mixins |

## Rough effort

| Phase | Size |
|---|---|
| 0 Foundations | L |
| 1 Components | XL |
| 2 Safety & guidance | L |
| 3 In-world feedback | M |
| 4 Map | XL |
| 5 Economy & law pages | L |
| 6 Onboarding & polish | M |
| 7 Art & migration | L (art) + M (code) |
