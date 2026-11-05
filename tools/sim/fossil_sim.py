# Balancing-Simulation Fossil Collector (Design-Phase-Referenz). Aufruf: python3 fossil_sim.py [seed]
import random, math, sys

random.seed(int(sys.argv[1]) if len(sys.argv) > 1 else 1)

# --- Parameter (spaeter JSON) ---
LAYERS = [  # name, unlock cost, team base cost, block value $/s, fragility, ruhm weight
    dict(name="Quartaer", unlock=0,      team_cost=10,     value=1.0,  frag=0.15, ruhm=1),
    dict(name="Neogen",   unlock=6_000,  team_cost=2_000,    value=8.0,  frag=0.30, ruhm=3),
    dict(name="Kreide",   unlock=250_000,team_cost=60_000, value=60.0, frag=0.45, ruhm=10),
]
SPECIES_PER_LAYER = 6
BONES_PER_SPECIES = 8
SPECIES_WEIGHTS = [40, 25, 15, 10, 5, 2]
SELL_FACTOR = 20
FIND_CHANCE = 0.08   # Duplikat-Verkauf = 20 s Ausstellungswert
ZONES = 4
TEAM_GROWTH = 1.12
TEAM_RATE = 0.1          # Bloecke/s pro Team
PREP_COST, PREP_GROWTH, PREP_RATE = 25, 1.17, 0.2
HALL_COST, HALL_GROWTH, HALL_SLOTS = 50, 3.0, 8
LAB_TIME = 30            # s pro Forschung
LAB_COST, LAB_GROWTH = 300, 4.0
TOOL_COST, TOOL_GROWTH, TOOL_STEP = 3, 1.6, 0.03
MANUAL_DIG, MANUAL_PREP, ACTIVE_SECONDS = 0.5, 0.5, 600
SKELETON_MULT = 2.0
RECON_CAP = 0.85         # Rekonstruktion max "Exzellent"

def tier(q):
    return ["Fragment","Beschaedigt","Gut","Exzellent","Museumsreif"][min(4,int(q*5))]

