# 01: Design System "Atlas"

All values here are **tokens**: named constants in `kami.libs.ui.theme.Tokens`, loaded from `assets/kami_libs/kami_theme/atlas.json`. Code never uses raw hex values. A resource pack or a server-supplied theme can override the JSON.

---

## 1. Colour

### 1.1 Surfaces (dark slate)

| Token | Hex | Use |
|---|---|---|
| `bg.backdrop` | `#000000` @ 60 % + vanilla blur | behind the app window |
| `bg.canvas` | `#111318` | app window background |
| `bg.surface` | `#181B21` | panels, cards |
| `bg.raised` | `#20242C` | headers, top bar, hovered cards |
| `bg.sunken` | `#0C0E12` | inputs, list wells, map frame |
| `bg.hover` | `#272C36` | row/button hover |
| `bg.selected` | `#2C3A52` | selected row, active tab |
| `bg.overlay` | `#1C2028` @ 96 % | modals, popovers, tooltips |
| `border.subtle` | `#262B34` | separators, inner lines |
| `border.default` | `#353B47` | panel outlines |
| `border.strong` | `#4A5262` | inputs, focusable outlines |
| `border.brass` | `#B08D57` | window frame trim, active tab indicator, primary emphasis |

### 1.2 Text

| Token | Hex | Contrast on `bg.surface` | Use |
|---|---|---|---|
| `text.primary` | `#ECEEF2` | 14.9:1 | values, titles |
| `text.secondary` | `#AEB5C1` | 8.3:1 | labels, body |
| `text.muted` | `#737B8A` | 4.1:1 | captions, hints, placeholders |
| `text.disabled` | `#4E5562` | n/a | disabled labels |
| `text.inverse` | `#111318` | n/a | text on bright fills (badges) |
| `text.link` | `#7FB2FF` | 7.2:1 | clickable text, underlined on hover |

### 1.3 Semantic (always paired with an icon)

| Token | Hex | Icon | Meaning |
|---|---|---|---|
| `sem.info` | `#56B6F7` | `info` | neutral information |
| `sem.success` | `#4CC38A` | `check` | done, healthy, positive money |
| `sem.warning` | `#F5A524` | `warning` | needs attention soon |
| `sem.danger` | `#F2555A` | `danger` | loss, irreversible, blocked |
| `sem.money` | `#F2C94C` | `coin` | all currency amounts |
| `sem.focus` | `#7FB2FF` | n/a | keyboard focus ring |

Each semantic token has `.bg` (18 % alpha fill) and `.border` (60 % alpha) variants for banners, badges and highlighted rows.

### 1.4 Geopolitical colours

| Token | Hex | Meaning |
|---|---|---|
| `geo.own` | country colour | your country |
| `geo.province` | `#A78BFA` | your provinces / your overlord |
| `geo.ally` | `#38BDF8` | allied |
| `geo.neutral` | `#9AA3B2` | other countries |
| `geo.banished` | `#F2555A` | banished from / hostile |
| `geo.nomansland` | `#6B6453` | unclaimed |
| `geo.reserved` | `#8C7A5B` | reserved after loss |

### 1.5 Chunk types (colour + icon, never colour alone)

| Type | Hex | Icon |
|---|---|---|
| civic | `#C9CED8` | `type.civic` (town hall) |
| mining | `#E0A050` | `type.mining` (pickaxe) |
| farming | `#8BCB6B` | `type.farming` (wheat) |
| forestry | `#3E9B63` | `type.forestry` (tree) |
| factory | `#6AA9FF` | `type.factory` (gear) |
| market | `#E07B9B` | `type.market` (scales) |
| residential | `#C9A0F0` | `type.residential` (house) |
| infrastructure | `#8A8FA3` | `type.infrastructure` (rail) |
| wilderness | `#6FA36B` | `type.wilderness` (pine) |
| unknown / config-added | hashed from the palette below | `type.generic` |

### 1.6 Ranks

| Rank | Hex | Icon |
|---|---|---|
| President | `#F2C94C` | `rank.president` (crown) |
| Chancellor | `#C9A0F0` | `rank.chancellor` (scroll) |
| Officer | `#7FB2FF` | `rank.officer` (shield) |
| Citizen | `#4CC38A` | `rank.citizen` (person) |
| Allied | `#38BDF8` | `rank.allied` (handshake) |
| Banished | `#F2555A` | `rank.banished` (ban) |

### 1.7 Data visualisation palette (Okabe-Ito, colour-blind safe)

`#E69F00` `#56B4E9` `#009E73` `#F0E442` `#0072B2` `#D55E00` `#CC79A7` `#999999`

Charts use these in order. Money-in charts use `sem.success`, money-out charts use `sem.danger`, and the balance line uses `sem.money`.

### 1.8 Colour-blind modes

