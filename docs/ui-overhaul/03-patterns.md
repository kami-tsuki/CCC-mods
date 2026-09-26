# 03: Interaction Patterns

Rules every page follows. If a page needs something not covered here, the pattern is added here first.

---

## 1. Modals

### 1.1 Types

| Type | Size (scaled px) | Frame | Buttons | Use |
|---|---|---|---|---|
| `Info` | S 240 | normal | [OK] | explanations, "what is this?" |
| `Confirm` | S 260 | normal | [Cancel] [Confirm] | reversible but notable actions (claim 12 chunks) |
| `Destructive` | M 320 | crimson + hazard strip | [Cancel] [Hold to …] or typed confirm | irreversible or authority-losing actions |
| `Form` | M 320 | normal | [Cancel] [Save] | small edits (set tribute, invite player) |
| `Wizard` | L 420 | normal + step indicator | [Back] [Next] / [Finish] | multi-step flows (found country, province agreement) |
| `Picker` | M 320 | normal | [Cancel] [Select] | choose a player/country/chunk type with search |

### 1.2 Anatomy
```
┌──────────────────────────────────────────────────────┐
│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒ hazard strip (destructive only) ▒▒▒▒▒▒│
│ [icon32]  TITLE IN CAPS                           ✕  │
│           One sentence: what happens.                │
├──────────────────────────────────────────────────────┤
│ Body: consequences, numbers, lists, mini map         │
├──────────────────────────────────────────────────────┤
│ ☐ optional acknowledgement                           │
│                         [ Cancel ]  [ Primary ▶ ]    │
└──────────────────────────────────────────────────────┘
```

### 1.3 Rules
- Focus is trapped inside; **Esc = Cancel**, **Enter = primary** (except destructive: Enter does nothing, you must hold or type).
- The cancel button is always on the left, the primary on the right, and the danger action is never the default focus.
- The backdrop dims and blurs the page; clicking it does nothing for Destructive/Wizard (no accidental dismiss), closes Info.
- Max one modal plus one nested picker. Modals never open on their own; alerts go to toasts or the alerts feed instead.
- Server-side changes while a modal is open: if the target no longer exists (member left, province released), the modal shows an inline banner "This changed while you were looking" and disables the primary action.

## 2. Confirmation tiers

| Tier | When | How |
|---|---|---|
| 0 None | reversible, cheap, personal (toggle a map layer, filter) | act immediately |
| 1 Staged | editing settings (rules, jobs, taxes) | changes are staged, "Apply (3 changes)" bar, "Discard" |
| 2 Confirm | costs money or affects others reversibly (claim area, invite, set tax, promote) | `Confirm` modal with the effect summary |
| 3 Hold | removes something or someone (unclaim, kick, banish, evict, leave, release province) | `Destructive` modal + **hold 1.5 s** |
| 4 Typed | irreversible and large (disband country, transfer presidency, **become a province**, give province away) | `Destructive` modal + type the country/player name, then hold |

Every tier ≥ 2 modal lists: **what happens**, **what it costs** (now and per day), **who is affected** (with count), **whether it can be undone and by whom**.

## 3. Staged editing (tier 1)

```
┌──────────────────────────────────────────────────────────────────────┐
│ ● 3 unsaved changes    Mining › Break: Citizens → Assigned job   …   │
│                                            [ Discard ]  [ Apply ▶ ] │
└──────────────────────────────────────────────────────────────────────┘
```
- Changed controls get the **dirty** state (brass dot, outlined).
- The apply bar slides up from the page bottom; hovering "3 unsaved changes" lists them.
- Leaving the page with unsaved changes opens "Apply or discard?" (Apply / Discard / Stay).
- Apply sends one batched request (07-data-contracts §2.3); per-field errors come back and mark the fields.

## 4. Forms and validation

- Labels above controls, helper text below, errors replace helper text in `sem.danger` with a ✖ icon.
- Validate client-side for format and range (instant); server-side for rules (on submit). Server errors map to fields via `Result.field`.
- Primary button stays enabled while the form is invalid; clicking it focuses the first invalid field and shakes it (2 px, 150 ms). This teaches better than a disabled button without explanation.
- Numbers: accept `1k`, `1.5k`, `1,000`; show the parsed value ("= 1,500 ◎").
- Names: live availability check for country names (debounced request), shown as ✔ available / ✖ taken.

## 5. Feedback

| Moment | Feedback |
|---|---|
| Click | press sprite + click sound |
| Request sent | pending spinner on that control; other controls stay usable |
| Success | success toast (title + consequence: "Claimed 12 chunks, upkeep now 64 ◎/day"), chime, **value flash** on changed numbers, affected map cells glow once |
| Failure | danger toast with the specific reason and a fix link ("Not connected to your land. [Show on map]"), the control is marked invalid, bass note |
| Partial success | warning toast "Claimed 9 of 12 chunks" + [Details] opens a list of failed chunks with reasons |
| Background change | info toast (member joined, upkeep billed) + badge updates; no sound unless it's a warning/danger |

**Value flash**: when a displayed number changes after a snapshot, its background tints green (up) or red (down) and fades over 900 ms. Money going down that's expected (the result of your own action) flashes neutral.

## 6. Alerts: telling players what to do

