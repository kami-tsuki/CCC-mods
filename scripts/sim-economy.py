import argparse, base64, gzip, hashlib, io, json, math, random, re, struct, time, uuid
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

SERVER = Path(__file__).resolve().parents[1] / "Test-Server-NeoForge-1.21.1"
WORLD = SERVER / "world"
DAY, HOUR, MIN = 86_400_000, 3_600_000, 60_000
DAYS = 7
SEED = 20260927


def cfg(path):
    return json.loads(re.sub(r"^\s*//.*$", "", (SERVER / "config/kami" / path).read_text(encoding="utf-8"), flags=re.M))


class Nbt:
    FMT = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}

    def __init__(self, data=b""):
        self.buf, self.pos = data, 0

    def take(self, n):
        self.pos += n
        return self.buf[self.pos - n:self.pos]

    def num(self, t):
        f = self.FMT[t]
        return struct.unpack(f, self.take(struct.calcsize(f)))[0]

    def str(self):
        return self.take(self.num(2) & 0xFFFF).decode()

    def payload(self, t):
        if t in self.FMT:
            return self.num(t)
        if t == 8:
            return self.str()
        if t in (7, 11, 12):
            n, f = self.num(3), {7: ">b", 11: ">i", 12: ">q"}[t]
            return list(struct.unpack(f[0] + f[1] * n, self.take(struct.calcsize(f[1]) * n)))
        if t == 9:
            et, n = self.num(1), self.num(3)
            return et, [self.payload(et) for _ in range(n)]
        out = {}
        while (tt := self.num(1)) != 0:
            k = self.str()
            out[k] = (tt, self.payload(tt))
        return out

    @classmethod
    def load(cls, data):
        r = cls(gzip.decompress(data))
        t = r.num(1)
        r.str()
        return t, r.payload(t)

    @classmethod
    def enc(cls, t, v):
        if t in cls.FMT:
            return struct.pack(cls.FMT[t], v)
        if t == 8:
            b = v.encode()
            return struct.pack(">H", len(b)) + b
        if t in (7, 11, 12):
            f = {7: "b", 11: "i", 12: "q"}[t]
            return struct.pack(f">i{len(v)}{f}", len(v), *v)
        if t == 9:
            et, items = v
            return struct.pack(">bi", et, len(items)) + b"".join(cls.enc(et, x) for x in items)
        return b"".join(struct.pack(">b", tt) + cls.enc(8, k) + cls.enc(tt, x) for k, (tt, x) in v.items()) + b"\0"

    @classmethod
    def dump(cls, compound):
        return gzip.compress(b"\x0a" + cls.enc(8, "") + cls.enc(10, compound))


def offline_uuid(name):
    h = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
    h[6], h[8] = h[6] & 0x0F | 0x30, h[8] & 0x3F | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


def uuid_ints(u):
    return list(struct.unpack(">4i", uuid.UUID(u).bytes))


def phrase(key, *args):
    return json.dumps({"key": key, "args": [{"text": str(a), "value": True} for a in args]}, separators=(",", ":"))


