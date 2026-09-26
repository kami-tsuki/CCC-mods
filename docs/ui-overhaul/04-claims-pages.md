# 04: KamiClaims Pages

Wireframes are schematic: characters stand for sprites and icons (⛁ treasury, ◎ coin, ▲▼ trends, ● status, ⚠ warning, ♛ capital, ⌂ plot). Sizes assume the `wide` breakpoint; `compact` changes are noted per page.

---

## 1. Information architecture

```
KamiClaims
├── (no country) ── Welcome · Found a country (wizard) · Browse countries · Invitations
│
├── OVERVIEW
│   ├── Dashboard ........ health, alerts, next steps, KPIs, activity feed
│   └── Statistics ....... charts: treasury, income/spending, territory, population
├── TERRITORY
│   ├── Map .............. strategic map with map modes and claim planning (05-map)
│   ├── Chunks ........... table of all claims, bulk actions
│   └── Plots ............ residential plots: mine / free / all
├── ECONOMY
│   ├── Budget ........... income vs spending, upkeep by type, taxes, forecast, runway
│   └── Ledger ........... every transaction, filterable, exportable to chat
├── SOCIETY
│   ├── Citizens ......... members, requests, profiles
│   ├── Jobs ............. job definitions, workers, quotas, payroll
│   └── Ranks ............ what each rank may do (capability matrix)
├── LAW
│   ├── Protection ....... per-chunk-type access rules, fire/fluid/machines
│   └── Plot law ......... residential tax, lapse chain, plot limits
├── DIPLOMACY
│   ├── Relations ........ allies, banished, auto-allied family
│   ├── Provinces ........ overlord/province system, agreements, independence
│   └── World ............ all countries, comparison, profiles; players directory
└── SYSTEM
    ├── Help ............. glossary, guides, keyboard shortcuts
    └── Settings ......... client preferences (03-patterns §14)
```

Mapping from today's tabs: Overview → Dashboard + Budget; Map → Map; Claims → Chunks; Plots → Plots + Plot law; Members → Citizens + Relations; Jobs → Jobs; Provinces → Provinces; Players → World; Ranks → Ranks; Rules → Protection.

**Visibility**: pages a player can't use stay visible with a lock and an explanation (03-patterns §9). Read-only views are shown to citizens (they can *see* the budget if `details` allows, can *see* laws), with edit controls hidden behind "Only Chancellor and up can change this".

**Province view** (managing a province as its overlord): a persistent amber banner under the top bar, "Managing *Riverhold* as overlord · limited rights · [Exit]". The sidebar greys out pages outside the delegated capabilities with the reason "Not delegated to overlords".

---

## 2. App shell

```
╔══════════════════════════════════════════════════════════════════════════════════════╗
║ [⚑] KINGDOM OF ASH  ♛ President │ ⛁ 12,480 ◎ ▲ │ ± +84/d │ ⌚ Runway ∞ │ ▦ 46 │ 👥 9 │ 🔔3 ? ⚙ ✕ ║
╟───────────────┬──────────────────────────────────────────────────────────────────────╢
║ OVERVIEW      │ Overview › Dashboard                                                 ║
║  ▣ Dashboard 3│                                                                      ║
║  📈 Statistics │                                                                      ║
║ TERRITORY     │                                                                      ║
║  🗺 Map        │                                                                      ║
║  ▦ Chunks  ⚠  │                          page content                                ║
║  ⌂ Plots      │                                                                      ║
║ ECONOMY       │                                                                      ║
║  ⚖ Budget     │                                                                      ║
║  📒 Ledger     │                                                                      ║
║ SOCIETY       │                                                                      ║
║  👥 Citizens 2 │                                                                      ║
║  ⚒ Jobs       │                                                                      ║
║  ♛ Ranks      │                                                                      ║
║ LAW           │                                                                      ║
║  🛡 Protection │                                                                      ║
║  📜 Plot law   │                                                                      ║
║ DIPLOMACY     │                                                                      ║
║  🤝 Relations  │                                                                      ║
║  ⛓ Provinces 1│                                                                      ║
║  🌍 World      │                                                                      ║
╚═══════════════╧══════════════════════════════════════════════════════════════════════╝
```
- **Top bar KPIs** (each a chip with tooltip breakdown and link): Treasury (trend vs 7 d), Net per day (green/red), Runway (∞ / days, colour by threshold: > 30 d green, 7–30 amber, < 7 red), Chunks (claimed, tooltip: free/paid/in debt), Citizens (online/total), Alerts bell.
- Sidebar badges: counts of pending items (requests, offers), a ⚠ for pages with warnings (Chunks has chunks in debt).

