import gzip, io, random, struct, sys

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTES, STRING, LIST, COMPOUND, INTS, LONGS = range(13)
SCALARS = {BYTE: ">b", SHORT: ">h", INT: ">i", LONG: ">q", FLOAT: ">f", DOUBLE: ">d"}
FRESH_WORLD_KEYS = ("ScheduledEvents", "DragonFight", "CustomBossEvents", "WanderingTraderId", "WanderingTraderSpawnChance", "WanderingTraderSpawnDelay", "Player")
ZEROED_KEYS = ("Time", "DayTime", "clearWeatherTime", "rainTime", "thunderTime", "raining", "thundering", "initialized", "SpawnX", "SpawnY", "SpawnZ")


def read(stream, kind):
    if kind in SCALARS:
        fmt = SCALARS[kind]
        return (kind, struct.unpack(fmt, stream.read(struct.calcsize(fmt)))[0])
    if kind == STRING:
        return (kind, stream.read(struct.unpack(">H", stream.read(2))[0]).decode("utf-8"))
    if kind == BYTES:
        return (kind, stream.read(struct.unpack(">i", stream.read(4))[0]))
    if kind in (INTS, LONGS):
        size = 4 if kind == INTS else 8
        count = struct.unpack(">i", stream.read(4))[0]
        return (kind, stream.read(count * size))
    if kind == LIST:
        inner = stream.read(1)[0]
        count = struct.unpack(">i", stream.read(4))[0]
        return (kind, (inner, [read(stream, inner) for _ in range(count)]))
    entries = {}
    while True:
        tag = stream.read(1)[0]
        if tag == END:
            return (COMPOUND, entries)
        name = read(stream, STRING)[1]
        entries[name] = read(stream, tag)


def write(out, tag):
    kind, value = tag
    if kind in SCALARS:
        out.write(struct.pack(SCALARS[kind], value))
    elif kind == STRING:
        raw = value.encode("utf-8")
        out.write(struct.pack(">H", len(raw)) + raw)
    elif kind == BYTES:
        out.write(struct.pack(">i", len(value)) + value)
    elif kind in (INTS, LONGS):
        out.write(struct.pack(">i", len(value) // (4 if kind == INTS else 8)) + value)
    elif kind == LIST:
        inner, items = value
        out.write(bytes([inner]) + struct.pack(">i", len(items)))
        for item in items:
            write(out, item)
    else:
        for name, child in value.items():
            out.write(bytes([child[0]]))
            write(out, (STRING, name))
            write(out, child)
        out.write(bytes([END]))


def load(path):
    stream = io.BytesIO(gzip.open(path).read())
    stream.read(1)
    read(stream, STRING)
    return read(stream, COMPOUND)


def save(path, root):
    out = io.BytesIO()
    out.write(bytes([COMPOUND]))
    write(out, (STRING, ""))
    write(out, root)
    with gzip.open(path, "wb") as target:
        target.write(out.getvalue())


def freshen(root):
    data = root[1]["Data"][1]
    for key in FRESH_WORLD_KEYS:
        data.pop(key, None)
    for key in ZEROED_KEYS:
        if key in data:
            data[key] = (data[key][0], 0)
    settings = data["WorldGenSettings"][1]
    settings["seed"] = (LONG, random.SystemRandom().randrange(-(1 << 63), 1 << 63))


if __name__ == "__main__":
    source, target = sys.argv[1], sys.argv[2]
    root = load(source)
    freshen(root)
    save(target, root)
    print(root[1]["Data"][1]["WorldGenSettings"][1]["seed"][1])
