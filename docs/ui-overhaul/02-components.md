# 02: Component Library (`kami.libs.ui.widget`)

Every component:
- is a `Node` in the layout tree (measure → arrange → render → input),
- takes its look only from tokens and sprites (01-design-system),
- supports the shared **states** below where they apply,
- is keyboard reachable (Tab / Shift+Tab, Enter/Space, arrows), and narrates through vanilla's `NarratableEntry`,
- has an entry in the component gallery (`/kamiui gallery`) that shows every variant and state.

### Shared states

| State | Visual | Notes |
|---|---|---|
| default | base sprite | n/a |
| hover | `_hover` sprite, cursor-style highlight | 90 ms fade |
| pressed | `_pressed`, content shifts 1 px down | n/a |
| focused | 1 px `sem.focus` ring outside the element | keyboard only, not on mouse click |
| disabled | `_disabled`, `text.disabled`, lock icon if space allows | **tooltip always states the reason** (`disabledReason` is required when `enabled = false`) |
| selected | `bg.selected` + 2 px brass indicator on the left edge | lists, tabs, chips |
| invalid | `_invalid` (red border) + inline error text below | forms |
| pending | spinner replaces the icon, label kept, input blocked | request in flight (00-overview §4.3) |
| dirty | brass dot in the corner, "unsaved" outline | staged edits |
| attention | pulsing `sem.warning` glow (1.2 s loop) | items an alert points to |

### API style

```kotlin
column(gap = Space.md) {
    heading("Treasury", icon = Icons.TREASURY)
    row(gap = Space.sm) {
        currencyField(state = amount, max = { store.funds }, label = "Amount")
        button("Deposit", icon = Icons.DEPOSIT, style = Primary, enabled = { amount.valid }, disabledReason = { amount.error }) { store.act("deposit", amount.text) }
    }
}
```
A small Kotlin DSL builds the tree. `state` objects (`TextState`, `NumberState`, `Selection<T>`) hold user input separately from server data.

---

## 1. Layout primitives (`kami.libs.ui.layout`)

| Node | Behaviour |
|---|---|
| `Row` / `Column` | flex along one axis: `gap`, `padding`, `align`, `justify`, per-child `weight`, `min`/`max` |
| `Grid` | fixed or `fr` columns, row/col span; used by dashboards and forms |
| `Stack` | children on top of each other (badges on icons, overlays on the map) |
| `Split` | two panes with a draggable divider (list + detail), remembers ratio per page |
| `Scroll` | vertical/horizontal scroll with themed scrollbar, wheel + drag + keyboard, scissor clipping, *scroll into view* API |
| `Spacer` / `Divider` | flexible gap / 1 px line with optional label (`── Danger zone ──`) |
| `Responsive` | swaps child trees by breakpoint (`compact` / `regular` / `wide`) |

Pure Kotlin, unit-tested without Minecraft (measure/arrange take a `TextMeasurer` interface).

---

## 2. Containers

### 2.1 AppWindow
```
╔═[brass frame]══════════════════════════════════════════════════╗
║ TopBar                                                         ║
╟────┬───────────────────────────────────────────────────────────╢
║Side│ Breadcrumbs  › Territory › Map                  [?] [⚙]   ║
║bar │ Page content                                              ║
║    │                                                           ║
╚════╧═══════════════════════════════════════════════════════════╝
```
Draws `window` sprite + backdrop blur, hosts overlays (modal, toast, tooltip, popover, coachmark) in z-order above the page.

### 2.2 TopBar
Left: country emblem (flag 16×12) + name (heading) + rank chip. Middle: **KPI chips**. Right: alerts bell with badge, help, settings, close.
KPI chip = icon + value + trend arrow + tooltip with breakdown. Click → linked page.

### 2.3 Sidebar
Grouped nav items with section labels. Item = icon + label + optional badge (count, severity colour). States: hover, active (brass bar left), disabled (lock + reason). Collapses to icon-only in `compact`. Keyboard: Up/Down to move, Enter to open. `Ctrl+1…9` jumps to groups.

### 2.4 Page
Title row (title, subtitle, page actions on the right), optional **SubTabs**, content. Pages declare `route`, `title`, `icon`, `requires` (capability / has-country), `badge` selector.

### 2.5 Card
```
┌─ ⛁ TREASURY ───────────────────────── ⋯ ┐
│ content                                   │
│                                  [Action] │
└───────────────────────────────────────────┘
```
Header (icon, title, optional help `ⓘ`, overflow menu `⋯`), body, optional footer actions. Variants: default, `severity` (left accent bar + tinted header), `interactive` (whole card clickable, hover lift).

### 2.6 Drawer
Side panel sliding in from the right (wide: docked; compact: overlay with backdrop). Used for detail views (member profile, chunk detail, province detail). Header with title + close, scrollable body, sticky footer actions.