The server computes **alerts** (07-data-contracts §3). They appear in:
1. the **bell** in the top bar (badge = count, colour = highest severity),
2. the **Dashboard → Needs attention** card,
3. inline banners on the related page,
4. HUD toasts in the world for danger-level ones (optional).

Each alert has: severity, icon, title, one-line explanation, **one recommended action** (button), and a link that navigates to the target and **highlights** it (§7).

Example: "⚠ 3 chunks will be unclaimed in 2 days. Treasury is 140 ◎ short for the next billing. [Deposit 140 ◎] [Show chunks]".

## 7. Highlighting

| Highlight | Look | Trigger |
|---|---|---|
| Focus ring | 1 px `sem.focus` | keyboard focus |
| Selection | `bg.selected` + brass left bar | selected rows/items |
| Hover | `bg.hover` | mouse over interactive elements |
| Attention pulse | `sem.warning` glow pulsing 1.2 s, 3 times, then a static outline | target of an alert link / deep link |
| Spotlight | everything else dimmed to 40 % | coachmarks, "show me" |
| Dirty | brass corner dot + outline | staged edit |
| Changed value | green/red fade | snapshot diff |
| Search match | matched substring in `sem.money` bold | tables, lists |
| Map target | animated marching-ants border + ping ripple | "Show on map" |
| Row severity | left accent bar in severity colour | rows with problems (debt, lapse) |

## 8. Tooltips (content rules)

- **Every icon-only control has a tooltip.** Every disabled control has a reason tooltip.
- Structure: **Title** (bold) → what it is / does (1–2 lines) → current value/effect → key hints (`[Shift]+[LMB] select area`).
- Numbers in tooltips show their breakdown (upkeep tooltip: per type × count).
- Tables: truncated cells show the full text; column headers explain the column.
- Rich tooltips can show a mini map (chunk hover), a sparkline (KPI), or a rank matrix (capability hover).

## 9. Empty, loading, error states

| State | Pattern |
|---|---|
| Empty (first use) | illustration + title + one sentence + primary action ("No jobs yet. Jobs pay citizens for mining, farming and forestry. [Create first job]") |
| No results | "No results for *foo*" + [Clear filters] |
| Loading | skeleton rows/tiles with shimmer; never an empty table that "pops" in |
| Stale | if no snapshot for > 10 s, a muted banner "Waiting for server…" |
| Error | danger banner with the reason and [Retry] |
| No permission | lock illustration + "Only Chancellor and up can change laws. Your rank: Citizen." + link to the Ranks page |

## 10. Navigation and linking

- **Router** with routes `claims:dashboard`, `claims:map?x=12&z=-4&mode=political`, `claims:citizens/{uuid}`, …
- Back/forward history (mouse buttons 4/5, `Alt+←/→`, `Backspace` when no field is focused).
- **Deep links from chat**: server messages use `ClickEvent.RUN_COMMAND` → `/claims open <route>`, which opens the screen at that route (vanilla clients just see the text).
- Every entity reference is a link: country names → Countries page profile, player names → profile drawer, coordinates → map.
- Breadcrumbs show where you are; the sidebar keeps the active group expanded.

## 11. Keyboard

| Key | Action |
|---|---|
| `K` (configurable) | open/close the claims app |
| `Tab` / `Shift+Tab` | move focus |
| `Enter` / `Space` | activate |
| `Esc` | close popover → modal → drawer → deselect → close app (one step at a time) |
| `Ctrl+F` | focus the page search |
| `Ctrl+1…6` | sidebar groups |
| `Ctrl+S` | apply staged changes |
| `?` | help overlay for the current page |
| `M` | map page |
| Map: `WASD`/arrows pan, `+`/`-` zoom, `1…7` map modes, `C` center on me, `Space` hold to pan | see 05-map |

All shortcuts are listed in the help overlay and in tooltips as keycaps.

## 12. Help overlay (`?`)

Dims the page and draws numbered callouts on its main regions ("1 KPI tiles: your country's health at a glance"). Each page defines its callouts in lang files. There's also a "Learn more" link to a short in-game guide page (Help section).

## 13. Microcopy

- Second person, active voice, present tense: "You pay 5 ◎ per day", not "Taxes are paid".
- Name the consequence, not the mechanism: "Your country loses these 3 chunks in 2 days", not "Debt counter 2/3".
- Buttons are verbs with objects: "Claim 12 chunks", "Deposit 140 ◎", "Grant independence".
- No jargon without a tooltip: *upkeep*, *runway*, *lapse*, *tribute*, *province*, *reserve* all have a glossary tooltip (dotted underline).
- Numbers always with units; dates in relative + exact form ("in 2d 4h, Sep 28 12:00 UTC").

## 14. Accessibility and settings

Settings page (client prefs, `config/kami/claims/client.json` + `kami/library/ui.json` for shared UI prefs):
- Theme (Atlas / Parchment), colour-blind mode, reduce motion, UI sounds volume, tooltip delay, confirm-by-hold duration (1–3 s) or click-twice alternative, show keyboard hints, onboarding reset.
- Text never below 1× font; the app respects the GUI scale and adapts to breakpoints.
- Narrator: all components provide narration text (title, value, state, reason).