COUNTRIES = [
    dict(id="aurelia", name="Aurelia", kind="trade", color=0xE0A526, flag=(2, 8, 0x1B3A6B), tax=12, at=(8, -3), size=(5, 4), job="worker",
         mix=dict(civic=2, market=6, residential=6, factory=4, infrastructure=2), players=["Quillmere", "SaffronTide", "Lyra_Vance", "coinwright"],
         provinces=[dict(id="portwell", name="Portwell", color=0xD9822B, flag=(1, 11, 0xFFFFFF), tax=10, at=(15, -3), size=(3, 4), mode="PERCENT", amount=0.2,
                         mix=dict(civic=1, market=4, residential=3, factory=2, infrastructure=2), players=["HarborHal", "tidewalker99"]),
                    dict(id="brightmarket", name="Brightmarket", color=0xF2C94C, flag=(7, 14, 0x5A3E1B), tax=10, at=(8, 3), size=(4, 3), mode="FLAT", amount=6.0,
                         mix=dict(civic=1, market=3, residential=3, factory=4, infrastructure=1), players=["Merrow_Finch", "BrassBaron"])]),
    dict(id="karvhold", name="Karvhold", kind="mining", color=0x5B6170, flag=(5, 5, 0xB22222), tax=14, at=(-14, 10), size=(5, 5), job="miner",
         mix=dict(civic=2, mining=12, residential=6, factory=3, infrastructure=2), players=["Grimthane", "IronDuchess", "Borak_Stone", "deepdelver"],
         provinces=[dict(id="ironreach", name="Ironreach", color=0x8A8F99, flag=(9, 3, 0xB22222), tax=10, at=(-20, 10), size=(3, 4), mode="PERCENT", amount=0.25,
                         mix=dict(civic=1, mining=8, residential=3), players=["Anvilheart", "ShaleRunner"]),
                    dict(id="deepvale", name="Deepvale", color=0x3D4250, flag=(4, 10, 0xE8B04B), tax=10, at=(-14, 17), size=(4, 3), mode="FLAT", amount=5.0,
                         mix=dict(civic=1, mining=7, residential=3, forestry=1), players=["Cindervein", "moth_miner"])]),
    dict(id="verdania", name="Verdania", kind="agrarian", color=0x3F9B4B, flag=(3, 6, 0xF2E394), tax=10, at=(-4, -22), size=(5, 5), job="farmer",
         mix=dict(civic=2, farming=12, forestry=4, residential=6, market=1), players=["Meadowlark", "Barley_Bree", "OakenJoss", "sunhollow"],
         provinces=[dict(id="wheatmere", name="Wheatmere", color=0xC9A227, flag=(8, 6, 0x3F9B4B), tax=8, at=(3, -22), size=(4, 3), mode="PERCENT", amount=0.15,
                         mix=dict(civic=1, farming=8, residential=3), players=["HaystackHenna", "TillerTom"]),
                    dict(id="oakhollow", name="Oakhollow", color=0x2E6B34, flag=(11, 4, 0xA0522D), tax=8, at=(-4, -28), size=(4, 3), mode="FLAT", amount=4.0,
                         mix=dict(civic=1, forestry=8, residential=3), players=["Fernsby", "Birchwhistle"])]),
]
RANKS = ["PRESIDENT", "CHANCELLOR", "OFFICER", "CITIZEN"]
LOGS = ["minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:dark_oak_log"]
CROPS = ["minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:bread", "minecraft:baked_potato", "minecraft:apple", "minecraft:pumpkin", "minecraft:sugar_cane", "minecraft:beetroot"]
GOODS = {
    "mining": {"minecraft:iron_ingot": (4, .04), "minecraft:gold_ingot": (6, -.02), "minecraft:copper_ingot": (2, .0), "minecraft:coal": (1, .0), "minecraft:diamond": (45, .03),
               "minecraft:redstone": (1, .0), "minecraft:lapis_lazuli": (2, -.03), "minecraft:raw_iron": (3, .03), "minecraft:emerald": (20, .01), "create:zinc_ingot": (3, .05)},
    "trade": {"create:andesite_alloy": (3, .02), "create:brass_ingot": (8, .04), "create:cogwheel": (5, .01), "minecraft:rail": (2, -.01), "minecraft:glass": (1, .0),
              "minecraft:bookshelf": (12, .02), "minecraft:lantern": (6, -.02), "minecraft:paper": (1, .0), "minecraft:shield": (15, -.01)},
    "agrarian": {"minecraft:hay_block": (2, .0), "minecraft:white_wool": (2, .02), "minecraft:leather": (3, .03), "minecraft:egg": (1, .0), "minecraft:honey_bottle": (4, .02)},
}
AUCTIONS = [("minecraft:elytra", 1, "Elytra", 900), ("minecraft:netherite_pickaxe", 1, "Netherite Pickaxe", 700), ("minecraft:totem_of_undying", 1, "Totem of Undying", 250),
            ("minecraft:enchanted_golden_apple", 2, "Enchanted Golden Apple", 400), ("minecraft:beacon", 1, "Beacon", 600), ("minecraft:trident", 1, "Trident", 350),
            ("minecraft:music_disc_pigstep", 1, "Music Disc", 80), ("minecraft:heart_of_the_sea", 1, "Heart of the Sea", 180), ("minecraft:dragon_head", 1, "Dragon Head", 500)]


