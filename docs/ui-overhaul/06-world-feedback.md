# 06: In-World Feedback

The screen is only half the UX. Players spend most of their time *in the world*, and that's where the "tree slightly outside my claim" frustration happens. This part makes borders, ownership and permissions visible where you build.

---

## 1. Border visualisation (`kami.claims.client.world.BorderFx`)

### 1.1 Why the current system fails
`Effects.borders` (server) sends `END_ROD`/`SMOKE` particles every 10 ticks at `player.y + 1.2`, 1 particle every 2 blocks, only for 3×3 chunks, only after `/claims border`. On slopes the line is buried or floating, trees crossing the edge show nothing, and nobody knows the command exists.

### 1.2 New: client-side, terrain-aware, automatic
Runs on the client using `ClientClaims` (already synced), so there's no network cost and no delay.

**Modes** (setting + keybind `B` cycles, with a small HUD toast showing the new mode):
| Mode | Behaviour |
|---|---|
| Off | nothing (denial pulses §3 still show) |
| **Auto** (default, D4) | shows edges within 12 blocks of you when a trigger is active (§1.3), fades in/out over 0.4 s |
| Always | always shows edges within 24 blocks |
| Builder | Always + line style + block grid on your own border chunks |

### 1.3 Auto triggers
- You are within **6 blocks** of an ownership edge (claim ↔ other claim ↔ nomansland).
- You hold a **tool, block or bucket** and your crosshair targets a block in a *different* owner area than the one you stand in.
- You're sneaking and looking down (builders' "peek" gesture), configurable.
- An action was just denied (§3).
- For 10 s after you claim/unclaim/retype chunks (the new border shows itself).

### 1.4 Particle curtain
For each edge segment in range, per block column along the edge:
- Find the **ground**: highest motion-blocking block at that column (`Heightmap.Types.MOTION_BLOCKING` from the client chunk). Leaves count, so the curtain goes up through tree canopies.
- Spawn from `ground + 0.1` up to `max(ground, eyeY) + 2.5`, one particle per block of height, jittered ±0.1 so it shimmers instead of forming a solid wall.
- **Density** by distance from you: every 0.5 blocks within 4 blocks, every block up to 8, every 2 blocks beyond. Total budget **300 particles/second** (setting), furthest segments dropped first.
- **Lifetime** 20 ticks, re-spawned each 10 ticks → continuous without flicker.
- **Colour** (custom `DustParticleOptions`, size 0.6) describes the *other side* of the edge as you look across it:
  - your own land on the other side: soft `geo.own` (you may build),
  - an ally/province: `geo.ally` / `geo.province`,
  - another country: that country's colour, with a red core particle every 3rd step when you *can't* build there,
  - nomansland: warm grey dust with occasional `ASH` particles.
- **Corners**: a vertical column of brighter particles at chunk corners that belong to an edge, so shapes read at a distance.
- Also visible under water (uses `BUBBLE` variant below water level) and in caves (curtain spans ±4 blocks around your eye height if you're underground, detected by sky light 0).

### 1.5 Line style (Builder mode / setting)
`RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS`: translucent vertical quads along the edges from ground to ground+4 with a 1-block grid texture, colour as above at 25 % alpha, plus a solid 2 px line at ground level. Depth-tested, so walls hide behind terrain. Like vanilla F3+G, but only on ownership edges and coloured.

### 1.6 Server fallback
`/claims border` stays for players without the client mod, improved: particles at the ground level of each column (server heightmap) and at eye level, density 1 per block within 8 blocks. The server skips it for players who have the client mod (known from the payload channel).

---

## 2. Block targeting hints (`BlockHint`)

When your crosshair targets a block:
- **Can't break/place here** (predicted client-side from ownership + the rules the client knows; foreign rules are unknown, so "foreign = assume no"): the block outline is drawn **red** instead of black (`RenderHighlightEvent.Block` replaced with our own outline), and a small lock glyph appears next to the crosshair.
- **Across your border** (target block in another area than your feet): the outline is **amber** and the crosshair shows the other owner's colour dot, even if allowed. This is the tree case: you see *before* swinging that this log belongs to someone else.
- Holding `Alt` (configurable) shows a small tooltip next to the crosshair: owner, chunk type, "Break ✖ Place ✖ Use ✔ Open ✔".
- The prediction is advisory; the server stays authoritative. A mismatch (predicted allowed, denied by server) is corrected by §3 feedback.

---

## 3. Denial feedback (server `Guard` → client)

### 3.1 Structured denial
`Guard.check` today sends "You can't do that here". New: a `Denied` payload to modded clients (text fallback to vanilla clients):

```kotlin
Denied(action: Action, x: Int, y: Int, z: Int, owner: String?, ownerColor: Int, chunkType: String?,
       reason: Reason, needed: String?, borderDistance: Int?)
enum class Reason { NOMANSLAND, FOREIGN, RANK_TOO_LOW, JOB_REQUIRED, PLOT_PRIVATE, PLOT_LOCKED, BANISHED }
```
- `borderDistance` = blocks from the target to the nearest edge of the area the player stands in (only when the target is in a different area).

### 3.2 What the player sees
- **Action bar** (modded): `✖ Nomansland · 1 block past your border · [B] show border`.
  Others: `✖ Kingdom of Ash · Mining land · only Miners may break here`, `✖ Steve's plot · ask Steve for Household`, `✖ Plot locked: tax 3 days overdue`.
- **Pulse**: the edge nearest the target block flashes red particles for 3 s (even in Off mode), and the denied block gets a red outline for 1 s.
- **Sound**: soft `note_block.bass`, rate-limited.
- **Rate limit**: the same reason for the same area at most once per 2 s; while breaking a tree you get one message, not twenty.
- **Chat hint** (first 3 times per player, then never): "Tip: press B to always see borders, or open the map with K." with clickable links.

### 3.3 Vanilla-client fallback
Action bar text with the same content (no glyphs), plus the improved server border particles for 3 s around the denied block.

---

## 4. HUD (`kami.claims.client.hud`)

### 4.1 Territory pill (top centre, sprite-based)
```
 ┌──────────────────────────────────────┐
 │ [⚑] Kingdom of Ash · ⛏ Mining  ◂ 3  │
 └──────────────────────────────────────┘
```
- Emblem, owner, chunk type icon, plot owner if any.
- **Border distance** `◂ 3` with an arrow pointing to the nearest edge (8 directions) when within 8 blocks; amber at ≤ 2.
- Nomansland: `Nomansland · no building` in muted text.
- Settings: position (top centre / top left / under the minimap), scale, auto-hide after 5 s unless near a border.

### 4.2 Entering territory
Replaces the vanilla title with a styled **banner toast** (emblem, name, relation, "Visitors may: use doors, open markets") that slides down under the pill for 3 s. Server titles stay for vanilla clients.

### 4.3 Event toasts in the world
Danger/warning alerts (07-data-contracts §3) as small toasts at the top right: "⚠ Upkeep failed: 3 chunks in debt [K]". Info events off by default. Never during combat (hurt in the last 5 s).

---

## 5. Claim-mode in the world (optional, phase 6)
A keybind toggles **claim mode**: the chunk you look at is highlighted on the ground (line style), and `Shift`-right-click with an empty hand opens a quick radial menu: *Claim as ▸ type*, *Info*, *Open on map*. Uses the same preview/confirm rules as the map planner.