---

## 3. No country: Welcome

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                       [ill.nomansland]                                       │
│                 YOU ARE IN NOMANSLAND                                        │
│   Countries own land, collect taxes and protect their builds.                │
│   Nothing can be built out here. Start your own country or join one.         │
│                                                                              │
│  ┌──────────────────────────────┐      ┌──────────────────────────────┐      │
│  │ [ill.found]                  │      │ [ill.citizens]               │      │
│  │ FOUND A COUNTRY              │      │ JOIN A COUNTRY               │      │
│  │ Free: 9 chunks incl. capital │      │ 14 countries · 3 near you    │      │
│  │ Upkeep only beyond those 9   │      │ Share land and upkeep        │      │
│  │        [ Start ▶ ]           │      │        [ Browse ▶ ]          │      │
│  └──────────────────────────────┘      └──────────────────────────────┘      │
│                                                                              │
│  ✉ INVITATIONS (2)                                                           │
│  [⚑] Kingdom of Ash · 9 citizens · 46 chunks · 1.2k from you   [View] [Join]│
│  [⚑] Riverhold      · 3 citizens · 12 chunks · 300 from you    [View] [Join]│
│                                                                              │
│  ⓘ How do countries work?  [Open guide]                                     │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Found-a-country wizard (4 steps)
1. **Location**: mini map around you with terrain; your chunk marked as the future capital, the 3×3 free area preview (free chunks are not auto-claimed, it's just an illustration of "9 free"). Warnings if the chunk is next to another country or not claimable (dimension, reserved), with the reason.
2. **Identity**: name (live availability ✔/✖, length, allowed characters), colour picker, emblem picker (D7), preview banner and preview on the map.
3. **How it works**: 4 short cards: *Land & upkeep* (9 free, then per type per day), *Treasury* (deposit, runway), *Citizens & ranks*, *Protection*. Checkbox "Show me a guided tour after founding".
4. **Confirm**: summary + [Found Kingdom of Ash]. Success screen: fireworks illustration, "Next steps" checklist, [Open the map].

### 3.2 Browse countries
DataTable: emblem, name, citizens, chunks, distance from you, open to requests (y/n), your relation. Row click → **Country profile drawer**: emblem, president, founded date, size, member avatars, provinces/overlord, public description (future), [Request to join] with a confirm that says what happens ("An officer must approve. You can only be in one country.").

---

## 4. Dashboard

```
┌ Overview › Dashboard ──────────────────────────────────────────────────── [?] ┐
│ ┌─ ⛁ Treasury ─────┐┌─ ± Net / day ────┐┌─ ⌚ Runway ──────┐┌─ ▦ Territory ───┐│
│ │ 12,480 ◎          ││ +84 ◎            ││ ∞  stable       ││ 46 chunks       ││
│ │ ▲ +6% vs 7d  ~~~/ ││ in 212 · out 128 ││ next bill 3h 12m││ 9 free · 1 debt ││
│ └───────────────────┘└──────────────────┘└─────────────────┘└─────────────────┘│
│ ┌─ ⚠ NEEDS ATTENTION (3) ──────────────────────────┐┌─ ✓ NEXT STEPS ────────┐ │
│ │ ✖ Chunk 13,-4 is in debt (2/3). It will be       ││ ✔ Found your country  │ │
│ │   unclaimed in 1 day.     [Deposit 40 ◎] [Show]  ││ ✔ Claim first chunks  │ │
│ │ ⚠ Riverhold asks for independence.               ││ ☐ Set residential tax │ │
│ │                          [Review request]        ││   [Go to Plot law]    │ │
│ │ ⓘ 2 people want to join.        [Review]         ││ ☐ Create a job        │ │
│ └──────────────────────────────────────────────────┘│ ☐ Invite a citizen    │ │
│ ┌─ 📈 Treasury, 30 days ───────────────────────────┐└───────────────────────┘ │
│ │  line chart: balance, dashed forecast 14 days    │┌─ 🕑 ACTIVITY ─────────┐ │
│ │  bars below: daily in (green) / out (red)        ││ 3h Upkeep −128 ◎      │ │
│ └──────────────────────────────────────────────────┘│ 5h Steve joined       │ │
│ ┌─ ▦ Territory by type ───┐┌─ 👥 Citizens ─────────┐│ 1d Claimed 4 chunks   │ │
│ │ ⛏ Mining   12 ████▌  36◎││ 9 total · 3 online    ││ 1d Tax set to 5 ◎     │ │
│ │ ⌂ Resid.   18 ██████ 90◎││ ♛1 📜1 🛡2 👤5         ││ [Open ledger ▶]       │ │
│ │ ...                     ││ Top workers ...       │└───────────────────────┘ │
│ └─────────────────────────┘└───────────────────────┘                          │
└───────────────────────────────────────────────────────────────────────────────┘
```
- **Needs attention** is the heart of "don't guess": server alerts, sorted by severity, each with a direct fix button.
- **Next steps** checklist for new countries (server-computed completion), hidden after all done or dismissed.
- **Activity** feed: last 20 ledger/membership events with links.
- Citizens see a reduced dashboard (no budget details without `details`), plus their own card: "Your plot: tax 5 ◎/d, paid until…", "Your job: Miner 340/500 this period, pay 20 ◎".

## 5. Statistics
SubTabs: **Treasury** · **Income & spending** · **Territory** · **Population** · **Jobs**.
Each: range selector (7 d / 30 d / 90 d), chart with hover crosshair, table of the same data below, and 3 insight lines computed client-side ("Upkeep grew 22 % in 30 days, mostly Mining (+8 chunks)").

---

## 6. Map (full spec in 05-map.md)

```
┌ Territory › Map ────────────────────────────────────────────────────────────────────┐
│ [▣Select][⬚Area][✥Pan][📏Measure]  Mode:[Political▾]  Layers:[Types][Plots][Grid]… ⌕ │
│ ┌────────────────────────────────────────────────────────────┐┌─ SELECTION ───────┐ │
│ │                                                            ││ 12 chunks          │ │
│ │           terrain map + claim overlay                      ││ ✔ 9 claimable      │ │
│ │                                                            ││ ✖ 2 other country  │ │
│ │                                                            ││ ✖ 1 not connected  │ │
│ │                                                            ││                    │ │
│ │                                                  N         ││ CLAIM AS           │ │
│ │                                                 W+E        ││ [⛏ Mining      ▾] │ │
│ │  ▭▭ 5 chunks                                     S         ││ Cost now    0 ◎    │ │
│ └────────────────────────────────────────────────────────────┘│ Upkeep  +27 ◎/day  │ │
│  12, -4 · Kingdom of Ash · ⛏ Mining   zoom ━━●━━  [◎ Me] [♛]  ││ Runway ∞ → 190d    │ │
│  Legend: ■ yours ■ province ■ ally ■ other ▨ debt ▨ reserved  ││ [Claim 9 chunks ▶] │ │
│                                                               │└────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────┘
```
Right panel is contextual: nothing selected → "How to" hints + country summary; one chunk → chunk detail (§7.1); area → planning summary (above); a foreign chunk → owner profile + "what you can do here".

## 7. Chunks

DataTable (02-components §6.1): chunk, dimension, type, capital, upkeep/day, status (OK / debt n/3 / free chunk), plot owner, plot tax, claimed date.
- Filters: type, status, dimension, has plot. Search coordinates or owner.
- Bulk: Set type (Select with price difference preview), Unclaim (tier 3 confirm, lists chunks + upkeep saved + "reserved for you for 5 days"), Show on map (selects them on the map).
- Row severity bar for debt chunks. Header summary: "46 chunks · 9 free · 37 paid · 1 in debt · upkeep 128 ◎/day".

### 7.1 Chunk detail drawer
```
┌ CHUNK 13, -4 ─────────────────────────────── ✕ ┐
│ [mini map 5×5 around it]                       │
│ ⌂ Residential                ● In debt 2/3     │
│ ────────────────────────────────────────────── │
│ OWNER COUNTRY ......... [⚑] Kingdom of Ash     │
│ UPKEEP ................ 5 ◎ / day              │
│ CLAIMED ............... 12 days ago            │
│ PLOT OWNER ............ [☺] Steve              │
│ PLOT TAX .............. 5 ◎ / day, paid        │
│ ────────────────────────────────────────────── │
│ DEBT TIMELINE                                  │
│ ●━━━━━━━●━━━━━━━○━━━━━━━○                      │
│ billed  debt 1  debt 2  unclaimed → reserved 5d│
│  today   ✔       ✔      in 1d                  │
│ ────────────────────────────────────────────── │
│ WHO CAN DO WHAT HERE                           │
│ ⛏ Break  Citizens   ▣ Place Citizens           │
│ ✋ Use    Allies     ▤ Open  Citizens           │
│ ────────────────────────────────────────────── │
│ [Show on map] [Change type ▾] [Unclaim…]       │
└────────────────────────────────────────────────┘
```

## 8. Plots

SubTabs: **My plots** · **Available** · **All plots** (details cap).

- *My plots*: cards per plot: location + mini map, tax, **lapse timeline** (paid → locked after N d → lost after M d), trusted players list with role chips, [Add player] (player picker + role radio with descriptions: *Household* builds and opens everything · *Allied* may use doors/buttons (`plotAllied` actions) · *Banished* locked out), [Release plot] (tier 3).
- *Available*: table of free residential plots (location, tax, distance from you), [Claim plot] (tier 2: "You pay 5 ◎ per day from your bank. If you can't pay: locked after 3 d, lost after 10 d.").
- *All plots*: owners, tax per plot (NumberField, staged), lapse state, [Evict] (tier 3).
- Header KPI: "You own 2 / 3 plots".

---

## 9. Budget

```
┌ Economy › Budget ─────────────────────────────────────────────────────────────┐
│ [ Income 212 ◎/d ] [ Spending 128 ◎/d ] [ Net +84 ◎/d ] [ Runway ∞ ]          │
│ ┌─ INCOME ─────────────────────────┐ ┌─ SPENDING ──────────────────────────┐  │
│ │ ⌂ Plot taxes     18 plots  90 ◎  │ │ ▦ Upkeep       37 chunks   98 ◎    │  │
│ │ ⛓ Tribute from provinces  122 ◎  │ │   ⛏ Mining  12 × 3    36           │  │
│ │   Riverhold 15%   42             │ │   ⌂ Resid.  10 × 5    50           │  │
│ │   Stonewatch flat 80             │ │   …                                │  │
│ │                                  │ │ ⚒ Job pay      3 jobs      30 ◎    │  │
│ │                                  │ │   (cap 60% of income = 127 ◎)      │  │
│ │                                  │ │ ⛓ Tribute to overlord       0 ◎    │  │
│ └──────────────────────────────────┘ └─────────────────────────────────────┘  │
│ ┌─ FORECAST, 30 days ───────────────────────────────────────────────────────┐ │
│ │ dashed balance projection, red zone below 0, marker "next billing"        │ │
│ │ "At this rate your treasury grows by 2,520 ◎ in 30 days."                 │ │
│ └───────────────────────────────────────────────────────────────────────────┘ │
│ ┌─ TREASURY ────────────────────────────────────────────────────────────────┐ │
│ │ Balance 12,480 ◎     You carry 1,240 ◎                                    │ │
│ │ [ Amount ◎ ___ ] [10][100][Max]   [Deposit ▶]  [Withdraw…] (Chancellor)   │ │
│ └───────────────────────────────────────────────────────────────────────────┘ │
│ ┌─ WHAT-IF ─────────────────────────────────────────────────────────────────┐ │
│ │ Plot tax [ 5 ◎ ]  → income +18 ◎/d · runway ∞       [Apply in Plot law]  │ │
│ │ Unclaim 4 Mining  → spending −12 ◎/d                                     │ │
│ └───────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────┘
```
- Each line item links to its source page. Tooltips explain how each value is computed (the formulas from `Upkeep`).
- **What-if** sandbox: adjust tax/unclaim/job pay locally and see the projected effect before applying (client-side projection from the same numbers the server sends).
- Withdraw is tier 2 and logged in the ledger with the actor.

## 10. Ledger
DataTable: time, type (upkeep, tax, tribute in/out, job pay, deposit, withdraw, claim, debt, forgiveness), amount (signed, coloured), balance after, actor/counterparty, related chunk/player (links). Filters: type, range, actor. Summary row per filter. [Post summary to chat] prints a compact text table for sharing.

---

## 11. Citizens

Split: table left, profile drawer right.
- Table: avatar, name, rank chip, job + quota bar, plots, last seen (● online), joined.
- SubTabs: **Members** · **Join requests (2)** · **Invitations sent**.
- Requests: avatar, name, their current country, requested when, [Approve] (tier 2) [Deny].
- [Invite player] → player picker modal with "They get a message with [Accept]".

### 11.1 Member profile drawer
```
┌ [☺] STEVE ──────────────────────────────── ✕ ┐
│ 🛡 Officer · joined 34d ago · ● online        │
│ JOB ......... ⛏ Miner   340 / 500 ▓▓▓▓▓░░    │
│ PLOTS ....... 13,-4  ·  14,-4                 │
│ CAN DO ...... claim ✖ tax ✖ invite ✔ jobs ✔  │
│ ───────────────────────────────────────────── │
│ RANK   [Citizen][Officer●][Chancellor]        │  ← segmented, shows allowed targets only
│        Promoting to Chancellor lets Steve     │
│        change laws, taxes and claim land.     │
│ JOB    [⛏ Miner ▾]                            │
│ ───────────────────────────────────────────── │
│ ── Danger zone ──                              │
│ [Kick…] [Banish…] [Make president…]           │
└───────────────────────────────────────────────┘
```
The rank control shows what the new rank *gains or loses* (diff from the capability matrix) before confirming.

## 12. Jobs
Cards per job: type icon + name, **pay/day**, **quota**, **period** (NumberFields, staged), counted actions and blocks (read-only chips from config), assigned zone chunks (map link), workers with progress bars and "paid last period ✔/✖ (quota missed / treasury short / cap reached)". Footer: "Payroll 30 ◎/d · cap 127 ◎/d (60 % of income)" meter. Empty state illustrates jobs.

## 13. Ranks
Capability matrix: rows = capabilities (icon + name + one-line explanation), columns = ranks (icon + colour + member count), cells ✔ / —. Your rank's column highlighted. Hover a row: examples of what it allows. Lock note: "Minimum ranks come from the server config" (and later per-country overrides).

---

## 14. Protection (laws)

```
┌ Law › Protection ─────────────────────────────────────────────────────────────┐
│ Who may do what in each type of land. Changes apply after [Apply].            │
│ Presets: [Private ▾]                                   Legend: ⛔ 🛡 ⚒ 👥 🤝 🌍 │
│ ┌─────────────┬──────────┬──────────┬──────────┬──────────┬────┬────┬─────┐  │
│ │ TYPE        │ ⛏ BREAK  │ ▣ PLACE  │ ✋ USE    │ ▤ OPEN   │ ⚙M │ 🔥 │ 💧  │  │
│ ├─────────────┼──────────┼──────────┼──────────┼──────────┼────┼────┼─────┤  │
│ │ ⛏ Mining    │ ⚒ Job    │ ⚒ Job    │ 👥 Citiz. │ 👥 Citiz. │ ●  │ ○  │ ○   │  │
│ │ ⌂ Resid.    │ ⛔ Plot   │ ⛔ Plot   │ 🤝 Allies │ ⛔ Plot   │ ○  │ ○  │ ○   │  │
│ │ ◈ Market    │ 🛡 Offic. │ 🛡 Offic. │ 🌍 All    │ 🌍 All    │ ○  │ ○  │ ○   │  │
│ └─────────────┴──────────┴──────────┴──────────┴──────────┴────┴────┴─────┘  │
│ Selected: Mining › Break = Assigned job                                       │
│ "Only members whose job works in Mining land may break blocks here.           │
│  Example: a Miner can mine, a Farmer cannot."                                 │
└───────────────────────────────────────────────────────────────────────────────┘
```
- Cells are chips (icon + short label, colour by openness from red "Nobody" to green "Everyone"). Click → Select popover with all `Access` levels and descriptions.
- Toggles for machines/fire/fluid with explanations ("Fire may spread into Mining land").
- Staged editing + apply bar. The explanation panel below updates with the selected cell (plain-language sentence + example).
- Presets per row: Private, Workers only, Public market, Open.
- Nomansland/wilderness rules shown read-only with a server-config lock.

## 15. Plot law
Residential tax (NumberField, what-if income), **lapse chain editor** as a Timeline: "Tax unpaid → **locked** after [3] days → **released** after [7] more days", with a plain sentence and a warning if values are extreme. Plot limit per player (read-only from config). Staged.

---

## 16. Relations
Three lists: **Allies** (players, with country), **Banished**, **Family** (auto-allied via provinces, read-only, "via Riverhold"). Add ally / banish via player picker with explanations of what each grants/blocks. Remove is tier 2.

## 17. Provinces

This page changes with your position in the family.

### 17.1 You are independent (no provinces)
Explainer card with `ill.province`: "A province is a country that pays tribute to an overlord. The overlord can manage its land, laws and jobs. Provinces can't leave on their own." SubTabs: **Offers received** · **Request to join a country** · **Invite a country**.

### 17.2 You are an overlord
```
┌ Diplomacy › Provinces ────────────────────────────────────────────────────────┐
│ [ 2 provinces ] [ Tribute +122 ◎/d ] [ Tribute debt 1 ] [ 1 independence req ]│
│ ┌─ FAMILY ────────────────────────────────────────────────────────────────┐   │
│ │                    [⚑] KINGDOM OF ASH                                   │   │
│ │                   ╱                   ╲                                 │   │
│ │   [⚑] Riverhold  15%  ● ok     [⚑] Stonewatch  80◎  ● debt 1            │   │
│ │   ⚠ wants independence                                                   │   │
│ └─────────────────────────────────────────────────────────────────────────┘   │
│ Selected: Riverhold                                                           │
│ Tribute 15% of plot tax (≈ 42 ◎/d) · Debt 0/5 · Members 3 · Chunks 12          │
│ ⚠ Riverhold asks for independence (2 days ago).                               │
│   [Grant independence…]  [Decline…]                                           │
│ [Manage province ▶] [Change tribute…] [Forgive debt…] [Transfer…] [Release…]  │
└───────────────────────────────────────────────────────────────────────────────┘
```
- **Manage province** enters province view (banner, delegated pages only).
- Change tribute: Form modal with SegmentedControl Percent/Flat, Slider (bounded by config), live estimate from the province's income, and "The province is notified".
- Transfer: Picker modal (countries that can accept) + tier 4 confirm.
- Release / Grant independence: tier 3, consequences listed (tribute stops, auto-alliance ends, their citizens lose ally access to your land).

### 17.3 You are a province
Status card (always at top, amber):
```
┌─ ⛓ YOU ARE A PROVINCE OF [⚑] KINGDOM OF ASH ─────────────────────────────────┐
│ Tribute 15% of your plot tax  ≈ 42 ◎/day   ·  Tribute debt 0 / 5              │
│                                                                              │
│ Kingdom of Ash can:                      You keep:                           │
│  ✎ claim, unclaim and retype your land    ⛁ your treasury (no withdrawals)   │
│  ♛ move your capital                      👥 your citizens and ranks          │
│  % set your plot tax and lapse            ✉ invitations and requests          │
│  🛡 change your protection laws, colour    ⚑ your name                        │
│  ⚒ create and change your jobs                                               │
│                                                                              │
│ Only Kingdom of Ash can give you independence.                               │
│ [Request independence…]           status: ● not requested                   │
└──────────────────────────────────────────────────────────────────────────────┘
```
After requesting: status `● requested 2d ago, waiting for Kingdom of Ash` with a timeline, and a [Withdraw request] option. If declined: `✖ declined`, cooldown shown.

### 17.4 Province agreement wizard (becoming a province)

Opened by *Accept offer* or *Send request*. Tier 4. Three steps:

**Step 1 · Terms**
```
┌──────────────────────────────────────────────────────────────────────────┐
│ ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒ │
│ [ill.province]  BECOME A PROVINCE OF [⚑] KINGDOM OF ASH        ① ② ③    │
│ Riverhold will pay tribute and give up authority over its land.          │
├──────────────────────────────────────────────────────────────────────────┤
│ TRIBUTE ............ 15 % of your daily plot tax income                  │
│ TODAY THAT IS ...... ≈ 42 ◎ per day  (your income 280 ◎/day)             │
│ IF YOU CAN'T PAY ... tribute debt grows; Kingdom of Ash decides what      │
│                      happens (forgive or release you)                    │
│ OFFER EXPIRES ...... in 2d 4h                                            │
└──────────────────────────────────────────────────────────────── [Next ▶]┘
```
**Step 2 · Loss of authority** (the warning you asked for; red highlights)
```
│ ⚠ YOU ARE GIVING UP AUTHORITY                                            │
│ ┌─ Kingdom of Ash gains control over ──┐ ┌─ Riverhold keeps ───────────┐ │
│ │ ✎ Your land: claim, unclaim, retype  │ │ ⛁ Treasury (no withdrawals) │ │
│ │ ♛ Your capital                        │ │ 👥 Citizens, ranks, invites │ │
│ │ % Your plot tax and lapse rules       │ │ ⚑ Name                      │ │
│ │ 🛡 Your protection laws and colour     │ └─────────────────────────────┘ │
│ │ ⚒ Your jobs                           │                                 │
│ └───────────────────────────────────────┘                                 │
│ ⛓ YOU CANNOT LEAVE ON YOUR OWN                                           │
│   Only Kingdom of Ash can release you or grant independence.             │
│   You may ask for it; they may say no.                                   │
│ ⓘ Your citizens become allies of Kingdom of Ash and its other provinces. │
│ ⓘ Your 2 provinces will become direct provinces of Kingdom of Ash.       │  ← only if relevant
│ ☐ I understand that Riverhold loses authority and can't leave by itself │
```
**Step 3 · Sign**: type "Riverhold" + [Hold to sign ███░░]. Success: province status card, mail to all officers of both countries with the same summary.

The "gains control over" list is rendered from `delegableCaps` sent by the server (07-data-contracts §5), so it always matches what the server allows.

### 17.5 Overlord side of agreements
Invite a country / approve a request: Form wizard (pick country, set tribute with Slider + estimate from their income) → summary: "You'll be able to manage their land, laws and jobs, but not their treasury or members. Their citizens become your allies." → Confirm (tier 2). With D2, approving a request sends an **offer** the requester must sign via §17.4.

## 18. World
SubTabs: **Countries** (table: emblem, name, citizens, chunks, provinces, founded, relation to you; profile drawer; [Show on map]) · **Players** (today's Players tab: avatar, name, home country, citizenships incl. "via" entries, profile drawer).

## 19. Help
- **Guide**: short illustrated chapters (Countries, Land & upkeep, Treasury & runway, Plots, Jobs, Laws, Provinces, Protection & borders). Each links to the relevant page.
- **Glossary**: every term with a dotted underline elsewhere.
- **Shortcuts**: keyboard table.
- [Restart tour].

## 20. Settings
Client prefs from 03-patterns §14 plus map (default mode, terrain cache on/off, cache size), borders (mode, style particles/lines, density, colours), HUD (territory pill, border distance, event toasts).