A client setting (`Normal`, `Deuteranopia`, `Protanopia`, `Tritanopia`) swaps the semantic/geo tokens for safe alternatives (e.g. success → `#0072B2`, danger → `#D55E00`). Hatching and icons stay, so meaning never depends on hue.

---

## 2. Typography

Minecraft's font is a 9 px-line bitmap font. Scaling by non-integer factors blurs it, so the type scale uses **integer scales, weight (bold), case and colour**:

| Style | Scale | Weight | Colour | Use |
|---|---|---|---|---|
| `display` | 2× | regular, shadow | `text.primary` / `sem.money` | the one big number on a KPI tile or dialog |
| `title` | 1× | **bold**, UPPERCASE, letter-spaced +1 | `text.primary` | page titles, modal titles |
| `heading` | 1× | **bold** | `text.primary` | card and section headers |
| `body` | 1× | regular | `text.secondary` | descriptions, sentences |
| `label` | 1× | regular | `text.muted` | field labels, table headers (UPPERCASE) |
| `value` | 1× | regular | `text.primary` | values next to labels, right-aligned when numeric |
| `caption` | 1× | regular | `text.muted` | hints, footnotes, timestamps |
| `mono-num` | 1× | regular | inherits | numbers in tables; right-aligned, padded to equal digit widths |
| `link` | 1× | regular, underline on hover | `text.link` | navigation inside text |

Rules:
- Line height 11 px (9 + 2), paragraph gap 4 px.
- Max line length ≈ 60 characters. Longer text wraps (`Font.split`) and never overflows. `Theme.fit` ellipsis is only used for single-line table cells, with the full value in a tooltip.
- Numbers: thousands separators (`12,480`), compact form above 100k in tiles (`1.2M`), always a unit (◎, `/day`, `%`, `d`, `chunks`).
- Money: coin glyph after the amount, `sem.money` colour; negative amounts `sem.danger` with a minus sign, positive deltas with a `+` sign.
- All strings in lang files (`assets/kami_claims/lang/en_us.json`), none hardcoded.

---

## 3. Spacing, sizing, grid

- Base unit **2 px**. Scale: `xs 2` · `sm 4` · `md 6` · `lg 8` · `xl 12` · `2xl 16` · `3xl 24`.
- Control heights: small 14 px, regular 18 px, large 22 px. Icon-only buttons are square.
- Touch/click targets: at least 12×12 px, even for inline icons.
- Panel padding `lg` (8); card padding `md` (6); gaps between cards `lg`.
- Window: min 400×260, max 900×560 scaled pixels, 88 % of the screen.
- **Breakpoints** (scaled window width):
  - `compact` < 480: sidebar shows icons only; detail drawers overlay the content.
  - `regular` 480–720: sidebar with icons + labels, collapsible.
  - `wide` > 720: sidebar expanded, detail drawers dock side-by-side.

## 4. Shape and elevation

Pixel art, no anti-aliased curves.

| Level | Treatment | Use |
|---|---|---|
| 0 flat | fill only | page background, table rows |
| 1 panel | 1 px `border.default` + 1 px inner highlight `#FFFFFF` @ 4 % on the top edge | cards, panels |
| 2 raised | level 1 + 1 px shadow `#000` @ 50 % offset (1,1) | buttons, top bar, dropdown triggers |
| 3 overlay | 1 px `border.strong` + 2 px shadow @ 60 % + 1 px brass top trim | popovers, tooltips, toasts |
| 4 modal | 1 px brass frame + 3 px shadow + backdrop | modals |

"Rounded" corners = 1 px corner cut (the corner pixel stays transparent), baked into the nine-slice sprites.

## 5. Motion

A shared `Anim` clock uses real time, not ticks, so animation stays smooth at any FPS and while the game is paused.

| Token | Duration | Easing | Use |
|---|---|---|---|
| `motion.fast` | 90 ms | ease-out | hover, press |
| `motion.base` | 160 ms | ease-out | dropdown open, tab switch, drawer |
| `motion.slow` | 260 ms | ease-in-out | modal in/out, page transition |
| `motion.flash` | 900 ms | ease-out fade | changed-value flash |
| `motion.pulse` | 1200 ms loop | sine | attention pulse on items needing action |
| `motion.hold` | 1500 ms | linear | hold-to-confirm fill |

A "Reduce motion" setting turns off pulses, page transitions and flashes (flashes become a static outline).

---

## 6. Iconography

### 6.1 Sizes and style
- **16×16** master icons (`textures/gui/icons/…`) for buttons, tabs, table rows and map markers.
- **8×8** inline glyph icons (a bitmap font, §7) for use inside text, tooltips and chat.
- **32×32** feature icons for empty states, modal headers and onboarding.
- Style: 1 px dark outline (`#0C0E12`), 2–3 tone fill, light from top-left, readable on both `bg.surface` and parchment.
- Every icon has a semantic id; the code references ids, never file paths.