class Run:
    def __init__(s):
        s.money = 0.0; s.fp = 0.0; s.t = 0
        s.layers = 1
        s.teams = [0]*len(LAYERS); s.teams[0] = 0
        s.blocks = [0.0]*len(LAYERS)
        s.preps = 0; s.prep_acc = 0.0
        s.halls = 1
        s.labs = 1; s.lab_queue = []; s.lab_busy = []
        s.tool = 0.0; s.tool_lvl = 0
        s.best = {}     # (L,sp,bone) -> quality
        s.parts = {}    # (L,sp,bone,zone) -> best zone quality
        s.dups = 0; s.sold = 0.0
    # -- Preise
    def team_price(s, L): return LAYERS[L]["team_cost"] * TEAM_GROWTH ** s.teams[L]
    def prep_price(s): return PREP_COST * PREP_GROWTH ** s.preps
    def hall_price(s): return HALL_COST * HALL_GROWTH ** (s.halls - 1)
    def lab_price(s): return LAB_COST * LAB_GROWTH ** (s.labs - 1)
    def tool_price(s): return TOOL_COST * TOOL_GROWTH ** s.tool_lvl
    # -- Museum
    def complete(s, L, sp):
        return all((L,sp,b) in s.best for b in range(BONES_PER_SPECIES))
    def income(s):
        items = sorted(((LAYERS[k[0]]["value"] * (0.5 + q) *
                         (SKELETON_MULT if s.complete(k[0],k[1]) else 1), k)
                        for k, q in s.best.items()), reverse=True)
        return sum(v for v, _ in items[: s.halls * HALL_SLOTS])
    def skeletons(s):
        return [(L,sp) for L in range(s.layers) for sp in range(SPECIES_PER_LAYER) if s.complete(L,sp)]
    def ruhm(s):
        r = 0
        for L, sp in s.skeletons():
            avg = sum(s.best[(L,sp,b)] for b in range(BONES_PER_SPECIES)) / BONES_PER_SPECIES
            r += LAYERS[L]["ruhm"] * (0.5 + avg)
        return math.floor(r)
    # -- Praeparation eines Blocks
    def prepare(s, L):
        if random.random() > FIND_CHANCE:
            s.money += LAYERS[L]['value'] * 0.5; s.sold += LAYERS[L]['value'] * 0.5; return
        sp = random.choices(range(SPECIES_PER_LAYER), SPECIES_WEIGHTS)[0]; b = random.randrange(BONES_PER_SPECIES)
        zones = []
        for z in range(ZONES):
            base = random.uniform(0.3, 1.0)
            dmg = max(0.0, LAYERS[L]["frag"] - s.tool) * random.uniform(0.5, 2.0)
            zones.append(max(0.0, base - dmg))
        q = sum(zones) / ZONES
        key = (L, sp, b)
        if key not in s.best or q > s.best[key]:
            s.best[key] = q
        else:
            s.dups += 1
            if len(s.lab_queue) < 3 * s.labs: s.lab_queue.append((L, sp, b, zones))
            else:
                v = LAYERS[L]['value'] * (0.5 + q) * SELL_FACTOR; s.money += v; s.sold += v
    def lab_tick(s):
        s.lab_busy = [(t-1, job) for t, job in s.lab_busy]
        for t, (L, sp, b, zones) in [x for x in s.lab_busy if x[0] <= 0]:
            s.fp += L + 1
            for z, zq in enumerate(zones):
                k = (L, sp, b, z)
                s.parts[k] = max(s.parts.get(k, 0), zq)
            # Rekonstruktion pruefen
            if all((L,sp,b,z) in s.parts for z in range(ZONES)):
                rq = min(RECON_CAP, sum(s.parts[(L,sp,b,z)] for z in range(ZONES)) / ZONES)
                if rq > s.best.get((L,sp,b), 0): s.best[(L,sp,b)] = rq
        s.lab_busy = [x for x in s.lab_busy if x[0] > 0]
        while len(s.lab_busy) < s.labs and s.lab_queue:
            s.lab_busy.append((LAB_TIME, s.lab_queue.pop()))
        if len(s.lab_queue) > 200: s.lab_queue = s.lab_queue[-200:]
    # -- Greedy Kauf-KI: billigste sinnvolle Option
    def buy(s):
        while True:
            opts = []
            for L in range(s.layers): opts.append((s.team_price(L), "team", L))
            if s.layers < len(LAYERS): opts.append((LAYERS[s.layers]["unlock"], "layer", s.layers))
            produced = sum(s.teams[L] * TEAM_RATE * 2 ** sum(s.teams[L] >= m for m in (25,50,100,200)) for L in range(s.layers)) + (MANUAL_DIG if s.t < ACTIVE_SECONDS else 0)
            if s.preps * PREP_RATE < produced * 1.1: opts.append((s.prep_price()*0.7, "prep", 0))
            if len(s.best) > s.halls * HALL_SLOTS * 0.8: opts.append((s.hall_price(), "hall", 0))
            if s.dups > 20 * s.labs: opts.append((s.lab_price(), "lab", 0))
            price, kind, L = min(opts)
            real = s.prep_price() if kind == "prep" else price
            if real > s.money: return
            s.money -= real
            if kind == "team": s.teams[L] += 1
            elif kind == "layer": s.layers += 1; s.log(f"Schicht {LAYERS[L]['name']} freigeschaltet")
            elif kind == "prep": s.preps += 1
            elif kind == "hall": s.halls += 1
            elif kind == "lab": s.labs += 1
    def log(s, msg): print(f"  [{s.t//60:3d}:{s.t%60:02d}] {msg}")
    def tick(s):
        s.t += 1
        active = s.t < ACTIVE_SECONDS
        for L in range(s.layers):
            s.blocks[L] += s.teams[L] * TEAM_RATE * 2 ** sum(s.teams[L] >= m for m in (25,50,100,200))
        if active: s.blocks[s.layers-1] += MANUAL_DIG
        s.prep_acc += s.preps * PREP_RATE + (MANUAL_PREP if active else 0)
        while s.prep_acc >= 1 and sum(s.blocks) >= 1:
            L = max(i for i in range(s.layers) if s.blocks[i] >= 1) if any(b >= 1 for b in s.blocks) else None
            if L is None: break
            s.blocks[L] -= 1; s.prep_acc -= 1; s.prepare(L)
        s.prep_acc = min(s.prep_acc, 5)
        s.lab_tick()
        while s.fp >= s.tool_price():
            s.fp -= s.tool_price(); s.tool_lvl += 1; s.tool += TOOL_STEP
        before = len(s.skeletons())
        s.money += s.income()
        s.buy()
        if len(s.skeletons()) > before:
            s.log(f"Skelett komplett ({len(s.skeletons())} gesamt), Ruhm jetzt {s.ruhm()}")

r = Run()
last = 0
for minute in range(1, 121):
    s0 = r.sold
    for _ in range(60): r.tick()
    sale = (r.sold - s0) / 60
    if minute in (5, 10, 15, 20, 30, 45, 60, 90, 120):
        rate = (r.sold - last) / 60 if False else 0
        print(f"t={minute:3d}min  Geld={r.money:12.0f}  Museum={r.income():9.0f}/s Verkauf={sale:9.0f}/s  "
              f"Teams={r.teams[:r.layers]} Prep={r.preps} Hallen={r.halls} Labs={r.labs} "
              f"Werkzeug=-{r.tool:.2f} Funde={len(r.best)}/{r.layers*SPECIES_PER_LAYER*BONES_PER_SPECIES} "
              f"Dups={r.dups} Skelette={len(r.skeletons())} Ruhm={r.ruhm()}")