class Sim:
    def __init__(self, now):
        self.now, self.rng = now, random.Random(SEED)
        self.today = now // DAY
        self.d0 = self.today - DAYS
        chunk_types = cfg("claims/chunk-types.json")["types"]
        self.price = {k: v["price"] for k, v in chunk_types.items()}
        self.jobs = {k: v for k, v in cfg("claims/jobs.json")["jobs"].items()}
        self.job_share = cfg("claims/jobs.json")["jobShare"]
        general = cfg("claims/general.json")
        self.free, self.ledger_size, self.history_days = general["freeChunks"], general["ledgerSize"], general["historyDays"]
        market, starter, hist = cfg("economy/market.json"), cfg("economy/starter-items.json"), cfg("economy/general.json")
        self.tax_pct = round(market["sellTaxPct"] * 100)
        self.band = starter["band"]
        self.reset_hour, self.recovery, self.cap = starter["resetHour"], starter["recoveryPct"], starter["dailySellLots"]
        self.retention = (hist["historyRawRetention"], hist["historyHourlyRetention"], hist["historyDailyRetention"])
        auctions = cfg("economy/auctions.json")
        self.fee, self.duration = auctions["auctionFeePct"], auctions["auctionDurationMillis"]
        self.starter = {}
        for g in starter["starterGoods"]:
            for item in LOGS if g["item"] == "#minecraft:logs" else [g["item"]]:
                self.starter[item] = g
        self.events, self.wallet, self.players, self.countries = [], {}, {}, []
        self.fills, self.id_times = defaultdict(list), []
        self.stocks = {i: dict(lots=g["target"], base=float(g["base"]), observed=0.0) for i, g in self.starter.items() if i in LOGS + CROPS}
        self.sold, self.asks, self.auctions, self.deliveries = defaultdict(lambda: defaultdict(int)), [], [], defaultdict(list)
        self.bids, self.vendors = [], []

    def at(self, day, lo=9, hi=23):
        return day * DAY + self.rng.randint(lo * HOUR, hi * HOUR) + self.rng.randint(0, MIN)

    def stock_day(self, ms):
        return (datetime.fromtimestamp(ms / 1000) - timedelta(hours=self.reset_hour)).date().toordinal() - datetime(1970, 1, 1).toordinal()

    def build(self, spawn):
        for spec in COUNTRIES:
            provinces = [self.country(p, spawn, spec["kind"], spec["job"], spec["id"]) for p in spec["provinces"]]
            parent = self.country(spec, spawn, spec["kind"], spec["job"], None)
            parent["data"]["provinces"] = [p["id"] for p in provinces]
            self.countries += provinces + [parent]
        for c in self.countries:
            self.found(c)
        for d in range(self.d0 + 1, self.today + 1):
            t = d * DAY + 2000
            for c in self.countries:
                self.events.append((t, c["order"], lambda c=c, d=d, t=t: self.upkeep(c, d, t)))
            for c in self.countries:
                if self.rng.random() < .45 and (t_dep := self.at(d)) < self.now - MIN:
                    officer = next(p for p, m in c["data"]["members"].items() if m["rank"] in ("PRESIDENT", "CHANCELLOR"))
                    self.events.append((t_dep, 5, lambda c=c, o=officer: self.deposit(c, o, self.rng.randint(20, 160))))
            reset = int(datetime.fromtimestamp(d * DAY / 1000).replace(hour=self.reset_hour, minute=0, second=5).timestamp() * 1000)
            if reset < self.now:
                self.events.append((reset, 0, self.rollover))
        self.market()
        self.place_vendors()
        for t, _, fn in sorted(self.events, key=lambda e: (e[0], e[1])):
            self.clock = t
            fn()

    def country(self, spec, spawn, kind, job, parent):
        cid, members = spec["id"], {}
        created = self.at(self.d0, 8, 12)
        for i, name in enumerate(spec["players"]):
            pid = offline_uuid(name)
            rank = RANKS[min(i, 3)] if parent is None else ("PRESIDENT" if i == 0 else "CITIZEN")
            pjob = None if rank == "PRESIDENT" else ("forester" if kind == "agrarian" and i % 2 else job)
            since = created + i * self.rng.randint(HOUR, 6 * HOUR)
            members[pid] = dict(rank=rank, job=pjob, since=since, seen=self.now - self.rng.randint(10 * MIN, 30 * HOUR), progress=self.rng.randint(0, 40),
                                start=self.today, zone=[], mail=[])
            self.players[pid] = dict(name=name, country=cid, kind=kind, job=pjob)
            self.wallet[pid] = self.rng.randint(300, 1500)
        x0, z0 = spawn[0] + spec["at"][0], spawn[1] + spec["at"][1]
        cells = [(x0 + dx, z0 + dz) for dz in range(spec["size"][1]) for dx in range(spec["size"][0])]
        types = [t for t, n in spec["mix"].items() for _ in range(n)]
        cap = len(cells) // 2
        cells.insert(0, cells.pop(cap))
        owners = list(members)
        claims = []
        for i, ((x, z), ty) in enumerate(zip(cells, types)):
            owner = owners.pop(0) if ty == "residential" and owners else None
            claims.append(dict(country=cid, dim="minecraft:overworld", x=x, z=z, type=ty, at=created + i * 1500, since=self.d0, free=i < self.free, capital=i == 0,
                               debt=0, upkeepCycles=DAYS, unclaimWarned=False, owner=owner, tax=-1, lapse=0, roles={}))
        r, g, b = spec["flag"][2] >> 16, spec["flag"][2] >> 8 & 255, spec["flag"][2] & 255
        data = dict(name=spec["name"], created=created, treasury=0, pending=0, lastActive=max(m["seen"] for m in members.values()), moved=0, color=spec["color"],
                    tax=spec["tax"], shutdown=3, release=3, members=members, outsiders={}, rules={}, machines={}, fire={}, fluid={},
                    jobs={job: dict(pay=self.jobs[job]["pay"] + (2 if parent is None else 0), quota=self.jobs[job]["quota"], period=1)} if parent is None else {},
                    invites={}, requests={}, parent=parent, taxMode=spec.get("mode", "FLAT"), taxAmount=float(spec.get("amount", 0.0)), provinceDebt=0,
                    independenceRequested=False, independenceDeclinedAt=0, flag=dict(pattern=spec["flag"][0], emblem=spec["flag"][1], secondary=(r << 16) | (g << 8) | b),
                    ledger=[], history=[], provinces=[], provinceInvites={}, provinceRequests={}, autoAllies=[], alliances=[], allianceOffers={}, tradePolicy={})
        return dict(id=cid, data=data, claims=claims, order=1 if parent else 2, seed=4000 if parent is None else 1200)

    def job(self, c, name):
        return c["data"]["jobs"].get(name) or self.jobs[name]

    def move(self, c, kind, delta, actor=None, note=""):
        c["data"]["treasury"] += delta
        self.record(c, kind, delta, actor, note)

    def record(self, c, kind, delta, actor=None, note=""):
        if delta:
            c["data"]["ledger"].append(dict(at=self.clock, kind=kind, amount=delta, balance=c["data"]["treasury"], actor=actor, note=note))

    def found(self, c):
        self.clock = c["data"]["created"]
        president = next(iter(c["data"]["members"]))
        self.wallet[president] += c["seed"]
        self.deposit(c, president, c["seed"])
        for cl in c["claims"]:
            self.clock = cl["at"]
            self.move(c, "CLAIM", -self.price[cl["type"]], note=f"{cl['x']}, {cl['z']}")
        for pid, m in list(c["data"]["members"].items())[1:]:
            name = self.players[pid]["name"]
            for other in c["data"]["members"].values():
                if other["rank"] != "CITIZEN" and other is not m and len(other["mail"]) < 4:
                    other["mail"].append("INFO|" + phrase("kami_claims.mail.joined", name))

    def deposit(self, c, pid, amount):
        amount = min(amount, self.wallet[pid])
        if amount > 0:
            self.wallet[pid] -= amount
            self.move(c, "DEPOSIT", amount, pid)

    def upkeep(self, c, day, t):
        d = c["data"]
        income = 0
        for cl in c["claims"]:
            if cl["owner"] and self.wallet[cl["owner"]] >= d["tax"]:
                self.wallet[cl["owner"]] -= d["tax"]
                income += d["tax"]
        self.move(c, "PLOT_TAX", income)
        paid = 0
        for cl in sorted(c["claims"], key=lambda x: x["at"]):
            cost = self.price[cl["type"]]
            if not cl["free"] and d["treasury"] >= cost:
                d["treasury"] -= cost
                paid += cost
        self.record(c, "UPKEEP", -paid)
        if d["parent"]:
            parent = next(p for p in self.countries if p["id"] == d["parent"])
            owed = math.floor(income * d["taxAmount"]) if d["taxMode"] == "PERCENT" else int(d["taxAmount"])
            if 0 < owed <= d["treasury"]:
                self.move(c, "TRIBUTE_OUT", -owed, note=parent["data"]["name"])
                self.move(parent, "TRIBUTE_IN", owed, note=d["name"])
        budget = math.floor(income * self.job_share)
        for pid, m in d["members"].items():
            if not m["job"] or self.rng.random() > .85:
                continue
            pay = self.job(c, m["job"])["pay"]
            if pay <= budget and pay <= d["treasury"]:
                self.wallet[pid] += pay
                self.move(c, "JOB_PAY", -pay, pid, m["job"])
                budget -= pay
        since = d["history"][-1]["at"] if d["history"] else 0
        window = [e for e in d["ledger"] if e["at"] > since]
        s = lambda k: sum(e["amount"] for e in window if e["kind"] == k)
        types = defaultdict(int)
        for cl in c["claims"]:
            types[cl["type"]] += 1
        d["history"].append(dict(day=day, at=t, treasury=d["treasury"], income=s("PLOT_TAX"), upkeep=-s("UPKEEP"), jobs=-s("JOB_PAY"), tributeIn=s("TRIBUTE_IN"),
                                 tributeOut=-s("TRIBUTE_OUT"), deposits=s("DEPOSIT"), withdrawals=-s("WITHDRAW"), chunks=len(c["claims"]), debtChunks=0,
                                 members=len(d["members"]), plots=sum(1 for cl in c["claims"] if cl["owner"]), types=dict(types)))

    def curve(self, u):
        lo, hi = self.band
        if u > math.log(hi):
            return hi - 1 + hi * (u - math.log(hi))
        if u < math.log(lo):
            return lo - 1 + lo * (u - math.log(lo))
        return math.exp(u) - 1

    def value(self, item, a, b):
        g, s = self.starter[item], self.stocks[item]
        d = g["depth"]
        return s["base"] * d * (self.curve((g["target"] - a) / d) - self.curve((g["target"] - b) / d))

    def tax(self, gross):
        return (gross * self.tax_pct + 99) // 100

    def rollover(self):
        for item, s in self.stocks.items():
            g = self.starter[item]
            gap = g["target"] - s["lots"]
            move = math.floor(gap * self.recovery / 100 + .5)
            s["lots"] += move if move or not self.recovery else (gap > 0) - (gap < 0)
            if s["observed"] > 0:
                s["base"] += max(-s["base"] * .1, min(s["base"] * .1, s["observed"] - s["base"]))
        self.sold.clear()

    def sell_stock(self, pid, item, lots):
        g, s = self.starter[item], self.stocks[item]
        limit = max(g["target"], math.ceil(g["target"] - g["depth"] * math.log(self.band[0]) - 1e-9))
        lots = min(lots, limit - s["lots"])
        if lots <= 0:
            return
        guaranteed = min(lots, max(0, self.cap - self.sold[pid]["*"]))
        gross = math.floor(guaranteed * max(1, round(s["base"])) + self.value(item, s["lots"] + guaranteed, s["lots"] + lots) + 1e-9)
        if gross <= 0:
            return
        self.wallet[pid] += gross - self.tax(gross)
        s["lots"] += lots
        self.sold[pid]["*"] += guaranteed
        self.fills[item].append((self.clock, gross // lots, lots * g["lot"]))
        self.id_times.append(self.clock)

    def buy_stock(self, pid, item, lots):
        g, s = self.starter[item], self.stocks[item]
        lots = min(lots, s["lots"])
        if lots <= 0:
            return
        cost = math.ceil(self.value(item, s["lots"] - lots, s["lots"]) - 1e-9)
        if self.wallet[pid] < cost + self.tax(cost):
            return
        self.wallet[pid] -= cost + self.tax(cost)
        s["lots"] -= lots
        self.fills[item].append((self.clock, cost // lots, lots * g["lot"]))
        self.id_times.append(self.clock)

    def trade(self, seller, buyer, item, price, qty, lot=1):
        gross = qty // lot * price
        if seller == buyer or self.wallet[buyer] < gross:
            return
        self.wallet[buyer] -= gross
        self.wallet[seller] += gross - gross * self.tax_pct // 100
        self.fills[item].append((self.clock, price, qty))
        self.id_times.append(self.clock)
        if item in self.stocks:
            s = self.stocks[item]
            s["observed"] = price if s["observed"] <= 0 else s["observed"] * .8 + price * .2

    def step(self, price):
        return 10 // math.gcd(10, price) if self.tax_pct == 10 else 100 // math.gcd(100, price * self.tax_pct)

    def clean_qty(self, price, spend):
        st = self.step(price)
        return st * max(1, min(64, round(spend / price / st)))

    def market(self):
        by_kind = defaultdict(list)
        for pid, p in self.players.items():
            by_kind[p["kind"]].append(pid)
        everyone = list(self.players)
        for kind, goods in GOODS.items():
            for item, (base, trend) in goods.items():
                walk = 0.0
                for day in range(self.d0 + 1, self.today + 1):
                    walk += trend + self.rng.gauss(0, .03)
                    for _ in range(self.rng.randint(2, 6)):
                        t = self.at(day)
                        if t >= self.now - MIN:
                            continue
                        price = max(1, round(base * math.exp(walk + (t % DAY) / DAY * trend) * self.rng.uniform(.93, 1.07)))
                        seller, buyer = self.rng.choice(by_kind[kind]), self.rng.choice([p for p in everyone if self.players[p]["kind"] != kind])
                        qty = self.clean_qty(price, self.rng.uniform(30, 180))
                        self.events.append((t, 3, lambda s=seller, b=buyer, i=item, p=price, q=qty: self.trade(s, b, i, p, q)))
                end = max(1, round(base * math.exp(walk)))
                for seller in self.rng.sample(by_kind[kind], self.rng.randint(1, 3)):
                    price = max(1, round(end * self.rng.uniform(1.0, 1.15)))
                    self.asks.append(dict(item=item, owner=seller, price=price, amount=self.clean_qty(price, self.rng.uniform(60, 400)),
                                          placedAt=self.now - self.rng.randint(20 * MIN, 2 * DAY), lot=1))
                for buyer in self.rng.sample([p for p in everyone if self.players[p]["kind"] != kind], self.rng.randint(0, 2)):
                    price = round(end * self.rng.uniform(.8, .95))
                    if 1 <= price < end:
                        placed = self.now - self.rng.randint(20 * MIN, DAY)
                        self.events.append((placed, 7, lambda b=buyer, i=item, p=price, q=self.clean_qty(price, self.rng.uniform(40, 200)), t=placed: self.place_bid(b, i, p, q, t)))
        farmers, others = by_kind["agrarian"], by_kind["mining"] + by_kind["trade"]
        for day in range(self.d0 + 1, self.today + 1):
            for pid in farmers:
                for _ in range(self.rng.randint(1, 3)):
                    t = self.at(day, 7, 22)
                    item = self.rng.choice(LOGS if self.players[pid]["job"] == "forester" else CROPS)
                    if t < self.now - MIN:
                        self.events.append((t, 4, lambda p=pid, i=item, n=self.rng.randint(1, 4): self.sell_stock(p, i, n)))
            for pid in self.rng.sample(others, 8):
                t = self.at(day, 7, 22)
                item = self.rng.choice(["minecraft:bread", "minecraft:baked_potato", "minecraft:apple"] + LOGS)
                if t < self.now - MIN:
                    self.events.append((t, 4, lambda p=pid, i=item, n=self.rng.randint(1, 3): self.buy_stock(p, i, n)))
            for _ in range(3):
                t, item = self.at(day), self.rng.choice(["minecraft:bread", "minecraft:oak_log", "minecraft:apple"])
                price = 5 * max(1, round(self.starter[item]["base"] * self.rng.uniform(.95, 1.25) / 5))
                if t < self.now - MIN:
                    self.events.append((t, 3, lambda s=self.rng.choice(farmers), b=self.rng.choice(others), i=item, p=price: self.trade(s, b, i, p, 2 * 64, 64)))
        for item in ["minecraft:bread", "minecraft:oak_log", "minecraft:wheat", "minecraft:carrot"]:
            price = 5 * max(1, round(self.starter[item]["base"] * self.rng.uniform(1.0, 1.3) / 5))
            self.asks.append(dict(item=item, owner=self.rng.choice(farmers), price=price, amount=64 * self.step(price) * self.rng.randint(1, 3),
                                  placedAt=self.now - self.rng.randint(HOUR, DAY), lot=64))
        for i, (item, count, label, start) in enumerate(AUCTIONS):
            created = self.now - (self.rng.randint(2 * HOUR, 60 * HOUR) if i < 5 else self.rng.randint(4 * DAY, 6 * DAY))
            state = "OPEN" if i < 5 else ["SOLD", "SOLD", "EXPIRED", "CANCELLED"][i - 5]
            seller = self.rng.choice(everyone)
            self.events.append((created, 6, lambda it=item, c=count, l=label, st=start, s=seller, cr=created, state=state: self.auction(it, c, l, st, s, cr, state)))

    def place_vendors(self):
        goods = ["minecraft:bread", "minecraft:oak_log", "minecraft:wheat", "minecraft:carrot", "minecraft:apple"]
        for c in self.countries:
            for cl in [cl for cl in c["claims"] if cl["type"] == "market"][:2]:
                item, sell = self.rng.choice(goods), self.rng.random() < .7
                price = max(1, round(self.starter[item]["base"] * self.rng.uniform(.85, 1.2) * (1 if sell else .8)))
                self.vendors.append(dict(dim="minecraft:overworld", x=cl["x"] * 16 + 8, y=64, z=cl["z"] * 16 + 8, country=c["id"],
                                         owner=self.rng.choice(list(c["data"]["members"])), item=item, price=price, count=64, sell=sell,
                                         stock=64 * self.rng.randint(1, 12) if sell else -1, seen=self.now - self.rng.randint(MIN, HOUR)))

    def place_bid(self, buyer, item, price, qty, placed):
        if self.wallet[buyer] >= price * qty:
            self.wallet[buyer] -= price * qty
            self.bids.append(dict(item=item, owner=buyer, price=price, amount=qty, placedAt=placed, lot=1))

    def auction(self, item, count, label, start, seller, created, state):
        a = dict(id=0, seller=seller, stackData=self.stack(item, count), label=label, startPrice=start, buyNowPrice=start * 2 if self.rng.random() < .5 else None,
                 currentBid=0, currentBidder="", createdAt=created, expiresAt=created + self.duration, state=state)
        if state in ("OPEN", "SOLD") and (state == "SOLD" or self.rng.random() < .7):
            bidders = [p for p in self.players if p != seller]
            bid = start
            for n in range(self.rng.randint(1, 4)):
                who = self.rng.choice(bidders)
                if self.wallet[who] < bid:
                    continue
                if a["currentBidder"]:
                    self.wallet[a["currentBidder"]] += a["currentBid"]
                self.wallet[who] -= bid
                a["currentBid"], a["currentBidder"] = bid, who
                self.id_times.append(created + (n + 1) * (self.now - created) // 6)
                bid = math.ceil(bid * self.rng.uniform(1.05, 1.2))
        if state == "SOLD":
            if not a["currentBidder"]:
                a["state"] = "EXPIRED"
            else:
                self.wallet[seller] += a["currentBid"] - math.floor(a["currentBid"] * self.fee)
                self.deliveries[a["currentBidder"]].append(dict(item="", qty=0, stackData=a["stackData"]))
        self.auctions.append(a)

    @staticmethod
    def stack(item, count):
        return base64.b64encode(Nbt.dump({"id": (8, item), "count": (3, count)})).decode()

    def history(self, now):
        books, hist = {}, {}
        raw_keep, hour_keep, day_keep = self.retention
        for item, fills in self.fills.items():
            mid, raw, hourly, daily, acc = 0.0, [], [], [], {HOUR: [None, 0], DAY: [None, 0]}
            for t, price, qty in sorted(fills):
                open_ = mid if mid > 0 else float(price)
                base = mid if mid > 0 else float(price)
                mid = max(1.0, base + max(-base * .05, min(base * .05, price - base)))
                b = dict(open=open_, high=float(max(price, open_, mid)), low=float(min(price, open_, mid)), close=mid, volume=qty, at=t)
                raw.append(b)
                for res, out, keep in ((HOUR, hourly, hour_keep), (DAY, daily, day_keep)):
                    cur, start = acc[res]
                    period = t - t % res
                    if cur is None or start != period:
                        if cur:
                            out.append(cur)
                        acc[res] = [dict(b), period]
                    else:
                        acc[res][0] = dict(open=cur["open"], high=max(cur["high"], b["high"]), low=min(cur["low"], b["low"]), close=b["close"],
                                           volume=cur["volume"] + b["volume"], at=t)
                    del out[:-keep]
            hist[item] = dict(raw=raw[-raw_keep:], hourly=hourly, daily=daily, hourAcc=acc[HOUR][0], hourStart=acc[HOUR][1], dayAcc=acc[DAY][0], dayStart=acc[DAY][1])
            books[item] = dict(lastFill=sorted(fills)[-1][1], midPrice=mid)
        return books, hist


def load_json(path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


ALLIANCES = [("aurelia", "verdania")]
TRADE_POLICY = [("verdania", "karvhold", 15, False), ("aurelia", "karvhold", 0, True)]


def main():
    ap = argparse.ArgumentParser(description="Seed a 7-day economy simulation into the test world.")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--world", type=Path, default=WORLD)
    args = ap.parse_args()
    world, now = args.world, int(time.time() * 1000)

    _, level = Nbt.load((world / "level.dat").read_bytes())
    spawn = (level["Data"][1]["SpawnX"][1] >> 4, level["Data"][1]["SpawnZ"][1] >> 4)
    sim = Sim(now)
    sim.build(spawn)
    sim_players, sim_countries = set(sim.players), {c["id"] for c in sim.countries}

    claims = load_json(world / "kami_claims.json", {"countries": {}, "claims": [], "reserves": [], "day": -1})
    claims["countries"] = {k: v for k, v in claims["countries"].items() if k not in sim_countries}
    claims["claims"] = [c for c in claims["claims"] if c["country"] not in sim_countries]
    taken = {(c["dim"], c["x"], c["z"]) for c in claims["claims"]}
    for c in sim.countries:
        clash = [cl for cl in c["claims"] if (cl["dim"], cl["x"], cl["z"]) in taken]
        if clash:
            raise SystemExit(f"{c['id']} overlaps real claims at {clash[0]['x']},{clash[0]['z']}")
        d = c["data"]
        d["ledger"] = d["ledger"][-sim.ledger_size:]
        d["history"] = d["history"][-sim.history_days:]
        claims["countries"][c["id"]] = d
        claims["claims"] += c["claims"]
    for a, b in ALLIANCES:
        claims["countries"][a]["alliances"].append(b)
        claims["countries"][b]["alliances"].append(a)
    for a, b, tariff, embargo in TRADE_POLICY:
        claims["countries"][a]["tradePolicy"][b] = dict(tariffPct=tariff, embargo=embargo)
    claims["day"] = max(claims.get("day", -1), sim.today)

    econ = load_json(world / "kami_economy.json", {})
    econ.setdefault("books", {})
    for book in econ["books"].values():
        book["sells"] = [o for o in book["sells"] if o["owner"] not in sim_players]
        book["buys"] = [o for o in book.get("buys", []) if o["owner"] not in sim_players]
    econ["auctions"] = [a for a in econ.get("auctions", []) if a["seller"] not in sim_players]
    real_ids = [o["id"] for b in econ["books"].values() for o in b["sells"] + b["buys"]] + [a["id"] for a in econ["auctions"]]
    next_id = max([econ.get("nextOrderId", 1)] + [i + 1 for i in real_ids])
    slots = sorted([(t, 0, None) for t in sim.id_times] + [(a["placedAt"], 1, a) for a in sim.asks + sim.bids] + [(a["createdAt"], 2, a) for a in sim.auctions], key=lambda e: e[:2])
    for n, (_, _, entity) in enumerate(slots):
        if entity:
            entity["id"] = next_id + n
    books, hist = sim.history(now)
    for item, info in books.items():
        book = econ["books"].setdefault(item, dict(item=item, sells=[], lastFill=0, midPrice=0.0, recentFills=[]))
        book.update(info, recentFills=[])
    for ask in sorted(sim.asks, key=lambda a: a["placedAt"]):
        book = econ["books"].setdefault(ask["item"], dict(item=ask["item"], sells=[], lastFill=0, midPrice=float(ask["price"]), recentFills=[]))
        book["sells"].append(dict(id=ask["id"], owner=ask["owner"], price=ask["price"], amount=ask["amount"], placedAt=ask["placedAt"], synthetic=False, lot=ask["lot"]))
        book["sells"].sort(key=lambda o: (o["price"], o["placedAt"]))
    for bid in sim.bids:
        book = econ["books"].setdefault(bid["item"], dict(item=bid["item"], sells=[], lastFill=0, midPrice=float(bid["price"]), recentFills=[]))
        book.setdefault("buys", []).append(dict(id=bid["id"], owner=bid["owner"], price=bid["price"], amount=bid["amount"], placedAt=bid["placedAt"], synthetic=False, lot=bid["lot"]))
        book["buys"].sort(key=lambda o: (-o["price"], o["placedAt"]))
    econ["auctions"] += sorted(sim.auctions, key=lambda a: a["id"])
    econ["nextOrderId"] = next_id + len(slots)
    econ["pendingDelivery"] = {k: v for k, v in econ.get("pendingDelivery", {}).items() if k not in sim_players} | dict(sim.deliveries)
    econ["frozen"] = econ.get("frozen", [])
    econ["stocks"] = econ.get("stocks", {}) | {i: dict(lots=s["lots"], base=round(s["base"], 4), observed=round(s["observed"], 4)) for i, s in sim.stocks.items()}
    econ["sold"] = {k: v for k, v in econ.get("sold", {}).items() if k not in sim_players} | {p: dict(v) for p, v in sim.sold.items() if v}
    econ["day"] = sim.stock_day(now)
    econ["vendors"] = [v for v in econ.get("vendors", []) if v["country"] not in sim_countries] + sim.vendors
    history = load_json(world / "kami_economy_history.json", {"items": {}})
    history["items"].update(hist)

    bank_path = world / "data/numismatics_bank.dat"
    root_tag = Nbt.load(bank_path.read_bytes())[1] if bank_path.exists() else {"data": (10, {"Accounts": (9, (10, []))}), "DataVersion": (3, 3955)}
    accounts = root_tag["data"][1].setdefault("Accounts", (9, (10, [])))[1][1]
    sim_ints = {tuple(uuid_ints(p)) for p in sim_players}
    accounts[:] = [a for a in accounts if tuple(a["id"][1]) not in sim_ints]
    accounts += [{"AccountType": (8, "PLAYER"), "SubAccounts": (9, (0, [])), "balance": (3, bal), "id": (11, uuid_ints(p))} for p, bal in sim.wallet.items()]

    cache = [e for e in load_json(SERVER / "usercache.json", []) if e["uuid"] not in sim_players]
    expires = (datetime.now().astimezone() + timedelta(days=30)).strftime("%Y-%m-%d %H:%M:%S %z")
    cache += [dict(name=p["name"], uuid=pid, expiresOn=expires) for pid, p in sim.players.items()]
    names = {k: v for k, v in load_json(SERVER / "usernamecache.json", {}).items() if k not in sim_players} | {pid: p["name"] for pid, p in sim.players.items()}

    wal = world / "kami_economy.wal"
    wal_lines = len(wal.read_text(encoding="utf-8").splitlines()) if wal.exists() else 0
    for c in sim.countries:
        d = c["data"]
        print(f"{d['name']:<13} {'province of ' + d['parent'] if d['parent'] else 'country':<22} members={len(d['members'])} claims={len(c['claims'])} "
              f"treasury={d['treasury']} ledger={len(d['ledger'])} days={len(d['history'])}")
    print(f"players={len(sim.players)} wallets={sum(sim.wallet.values())} fills={sum(map(len, sim.fills.values()))} items={len(hist)} "
          f"asks={len(sim.asks)} bids={len(sim.bids)} vendors={len(sim.vendors)} alliances={len(ALLIANCES)} tariffs={sum(t > 0 for _, _, t, _ in TRADE_POLICY)} embargoes={sum(e for *_, e in TRADE_POLICY)} auctions={len(sim.auctions)} stocks={len(sim.stocks)} capped={len(econ['sold'])} ids={next_id}..{econ['nextOrderId'] - 1} wal={wal_lines} spawn={spawn}")
    if args.dry_run:
        print("dry run: nothing written")
        return
    dump = lambda path, obj, indent=4: path.write_text(json.dumps(obj, indent=indent, ensure_ascii=False), encoding="utf-8")
    dump(world / "kami_claims.json", claims)
    dump(world / "kami_economy.json", econ)
    dump(world / "kami_economy_history.json", history, None)
    bank_path.write_bytes(Nbt.dump(root_tag))
    dump(SERVER / "usercache.json", cache, None)
    dump(SERVER / "usernamecache.json", names, 2)
    print("written")


if __name__ == "__main__":
    main()