### 6.2 Icon set

**Navigation (16)**: `nav.dashboard` `nav.map` `nav.chunks` `nav.plots` `nav.budget` `nav.ledger` `nav.stats` `nav.citizens` `nav.jobs` `nav.ranks` `nav.laws` `nav.plotlaw` `nav.diplomacy` `nav.provinces` `nav.countries` `nav.players` `nav.settings` `nav.help`

**Actions (16)**: `act.add` `act.remove` `act.edit` `act.save` `act.undo` `act.search` `act.filter` `act.sort.asc` `act.sort.desc` `act.close` `act.back` `act.more` (⋯) `act.locate` (crosshair) `act.copy` `act.refresh` `act.deposit` `act.withdraw` `act.invite` `act.kick` `act.promote` `act.demote` `act.transfer`

**Status (8 + 16)**: `check` `cross` `info` `warning` `danger` `lock` `unlock` `pending` (animated 4 frames) `clock` `trend.up` `trend.down` `trend.flat` `star` `pin` `eye` `eye.off`

**Domain (16)**: `coin` `treasury` `upkeep` `tax` `debt` `income` `job` `quota` `capital` `flag` `border` `province` `tribute` `independence` `alliance` `banish` `crown` `law` `vote` (future) `war` (future)

**Chunk types**: see §1.5. **Ranks**: see §1.6. **Capabilities** (one per `Cap`): `cap.claim` `cap.capital` `cap.tax` `cap.rules` `cap.withdraw` `cap.invite` `cap.members` `cap.rank` `cap.jobs` `cap.plot` `cap.details` `cap.province`

**Rule actions** (one per `Action` + flags): `rule.break` `rule.place` `rule.interact` `rule.container` `rule.machines` `rule.fire` `rule.fluid`

**Access levels** (one per `Access`): `access.none` `access.officer` `access.job` `access.worker` `access.citizen` `access.allied` `access.any`

**Map markers (16, with 1 px outline for contrast on terrain)**: `mk.you` (player head, drawn from skin) `mk.capital` `mk.plot.mine` `mk.plot.free` `mk.debt` `mk.reserved` `mk.member` `mk.selection`

---

## 7. Inline glyphs and symbols

Symbols inside text use a **bitmap font provider** so they render crisply wherever text renders (screens, tooltips, chat, item lore):

```json
// assets/kami_libs/font/ui.json  (merged into minecraft:default through the "kami_libs:ui" font id)
{ "providers": [
  { "type": "reference", "id": "minecraft:default" },
  { "type": "bitmap", "file": "kami_libs:font/icons8.png", "ascent": 7, "height": 8,
    "chars": ["", "..."] }
]}
```

`kami.libs.ui.text.Glyphs` maps semantic names to private-use code points with a plain-text fallback. The fallback is used when the receiving client doesn't have the mod (server chat to vanilla clients; the mod is optional on clients):

| Name | Code point | Fallback | Name | Code point | Fallback |
|---|---|---|---|---|---|
| coin | U+E000 | `◎` | check | U+E008 | `✔` |
| up | U+E001 | `▲` | cross | U+E009 | `✖` |
| down | U+E002 | `▼` | warning | U+E00A | `⚠` |
| flat | U+E003 | `▶` | info | U+E00B | `ⓘ` |
| crown | U+E004 | `♛` | lock | U+E00C | `🔒`→`[L]` |
| house | U+E005 | `⌂` | clock | U+E00D | `⌚`→`(t)` |
| flag | U+E006 | `⚑` | link | U+E00E | `→` |
| shield | U+E007 | `⛨`→`[S]` | dot | U+E00F | `●` |
| key.lmb | U+E010 | `[LMB]` | key.rmb | U+E011 | `[RMB]` |
| key.shift | U+E012 | `[Shift]` | key.esc | U+E013 | `[Esc]` |

- Rich text markup in code and lang files: `"{icon:coin} {money:120}/day {b}Upkeep{/b}"`, parsed by `kami.libs.ui.text.Rich` into `Component`s.
- Box-drawing/ASCII art is used **only** in dev tools and chat tables (`/claims info`), where alignment uses the vanilla font's fixed-width space trick (`Theme.pad`). In screens everything is drawn with sprites.

---

## 8. Sprites (nine-slice)

Files under `assets/kami_libs/textures/gui/sprites/kami/`. Each non-icon sprite has a `.png.mcmeta` with `gui.scaling`. Sizes are source pixels.

