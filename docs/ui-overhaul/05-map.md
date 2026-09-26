# 05: Strategic Map

The map is the centre of a territorial game. It must look like the world (terrain), work like a strategy map (map modes, layers), and plan claims before they cost anything.

---

## 1. Terrain rendering (`kami.claims.client.map`)

### 1.1 Sampling: `TerrainSampler`
For each client-loaded chunk:
- Top block per column: `Heightmap.Types.WORLD_SURFACE`, skipping transparent blocks with no map colour (glass, grass plants) and going one block down.
- Colour: `BlockState.getMapColor(level, pos)` with the vanilla map brightness rule: compare the height with the block to the north: higher → `HIGH`, same → `NORMAL`, lower → `LOW`, much lower → `LOWEST`. Water uses depth to pick brightness (shallow bright, deep dark) with an ordered 2×2 dither, like vanilla maps.
- Extras: biome water tint via `BiomeColors.getAverageWaterColor`, leaves/grass via biome tint so forests and swamps read correctly.
- Nether/End: in dimensions with a ceiling (`dimensionType().hasCeiling()`), scan down from `y = 100` (configurable) for the first air→solid transition; the End uses the surface normally.
- Output per chunk: `IntArray(256)` ARGB + a `heights` `ShortArray(256)` (for contour lines and 3D shading).

### 1.2 When to sample
- `ChunkEvent.Load` on the client level → queue.
- Block changes (`ClientLevel` section updates via `LevelEvent`/a light mixin-free approach: re-sample chunks within 4 chunks of the player every 5 s, round-robin).
- Budget: at most 2 ms per frame of sampling work on the render thread, the rest spread across frames (queue).

### 1.3 Storage: `TerrainCache` + `TerrainTiles`
- **Memory**: region tiles of 32×32 chunks = 512×512 px `NativeImage` wrapped in `DynamicTexture`, registered as `kami_claims:map/<dim>/<rx>_<rz>`. Dirty rectangles upload with `NativeImage.upload` sub-regions.
- **Mip levels**: 1 px/block (zoom in), 1 px per 4 blocks, 1 px per chunk (zoom out). Lower mips are generated from level 0 by box-averaging when a tile changes.
- **LRU**: max 48 tiles in VRAM (~48 MB worst case at level 0; level 2 tiles are tiny). Tiles far from the view are released.
- **Disk**: `<gameDir>/kami/mapcache/<server-id>/<dim>/r.<rx>.<rz>.kmap` (gzip: header + per-chunk presence bitmap + colours + heights). `server-id` = hash of the server address (singleplayer: level name). Write-behind every 30 s and on exit. Settings: on/off, max size (default 256 MB) with oldest-first eviction, "Clear map cache".
- **Optional Xaero source** (D3): if Xaero's World Map is present, a soft-linked adapter reads its cached region colours for chunks we haven't sampled, same `require = 0` approach as the current highlighter mixin.

### 1.4 Look
- **Parchment frame** (`map_frame`) and **parchment tile** for unexplored areas, with a soft edge (fog gradient 1 chunk wide) between explored and unexplored.
- **Hill shading** at zoom ≥ 4 px/block: extra light/dark from the height gradient (NW light), subtle.
- **Contour lines** (optional layer): every 16 blocks of height, 1 px darker line.
- **Water** gets a slight blue tint layer at low zoom so coastlines read.

---

## 2. Map modes (like strategy games)

A mode decides how chunks are **filled**. Layers (§3) add information on top. Hotkeys `1–7`.

| # | Mode | Fill | Legend |
|---|---|---|---|
| 1 | **Political** (default) | country colour 35 % over terrain; own 45 %; borders in country colour | countries in view with emblem |
| 2 | **Relations** | own = `geo.own`, provinces/overlord = `geo.province`, allied = `geo.ally`, others = `geo.neutral`, banished-from = `geo.banished` | the 5 relation classes |
| 3 | **Land use** | chunk type colour + type icon at zoom ≥ 12 px/chunk | types with counts |
| 4 | **Economy** | heat scale of upkeep per chunk per day (green cheap → red expensive); plots coloured by tax | scale bar with ◎ values |
| 5 | **Risk** | debt level 0/1/2/3 (hatched, increasing red), reserved chunks, plots in lapse (amber), free chunks (blue outline) | severity scale |
| 6 | **Plots** | residential occupancy: yours (gold), free (green), taken (grey), locked (amber hatch) | 4 states |
| 7 | **Terrain** | terrain only, borders as thin lines | n/a |

Modes 3–6 only show details the viewer may see (`View` visibility rules stay authoritative server-side; foreign chunks fall back to "claimed by X").