### 2.7 SubTabs
Horizontal tabs under the page title with icon + label + badge. Underline indicator animates between tabs (160 ms).

### 2.8 Section / FieldGroup
Heading + description + content; FieldGroup lays form fields in a 2-column label/control grid that collapses to 1 column in `compact`.

---

## 3. Buttons

| Variant | Look | Use |
|---|---|---|
| `Primary` | brass sprite, dark text | the one main action of a view |
| `Secondary` | slate sprite | normal actions |
| `Ghost` | transparent until hover | toolbars, table row actions |
| `Danger` | crimson sprite | destructive (opens a confirm, never acts directly) |
| `IconButton` | square, icon only, **tooltip required** | toolbars, compact rows |
| `SplitButton` | main action + ▾ menu | "Claim ▾ (as Mining / Farming …)" |
| `LinkButton` | `text.link` | inline navigation |
| `HoldButton` | fill bar grows over 1.5 s while held; releasing early cancels | irreversible confirms |

Anatomy: `[icon] Label [shortcut keycap]`, height 18 (14 small, 22 large). Pending state shows a spinner in place of the icon. Width fits the label unless it's stretched by layout; labels never truncate (min width = label).

---

## 4. Inputs

### 4.1 TextField
```
 LABEL                              12/24
┌─────────────────────────────────────┐
│ ⌕ placeholder text               ✕ │
└─────────────────────────────────────┘
 helper text or ✖ error message
```
Label above, optional prefix icon, clear button, character counter, helper/error line. Wraps vanilla `EditBox` for editing (selection, clipboard). Validation runs on every change; the error shows after first blur or submit. Enter submits the enclosing form.

### 4.2 NumberField
`[ − ][   120   ][ + ]  ◎/day`: steppers (Shift ×10, Ctrl ×100), min/max clamp with a hint ("max 500"), mouse wheel while hovered, unit suffix. Invalid input shows the reason and never sends.

### 4.3 CurrencyField
NumberField + coin glyph + quick buttons `10` `100` `Max` + "You have 1,240 ◎" helper. Max is computed (funds for deposit, treasury for withdraw).

### 4.4 Slider
Track + fill + knob, ticks, value bubble while dragging, bound labels. Keyboard arrows step. Used for percentages (province tribute, job share view).

### 4.5 Checkbox / Radio / Toggle
- Checkbox 12×12 with label, tri-state (mixed) for "select all" in tables.
- RadioGroup vertical or horizontal, each option with an optional description line.
- Toggle 22×12 with "On/Off" label to the right. Used for boolean rules (fire, fluid, machines, map layers, settings).

### 4.6 SegmentedControl
2–5 options in one bar, icon + label, one selected. Replaces every "click to cycle" button (Percent/Flat, plot filter Mine/Free/All, map tool).

### 4.7 Select (Dropdown)
```
 ACCESS
┌───────────────────────── ▾ ┐     ┌────────────────────────────────┐
│ 👥 Citizens                 │ ──▶ │ ⌕ filter…                      │
└─────────────────────────────┘     │ ⛔ Nobody                       │
                                    │ 🛡 Officers and up              │
                                    │ ⚒ Assigned job only             │
                                    │ ⚒ Any worker                    │
                                    │✔👥 Citizens     all members     │
                                    │ 🤝 Allies       + allied players │
                                    │ 🌍 Everyone                      │
                                    └────────────────────────────────┘
```
Options with icon, label, description, disabled + reason. Type-ahead filtering above 7 options. Keyboard: arrows, Enter, Esc. Opens as a popover that flips upward near the screen bottom.

### 4.8 Combobox / Picker
Autocomplete for **players** (heads + home country) and **countries** (emblem + member count). Sources: `snap.players`, `snap.countries`. Invalid entries are impossible; free text only if `allowCustom`.

### 4.9 SearchField
TextField with search icon, 150 ms debounce, `Ctrl+F` focuses it, result count ("12 of 80").

### 4.10 ColorPicker / EmblemPicker
Swatch grid (country palette of 16) + custom hex field + live preview on a mini map chunk. EmblemPicker: pattern + emblem icon grid + preview banner (D7).

---

## 5. Read-only display

