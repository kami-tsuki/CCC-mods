# Kami UI Overhaul: Overview

A full redesign of the KamiClaims interface, built on a new UI framework in KamiLibs that KamiEconomy, KamiGeology and KamiEssentials will also use.

The goal: KamiClaims should feel like the control room of an **economic and geopolitical simulation** (think Paradox-style strategy consoles), not a list of grey buttons. A player opening the screen for the first time must see what state their country is in, what needs attention, and what to do next, without guessing.

| Document | Contents |
|---|---|
| [00-overview.md](00-overview.md) | Vision, principles, current-state audit, architecture, decisions |
| [01-design-system.md](01-design-system.md) | Tokens: colour, type, spacing, elevation, motion, icons, glyphs, sprites, illustrations |
| [02-components.md](02-components.md) | Component library: anatomy, states, behaviour and API of every widget |
| [03-patterns.md](03-patterns.md) | Modals, confirmations, forms, validation, feedback, highlights, tooltips, empty/loading/error states, keyboard |
| [04-claims-pages.md](04-claims-pages.md) | Information architecture and every page of KamiClaims with wireframes |
| [05-map.md](05-map.md) | Strategic map: terrain rendering, map modes, layers, tools, claim planning |
| [06-world-feedback.md](06-world-feedback.md) | In-world borders (particles), block highlights, denial feedback, HUD, notifications |
| [07-data-contracts.md](07-data-contracts.md) | Server and network changes: alerts, ledger, history, forecasts, previews, request ids |
| [08-roadmap.md](08-roadmap.md) | Phases, file-level tasks, acceptance criteria, testing |

---

## 1. Vision

**"The Atlas"**: a dark slate strategy console with brass accents around a parchment map.