| Sprite | Size | Scaling | States |
|---|---|---|---|
| `window` | 32×32 | nine_slice, border 7 (brass frame) | n/a |
| `panel` | 24×24 | nine_slice, border 4 | n/a |
| `panel_sunken` | 24×24 | nine_slice, border 3 | n/a |
| `card` / `card_header` | 24×24 / 24×14 | nine_slice, border 4 | `_hover` |
| `topbar` | 32×24 | nine_slice, border 4 | n/a |
| `sidebar` / `sidebar_item` | 24×24 / 24×20 | nine_slice, border 4 | `_hover` `_active` `_disabled` |
| `tab` (horizontal sub-tabs) | 24×18 | nine_slice, border 4 | `_hover` `_active` `_disabled` |
| `button` | 24×18 | nine_slice, border 4 | `_hover` `_pressed` `_disabled` `_focus` |
| `button_primary` (brass) | 24×18 | nine_slice, border 4 | same |
| `button_danger` (crimson) | 24×18 | nine_slice, border 4 | same |
| `button_ghost` | 24×18 | nine_slice, border 4 | same |
| `input` | 24×18 | nine_slice, border 3 | `_hover` `_focus` `_invalid` `_disabled` `_readonly` |
| `checkbox` | 12×12 | stretch | `_on` `_mixed` `_hover` `_disabled` |
| `radio` | 12×12 | stretch | `_on` `_hover` `_disabled` |
| `toggle` | 22×12 | stretch | `_on` `_hover` `_disabled` + knob 10×10 |
| `slider_track` / `slider_fill` / `slider_knob` | 16×6 / 16×6 / 8×14 | nine_slice border 2 / same / stretch | knob `_hover` `_drag` |
| `scrollbar_track` / `scrollbar_thumb` | 6×16 | nine_slice, border 2 | thumb `_hover` `_drag` |
| `dropdown_menu` | 24×24 | nine_slice, border 4 | n/a |
| `tooltip` | 16×16 | nine_slice, border 4 (brass top trim) | `_warning` `_danger` |
| `toast` | 32×24 | nine_slice, border 5 | `_info` `_success` `_warning` `_danger` (left accent bar) |
| `banner` | 24×20 | nine_slice, border 4 | same four severities |
| `badge` | 10×10 | nine_slice, border 3 | severity + neutral |
| `chip` | 16×12 | nine_slice, border 4 | `_selected` `_hover` |
| `progress_track` / `progress_fill` | 16×6 | nine_slice, border 2 | fill per severity |
| `modal` | 32×32 | nine_slice, border 8 | `_danger` (crimson frame) |
| `hazard` | 16×8 | **tile** | top strip of danger modals |
| `keycap` | 12×12 | nine_slice, border 3 | for key hints |
| `map_frame` | 32×32 | nine_slice, border 6 (parchment edge) | n/a |
| `parchment` | 64×64 | **tile** | map background, unexplored fog |
| `hatch_danger` / `hatch_reserved` / `hatch_blocked` | 8×8 | **tile** | map overlays |
| `divider` | 8×2 | tile | separators |
| `skeleton` | 16×8 | tile, animated shimmer | loading placeholders |

**Programmer art first**: phase 1 generates flat placeholder PNGs from the tokens (a Gradle task drawing borders and fills), so the framework works before final art exists. You replace them with hand-drawn art without touching code.

## 9. Illustrations (hand-drawn, 64×64 or 96×64)

Used on empty states, onboarding and big dialogs:

| Id | Motif | Where |
|---|---|---|
| `ill.nomansland` | empty field with a lone signpost | "You are not in a country" |
| `ill.found` | flag planted on a hill | Found-country wizard |
| `ill.treasury` | open chest with coins | empty ledger |
| `ill.citizens` | three villagers | no members yet |
| `ill.jobs` | tools on a workbench | no jobs |
| `ill.law` | scroll and quill | laws intro |
| `ill.diplomacy` | two banners crossing | no relations |
| `ill.province` | small banner under a big banner with a chain | province agreement |
| `ill.independence` | broken chain | independence request/grant |
| `ill.map.fog` | rolled-up map | unexplored map area |
| `ill.debt` | cracked coin | debt warning dialog |
| `ill.success` | fireworks over a flag | wizard completion |

## 10. Sound

| Event | Sound | Pitch |
|---|---|---|
| click | `ui.button.click` (vanilla) | 1.0 |
| tab/page switch | `item.book.page_turn` | 1.2 |
| success | `block.note_block.chime` | 1.3 |
| warning | `block.note_block.bit` | 0.8 |
| danger / denied | `block.note_block.bass` | 0.6 |
| money in / out | `entity.experience_orb.pickup` / `block.chain.place` | 1.4 / 1.0 |
| modal open | `item.book.put` | 1.0 |
| hold-to-confirm complete | `block.anvil.use` (quiet) | 1.5 |

All UI sounds go through one `UiSound` helper with a volume slider in settings.