| Component | Anatomy | Use |
|---|---|---|
| `PropertyRow` | `LABEL ............ value [⧉]` | detail drawers, read fields; copy button for coordinates/ids |
| `PropertyGrid` | 2–4 columns of PropertyRows | chunk detail, member detail |
| `StatTile` (KPI) | icon, label, **display value**, delta (`▲ +12 %` vs 7 d), sparkline | dashboard, top of pages |
| `Meter` | segmented bar with thresholds and marker (e.g. debt 2/3) | debt level, quota progress, runway |
| `ProgressBar` | track + fill, label "340 / 500" | job quota, plot lapse |
| `Badge` | small count or dot, severity colour | sidebar, tabs, bell |
| `Chip` / `Tag` | icon + text in a pill; removable variant | rank, type, relation, filters |
| `StatusPill` | dot + text (`● Stable`, `● In debt`) | country status, plot status |
| `Avatar` | player head 8/16/24 px from the skin | member lists, map |
| `Emblem` | country flag 12×9 / 24×18 | anywhere a country is named |
| `Money` | amount + coin, sign colour, compact option | everywhere money appears |
| `Duration` | "3d 4h", tooltip with exact date (UTC) | lapses, billing, expiry |
| `Timeline` | horizontal steps with dates and icons | plot lapse chain, debt → unclaim → reserve |
| `KeyHint` | keycap sprites `[Shift]+[LMB]` | tooltips, toolbars |
| `Callout` | icon + text box, severity tinted | inline explanations |
| `Illustration` | 64 px drawing + title + text + action | empty states |

---

## 6. Collections

### 6.1 DataTable
```
┌───┬──────────────┬───────────┬──────────┬──────────┬─────────┬───┐
│ ☐ │ CHUNK      ▲ │ TYPE      │ UPKEEP/d │ STATUS   │ PLOT    │   │
├───┼──────────────┼───────────┼──────────┼──────────┼─────────┼───┤
│ ☐ │ 12, -4 ♛     │ ⛏ Mining  │     3 ◎  │ ● OK     │ n/a     │ ⋯ │
│ ☑ │ 13, -4       │ ⌂ Resid.  │     5 ◎  │ ● Debt 2 │ Steve   │ ⋯ │
└───┴──────────────┴───────────┴──────────┴──────────┴─────────┴───┘
  2 selected  [Set type ▾] [Unclaim…]                    1–50 of 212
```
- Sortable columns (click header, Shift for secondary sort), column visibility menu, numeric columns right-aligned.
- Row: hover, select (click), multi-select (checkbox, Shift range, Ctrl toggle), double-click = open detail, right-click = context menu, `⋯` row menu.
- Sticky header, virtual scrolling (thousands of rows), keyboard navigation.
- **Bulk action bar** appears when rows are selected.
- Filter chips above the table (`Type: Mining ✕`, `Status: Debt ✕`), search field, "Clear filters".
- Empty and "no results" states differ ("No chunks yet, go to the map" vs "No chunks match these filters, [Clear filters]").

### 6.2 List
Simple rows with avatar/icon, title, subtitle, trailing meta and actions. Used where a table is overkill (invites, requests).

### 6.3 TreeView / FamilyGraph
Node-link drawing for the **province family**: overlord at top, provinces below, lines coloured by tribute health, nodes show emblem, name, members, tribute. Click a node → province drawer.

### 6.4 Charts (`kami.libs.ui.chart`, built from `ui/graph/Chart.kt`)
Line, Area, StackedArea, Bar, StackedBar, Donut, Sparkline. Shared: axes with nice ticks, hover crosshair with a value tooltip, legend with toggles, empty state, forecast segment drawn dashed, threshold lines (e.g. "0 ◎").

---

## 7. Overlays (`kami.libs.ui.overlay`)

| Component | Behaviour |
|---|---|
| `Tooltip` | 400 ms delay (0 ms when moving between tooltipped items), follows 12 px below-right of the cursor and flips at edges, max width 200 px, wraps. Variants: simple text, **rich** (title, icon rows, money, bars, key hints), **disabled reason** (lock icon, warning tint). Also shown on keyboard focus |
| `Popover` | anchored panel (dropdowns, filter menus, color picker); click outside or Esc closes |
| `ContextMenu` | right-click menu with icons, shortcuts, separators, submenus, disabled items with reasons |
| `Modal` | see 03-patterns §1 |
| `Toast` | top-right stack, max 4, severity icon + title + text + optional action link, auto-dismiss 4 s (info/success), 8 s (warning), sticky (danger), hover pauses |
| `Banner` | inline, full width, severity; can be dismissible; used on top of pages ("You are managing province X") |
| `Coachmark` | onboarding spotlight: dims everything except a target rect, arrow + text bubble + Next/Skip, step counter |
| `Spinner` / `Skeleton` | pending state / placeholder shimmer while data loads |

---

## 8. Map components (details in 05-map.md)

`MapView` (terrain + layers + markers, pan/zoom), `MapToolbar`, `MapModeSwitcher`, `MapLegend` (auto from active layers), `MiniMap` (static preview for drawers and dialogs), `Compass`, `ScaleBar`, `CoordinateReadout`.

---

## 9. Component gallery

`/kamiui gallery` (client command, dev setting or permission) opens a screen listing every component in every state and variant, with a theme and breakpoint switcher. It's the living spec: art and code are reviewed there before pages use them.