- **Information first**: every page opens with the numbers that matter (treasury, net income, runway, territory, alerts), shown as KPI tiles with trends, not as sentences.
- **Guided action**: the game tells you what needs doing. An *Alerts* feed (debt, expiring plots, pending requests, province demands) links straight to the page and element that resolves it.
- **Consequences before commitment**: every action that costs money, gives up authority, or removes something shows its effect *before* you confirm (cost, new daily upkeep, who loses access, what you can't undo).
- **One visual language across all Kami mods**: the same buttons, tables, modals and colours in Claims, Economy, Geology and Essentials.
- **Minecraft-native**: pixel-art sprites at native resolution, the vanilla font, GUI-scale aware, re-skinnable through resource packs.

## 2. Design principles

1. **Show state, then options.** A page starts with *where you stand* (KPIs, status badges), then *what you can do* (actions), then *details* (tables, history).
2. **Never make players guess.**
   - No hidden cycle-buttons. Every choice shows its options (dropdown, segmented control, radio).
   - Every disabled control explains why ("Needs Chancellor", "Select a member first", "Treasury short by 120 ◎").
   - Every number has a unit and, where useful, a comparison (per day, % of income, vs last week).
3. **Severity is consistent.** Neutral, Info, Success, Warning, Danger each have one colour, one icon and one sound. Colour is never the only signal: there is always an icon or shape as well (colour-blind safe).
4. **Reversible by default, protected when not.** Unsaved changes are staged and applied together. Irreversible actions need a hold-to-confirm or typed confirmation.
5. **Everything is linked.** A chunk in a table opens on the map, a member name opens their profile, an alert opens the right page with the target highlighted. Chat messages carry the same links.
6. **Feedback where the action happened.** A pending spinner on the pressed button, a toast with the result, a value flash on changed numbers, a sound.
7. **Progressive disclosure.** Basic view for citizens, full controls for leadership, "Advanced" sections folded away.
8. **Fast.** The screen never rebuilds all widgets on a server update (today's `rebuildWidgets()` loses focus and scroll position). Components update in place.

## 3. Current-state audit (why this overhaul)

| Area | Today | Problem |
|---|---|---|
| Visuals | Vanilla grey `Button`s, flat `fill()` rectangles (`ClaimsScreen.btn`, `Theme`) | No hierarchy, looks unfinished, nothing draws the eye |
| Navigation | 10 equal tabs in one row, tabs vanish when not usable | No grouping, layout jumps, no sense of where things are |
| Symbols | ASCII stand-ins `v x * + -` (`Ui.CHECK`...) | Cryptic |
| Choices | Cycle buttons: type filter, job, plot role, all Rules cells, Percent/Flat | Options invisible, a misclick changes live server state |
| Rules | Grid of text buttons "citizen/allied/none" | Unreadable, unexplained, every click is sent immediately |
| Inputs | Free `EditBox` for amounts and names | No validation, no autocomplete, no units |
| Feedback | One line of text at the bottom for 7 s | Errors missed, no link to what went wrong |
| Safety | Only Disband confirms | Kick, Banish, Leave, Unclaim-area, Evict, presidency and **all province actions** are one click |
| Provinces | Accept is one click in a list; request binds before terms are known; no "decline independence" | Players give up authority without knowing it |
| Map | Flat colour squares, no terrain, Shift+drag hidden, 4-item legend | Can't relate map to world, can't discover area selection |
| Economy view | A few numbers in the overview | No history, no forecast, no breakdown of where money goes |
| In world | `/claims border` only, server particles at fixed height, "You can't do that here" | Border invisible on slopes/trees, denials unexplained |
| Onboarding | None | New players don't understand countries, types, upkeep or plots |
| Code | Manual pixel math per module, `rebuildWidgets()` on every snapshot | Fragile layouts, lost focus/scroll, hard to extend |

## 4. Architecture

### 4.1 Where code lives

```
KamiLibs  kami.libs.ui
├── theme/      Tokens, Palette, ThemeLoader (JSON, resource-pack overridable), Severity
├── render/     Draw (nine-slice, text styles, icons, gradients, shadows), Clip stack, Anim (clock, easing, springs)
├── layout/     Node, Row, Column, Grid, Stack, Split, Scroll, Spacer, Insets, measure/arrange
├── input/      FocusManager, KeyMap/Shortcuts, DragController, HitTest
├── widget/     all components of 02-components.md
├── overlay/    ModalHost, ToastHost, TooltipHost, PopoverHost, CoachmarkHost
├── data/       TableModel, Sort/Filter, Formatters (money, %, duration, relative time, compact numbers)
├── chart/      Line, Area, Bar, StackedBar, Donut, Sparkline (evolves ui/graph/Chart.kt)
├── text/       Rich text markup ({b}, {icon:coin}, {link:page}), i18n helper, Glyphs table
├── app/        KamiApp shell: TopBar, Sidebar, Router, Page, Breadcrumbs, history, deep links
└── gallery/    Component gallery screen (/kamiui gallery), dev only
KamiLibs  assets/kami_libs/
├── textures/gui/sprites/kami/…   shared sprites (+ .mcmeta nine-slice)
├── textures/gui/icons/…          16×16 icons
├── textures/font/icons8.png      8×8 inline icon glyphs
├── font/ui.json                  default font + icon glyph provider
└── kami_theme/atlas.json         default theme tokens

KamiClaims  kami.claims.client
├── store/      ClaimsStore (snapshot + derived selectors + pending requests), Selectors
├── pages/      one file per page (04-claims-pages.md)
├── map/        TerrainCache, TerrainTiles, MapView, MapModes, MapTools (05-map.md)
├── world/      BorderFx, BlockHint, DenialFx (06-world-feedback.md)
├── hud/        TerritoryHud, AlertToasts
└── ClaimsApp   KamiApp subclass: sidebar config, routes, top bar KPIs
KamiClaims  assets/kami_claims/
├── textures/gui/icons/claims/…   chunk types, ranks, capabilities, map markers
└── textures/gui/illustrations/…  empty-state and onboarding drawings
```

### 4.2 Retained component tree instead of `rebuildWidgets()`

- A page builds its component tree **once**. Components read from `ClaimsStore` through selectors and repaint when their slice changes.
- A server snapshot updates the store. The store diffs old/new values, notifies subscribers, and changed numeric values *flash* (03-patterns §7).
- Text fields, scroll positions, selections, open dropdowns and focus survive updates.
- Vanilla `AbstractWidget` is still used under the hood where it helps (narration, `EditBox` text editing), wrapped by Kami components.

### 4.3 Request lifecycle

Today `act(...)` is fire-and-forget. New: every action gets a request id (`rid`, 07-data-contracts §2):
1. Button enters *pending* (spinner, disabled, no double submit).
2. Server replies with `Result(rid, ok, message, reason, target)`.
3. The button leaves pending; a toast shows the result; on failure the relevant field is marked invalid with the reason.
4. Timeout 5 s → "No answer from the server" warning toast, button re-enabled.

## 5. Decisions (defaults chosen, change any)

| # | Question | Default in this plan |
|---|---|---|
| D1 | Theme | "Atlas": dark slate + brass, parchment map. A "Parchment" light theme ships later as an alternative |
| D2 | Province request flow | Approving a request creates an offer the requester must accept through the warning flow |
| D3 | Terrain for unexplored land | Explored-only (no information leak), optional Xaero tile reuse |
| D4 | In-world border default | *Auto* (appears near borders and on blocked actions) |
| D5 | Member positions on map | Server config, default off |
| D6 | Framework location | KamiLibs, migrated to by all Kami mods |
| D7 | Country flags | Simple banner designer (colour + pattern + emblem), phase 5 |
| D8 | History retention | 90 days of daily country statistics, 300 ledger entries per country |