## 3. Layers (toggle chips, remembered per client)

Borders · Grid (chunk grid, auto-hidden when zoomed far out) · Labels (country names placed at the centroid of each country's largest connected area, scaled with zoom, collision-avoiding) · Markers (capital, your plots, you, members if D5) · Selection · Coordinates (region ticks along the edges) · Contours · Fog of war.

## 4. Borders and fills

- Border between different owners: 2 px in the owner's colour on its own side + 1 px dark outline (`#0C0E12` @ 70 %) for contrast on any terrain.
- Own country: double line (outer brass 1 px).
- Provinces of yours: dashed 2 px `geo.province`.
- Capital: crown marker, emblem at zoom ≥ 16.
- Debt: animated diagonal hatch in `sem.danger` (slow scroll, disabled with reduce-motion).
- Reserved: grey hatch + lock icon on hover.
- Hover chunk: 1 px white outline + brightened fill.
- Selection: gold marching-ants border, gold 20 % fill; **preview** per chunk while planning (§6).

## 5. Navigation and tools

| Input | Effect |
|---|---|
| Left drag (Select tool) | pan if started on empty/no-tool, else select |
| Right or middle drag, `Space`+drag | always pan |
| Wheel | zoom around the cursor (smooth, 6 steps: 1, 2, 4, 8, 16, 32 px per chunk ×2 levels in between) |
| Double-click | zoom in centred on the chunk |
| `C` / [◎ Me] | center on you; [♛] center on capital |
| `WASD` / arrows | pan; `Shift` faster |
| `Esc` | clear selection |

**Tools** (toolbar, SegmentedControl):
- **Select** `Q`: click a chunk → detail in the side panel; `Ctrl`-click adds to selection.
- **Area** `E`: drag a rectangle (no Shift needed). `Shift`+drag in Select also works.
- **Brush** `B`: paint chunks with the chosen claim type (drag), for irregular shapes; right-drag erases from the plan.
- **Measure** `R`: distance in blocks/chunks between two points.
- **Pan** `H`.

Status line below the map: coordinates of the hovered chunk and block range (`x 192..207, z −64..−49`), owner, type, and the key hints for the active tool.

## 6. Claim planning (preview before cost)

1. Choose **Claim as** type (Select with icon, colour, price per day).
2. Draw with Area/Brush. The client asks the server for a **preview** (`claim_preview`, debounced 200 ms, 07-data-contracts §4).
3. Each planned chunk is drawn:
   - ✔ green outline: will be claimed; the fill shows the type colour at 50 %,
   - ✖ red hatch: can't, with the reason on hover (*owned by X*, *reserved for Y until …*, *not connected to your land*, *dimension not allowed*, *limit reached*),
   - ◐ amber: will be claimed but uses a free chunk (cost 0) / will change type.
4. Side panel totals: claimable, blocked (grouped by reason), free chunks used, **new upkeep per day**, **runway before → after**, treasury check ("You can afford 43 days of this").
5. [Claim 9 chunks ▶] = tier 2 confirm (skipped with a setting for experienced players, never skipped if runway drops below 7 days).
6. On success the claimed chunks glow once and the plan clears. On partial failure the failed chunks stay planned with their reasons.

The same planner does **Retype** (select own chunks, choose type, preview price change) and **Unclaim** (tier 3, preview upkeep saved and the reserve period).

## 7. Side panel contexts

| Context | Content |
|---|---|
| Nothing selected | country summary (chunks by type, upkeep), tool hints, "Plan new claims" CTA |
| Own chunk | chunk detail (04-claims-pages §7.1) compact variant |
| Foreign chunk | owner emblem and profile link, relation, "what you can do here" (break/place/use/open per your rank), [Request to join] |
| Nomansland | "Nobody owns this. Nothing can be built here." + claimability check + [Claim as…] |
| Area / brush plan | planning totals (§6) |
| Province view | amber header "Planning for Riverhold" |

## 8. Legend
Auto-built from the active mode and layers, collapsible, bottom-left over the map. Each entry is hoverable: hovering "In debt" highlights all debt chunks in view.

## 9. Mini maps
`MiniMap` (static, non-interactive, 5×5 to 15×15 chunks) reuses the tiles for drawers, dialogs (found wizard, unclaim confirm) and tooltips.

## 10. Performance targets
- Map frame ≤ 1.5 ms at 1080p, GUI scale 2, 40×25 chunks visible: tiles are single blits; overlays batched per colour into one `BufferBuilder` (quads) instead of hundreds of `fill` calls.
- Claim lookups via `ClientClaims.at` stay O(1); border edges are computed per tile once per claims revision (`ClientClaims.rev`) and cached as line lists.
