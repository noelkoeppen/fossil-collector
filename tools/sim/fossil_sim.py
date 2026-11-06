#!/usr/bin/env python3
"""Balancing-Simulation Fossil Collector (GAME_DESIGN.md Kapitel 5.1, 6.7, AP-28).

Simuliert den ersten Durchlauf mit derselben gierigen Kauf-KI wie der Kotlin-``PacingTest``
(``core/src/test/.../core/sim/GreedyBot.kt``) und liest alle Spielwerte aus den JSON-Dateien in
``core/src/main/resources/data/``. Im Code stehen nur Formeln und das Verhalten des simulierten
Spielers (``BotConfig``), keine Balancing-Zahlen.

Die Spiellogik bildet die Kotlin-Engine nach (``GameEngine.advance`` mit Grabung, Praeparation in
ganzen Bloecken, Einordnen der Funde, Museum, Labor, Meilensteinen; Pech-Schutz, Buch-Boni,
Rekonstruktion mit Cap, Modifier-System). Der Zufallsgenerator ist ein Nachbau von
``kotlin.random.Random(seed)`` (XorWow), deshalb liefert die Simulation fuer denselben Seed
dieselbe Zufallsfolge wie ``SeededRandom`` in Kotlin.

Nur Standardbibliothek. Anleitung: ``tools/sim/README.md``.
"""

from __future__ import annotations

import argparse
import copy
import csv
import json
import math
import os
import sys
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

DEFAULT_DATA_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "main", "resources", "data")
)

DATA_FILES = ("layers", "species", "bones", "generators", "upgrades", "balance", "milestones", "skilltree")

# Feste Verknuepfungen zwischen Definitions-IDs und Feldern des Spielstands
# (wie core.data.DefinitionIds, keine Spielwerte).
PREPARATOR = "preparator"
HALL = "hall"
LAB_SLOT = "lab_slot"
TOOLS = "tools"

# Formelkonstante aus Kapitel 4.4/4.6/5.3 (``0.5 + q``), kein Balancing-Wert.
QUALITY_OFFSET = 0.5
# Gewicht vorhandener Knochen beim Pech-Schutz (Kapitel 4.9: "vorhandene das Gewicht 1").
EXISTING_BONE_WEIGHT = 1.0
# Numerische Toleranzen wie in der Kotlin-Engine.
ROUNDING_TOLERANCE = 1e-9
DONE_EPSILON = 1e-9
EPSILON = 1e-9
MAX_PROGRESS = math.nextafter(1.0, 0.0)

FACTOR_BASE = 1.0
ADDITIVE_BASE = 0.0


# --------------------------------------------------------------------------------------------
# Zufall: Nachbau von kotlin.random.Random(seed: Long) (XorWowRandom)
# --------------------------------------------------------------------------------------------

_M32 = 0xFFFFFFFF


class KotlinRandom:
    """XorWow wie ``kotlin.random.XorWowRandom``; ``Random(seed)`` mit 64-Bit-Seed.

    Liefert dieselbe Folge wie ``SeededRandom(seed)`` in ``core`` (``nextDouble``,
    ``nextDouble(from, until)``).
    """

    def __init__(self, seed: int) -> None:
        seed &= 0xFFFFFFFFFFFFFFFF
        seed1 = seed & _M32
        seed2 = (seed >> 32) & _M32
        self._x = seed1
        self._y = seed2
        self._z = 0
        self._w = 0
        self._v = (~seed1) & _M32
        self._addend = ((seed1 << 10) ^ (seed2 >> 4)) & _M32
        if (self._x | self._y | self._z | self._w | self._v) == 0:
            raise ValueError("Initial state must have at least one non-zero element.")
        for _ in range(64):
            self._next_int()

    def _next_int(self) -> int:
        t = self._x
        t ^= t >> 2
        self._x = self._y
        self._y = self._z
        self._z = self._w
        v0 = self._v
        self._w = v0
        t = ((t ^ (t << 1)) ^ v0 ^ (v0 << 4)) & _M32
        self._v = t
        self._addend = (self._addend + 362437) & _M32
        return (t + self._addend) & _M32

    def _next_bits(self, bit_count: int) -> int:
        return self._next_int() >> (32 - bit_count)

    def next_double(self) -> float:
        hi = self._next_bits(26)
        lo = self._next_bits(27)
        return float((hi << 27) + lo) / float(1 << 53)

    def next_double_range(self, start: float, until: float) -> float:
        if start == until:
            return start
        r = start + self.next_double() * (until - start)
        return math.nextafter(until, -math.inf) if r >= until else r


def kotlin_sum(values) -> float:
    """Summe in Reihenfolge ohne Kompensation (wie Kotlin ``sum``/``sumOf``; Pythons ``sum`` kompensiert ab 3.12)."""
    total = 0.0
    for v in values:
        total += v
    return total


def kotlin_average(values: Sequence[float]) -> float:
    return kotlin_sum(values) / len(values) if values else 0.0


# --------------------------------------------------------------------------------------------
# Daten
# --------------------------------------------------------------------------------------------


def load_data(data_dir: str = DEFAULT_DATA_DIR) -> Dict[str, Any]:
    """Laedt alle Datendateien aus [data_dir] als ``{name: json}``."""
    data: Dict[str, Any] = {}
    for name in DATA_FILES:
        path = os.path.join(data_dir, name + ".json")
        with open(path, encoding="utf-8") as fh:
            data[name] = json.load(fh)
    return data


def apply_override(data: Dict[str, Any], assignment: str) -> None:
    """Setzt einen Wert, z. B. ``balance.findChance=0.1``, ``layers.neogene.unlockCost=8000``,
    ``balance.bookTierFactor=[1,1.5,2,3,4]``. In Listen-Dateien waehlt der zweite Teil das Objekt
    mit dieser ``id``. Der Wert wird als JSON gelesen (sonst als Text)."""
    if "=" not in assignment:
        raise ValueError(f"--set braucht die Form pfad=wert: {assignment!r}")
    path, raw = assignment.split("=", 1)
    parts = path.strip().split(".")
    if len(parts) < 2 or parts[0] not in data:
        raise ValueError(f"Unbekannter Pfad: {path!r} (Dateien: {', '.join(DATA_FILES)})")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        value = raw
    node: Any = data[parts[0]]
    for part in parts[1:-1]:
        if isinstance(node, list):
            matches = [item for item in node if isinstance(item, dict) and item.get("id") == part]
            if not matches:
                raise ValueError(f"Keine ID {part!r} in {path!r}")
            node = matches[0]
        elif isinstance(node, dict) and part in node:
            node = node[part]
        else:
            raise ValueError(f"Unbekannter Schluessel {part!r} in {path!r}")
    last = parts[-1]
    if isinstance(node, list):
        raise ValueError(f"Pfad {path!r} endet in einer Liste; ID und Feld angeben")
    if last not in node:
        raise ValueError(f"Unbekanntes Feld {last!r} in {path!r}")
    node[last] = value


Modifier = Tuple[str, str, float, str]  # (stat, op, value, category)


def to_modifier(raw: Dict[str, Any], category: Optional[str] = None) -> Modifier:
    return (raw["stat"], raw["op"], float(raw["value"]), category or raw["category"])


class GameData:
    """Aufbereitete Spieldaten (Entsprechung zu ``core.data.GameData``)."""

    def __init__(self, raw: Dict[str, Any]) -> None:
        self.raw = raw
        self.balance: Dict[str, Any] = raw["balance"]
        self.layers: List[Dict[str, Any]] = sorted(raw["layers"], key=lambda l: l["order"])
        self.layers_by_id = {l["id"]: l for l in self.layers}
        self.layers_deepest_first = sorted(self.layers, key=lambda l: l["order"], reverse=True)
        self.species: List[Dict[str, Any]] = raw["species"]
        self.species_by_id = {s["id"]: s for s in self.species}
        self.species_by_layer: Dict[str, List[Dict[str, Any]]] = {}
        for s in self.species:
            self.species_by_layer.setdefault(s["layer"], []).append(s)
        self.bones_by_id = {b["id"]: b for b in raw["bones"]}
        self.generators = raw["generators"]
        self.generators_by_id = {g["id"]: g for g in self.generators}
        self.upgrades = raw["upgrades"]
        self.upgrades_by_id = {u["id"]: u for u in self.upgrades}
        self.milestones = raw["milestones"]
        self.skills_by_id = {s["id"]: s for s in raw["skilltree"]}
        self.tiers = sorted(self.balance["qualityTiers"], key=lambda t: t["minQuality"])
        self.stat_limits: Dict[str, Dict[str, float]] = self.balance.get("statLimits", {})
        self.researched_bonus = to_modifier(self.balance["researchedBonus"], "book")

    def tier_index(self, quality: float) -> int:
        index = 0
        for i, tier in enumerate(self.tiers):
            if quality >= tier["minQuality"]:
                index = i
        return index

    def tier_factor(self, index: int) -> float:
        factors = self.balance["bookTierFactor"]
        if not factors:
            return 0.0
        return float(factors[max(0, min(index, len(factors) - 1))])

    def layer_of_species(self, species_id: str) -> Dict[str, Any]:
        return self.layers_by_id[self.species_by_id[species_id]["layer"]]


# --------------------------------------------------------------------------------------------
# Modifier-System (Kapitel 6.2, wie core.modifier.ModifierEngine)
# --------------------------------------------------------------------------------------------


class ModifierEngine:
    def __init__(self, stat_limits: Dict[str, Dict[str, float]]) -> None:
        self.limits = stat_limits
        self.sources: Dict[str, List[Modifier]] = {}
        self._cache: Dict[Tuple[str, float], float] = {}

    def set_source(self, source_id: str, modifiers: List[Modifier]) -> None:
        self.sources[source_id] = list(modifiers)
        self._cache.clear()

    def remove_source(self, source_id: str) -> None:
        if self.sources.pop(source_id, None) is not None:
            self._cache.clear()

    def value(self, stat: str, base: float) -> float:
        key = (stat, base)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        add_sum = 0.0
        mult_sums: Dict[str, float] = {}
        for modifiers in self.sources.values():
            for m_stat, op, value, category in modifiers:
                if m_stat != stat:
                    continue
                if op == "add":
                    add_sum += value
                else:
                    mult_sums[category] = mult_sums.get(category, 0.0) + value
        result = base + add_sum
        for s in mult_sums.values():
            result *= 1.0 + s
        limit = self.limits.get(stat)
        if limit:
            if "min" in limit and result < limit["min"]:
                result = float(limit["min"])
            if "max" in limit and result > limit["max"]:
                result = float(limit["max"])
        self._cache[key] = result
        return result

    def factor(self, stat: str) -> float:
        return self.value(stat, FACTOR_BASE)

    def additive(self, stat: str) -> float:
        return self.value(stat, ADDITIVE_BASE)


# --------------------------------------------------------------------------------------------
# Kostenkurve (Kapitel 6.3, wie core.engine.purchase.CostCurve)
# --------------------------------------------------------------------------------------------


def price_raw(base: float, growth: float, n: float) -> float:
    return base * math.pow(growth, n)


def cost_raw(base: float, growth: float, n: float, k: float) -> float:
    if k <= 0.0:
        return 0.0
    return price_raw(base, growth, n) * ((math.pow(growth, k) - 1.0) / (growth - 1.0))


def max_affordable(base: float, growth: float, n: float, money: float, cap: Optional[float] = None) -> float:
    limit = math.inf if cap is None else max(0.0, math.floor(cap))
    if money <= 0.0 or limit == 0.0:
        return 0.0
    first = price_raw(base, growth, n)
    if not math.isfinite(first) or first > money:
        return 0.0
    k = math.floor(math.log1p(money * (growth - 1.0) / first) / math.log(growth))
    if not math.isfinite(k) or k < 0.0:
        k = 0.0
    k = min(float(k), limit)
    while k > 0.0 and cost_raw(base, growth, n, k) > money:
        k -= 1.0
    while k < limit and cost_raw(base, growth, n, k + 1.0) <= money:
        k += 1.0
    return k


# --------------------------------------------------------------------------------------------
# Optionen und Spielerverhalten
# --------------------------------------------------------------------------------------------


@dataclass
class SimOptions:
    """Systeme fuer Analysen ein- und ausschalten (Ablation, Kapitel 6.7)."""

    book_bonus: bool = True  # Buch-Boni und researchedBonus (Kapitel 4.7)
    milestones: bool = True  # Meilensteine mit Belohnungen (Kapitel 5.2)
    bad_luck_protection: bool = True  # Pech-Schutz missingBoneWeight (Kapitel 4.9)
    reconstruction: bool = True  # Rekonstruktion durch die KI (Kapitel 4.5)
    skills: Tuple[str, ...] = ()  # bereits gekaufte Skills (z. B. fuer spaetere Durchlaeufe)


@dataclass
class BotConfig:
    """Verhalten des simulierten Spielers, wie ``GreedyBotConfig`` in Kotlin. Keine Spielwerte."""

    tick_seconds: float = 1.0
    active_seconds: float = 600.0
    dig_taps_per_second: float = 2.0
    prep_taps_per_second: float = 2.0
    prep_capacity_factor: float = 1.1
    prep_price_weight: float = 0.7
    hall_fill_threshold: float = 0.8
    duplicates_per_lab_slot: float = 20.0


@dataclass
class Snapshot:
    seconds: float
    money: float
    museum_income: float
    earned_per_second: float
    pieces: int
    skeletons: int
    fame: float
    teams: List[float]
    preparators: float
    halls: float
    lab_slots: float
    tool_level: float
    duplicates: int
    unlocked_layers: int
    research_points: float


# --------------------------------------------------------------------------------------------
# Spielzustand und Engine (erster Durchlauf, Kapitel 4 und 5)
# --------------------------------------------------------------------------------------------


def piece_key(species_id: str, bone_id: str) -> str:
    """Text-Form von ``PieceKey`` (MVP ohne Alter/Geschlecht)."""
    return f"{species_id}:{bone_id}:_:_"


def key_species(key: str) -> str:
    return key.split(":", 1)[0]


class Simulation:
    """Ein Durchlauf mit Kauf-KI (Nachbau von ``GreedyBot`` + ``GameEngine``)."""

    def __init__(
        self,
        data: GameData,
        seed: int,
        options: Optional[SimOptions] = None,
        bot: Optional[BotConfig] = None,
        on_event: Optional[Callable[[float, str], None]] = None,
    ) -> None:
        self.data = data
        self.options = options or SimOptions()
        self.bot = bot or BotConfig()
        self.rng = KotlinRandom(seed)
        self.modifiers = ModifierEngine(data.stat_limits)
        self.on_event = on_event
        b = data.balance

        # Meta (permanent)
        self.book: Dict[str, float] = {}
        self.researched: List[str] = []
        self.milestones_done: List[str] = []
        self.skills: List[str] = [s for s in self.options.skills if s in data.skills_by_id]
        self.pieces_found = 0.0
        self.pristine_found = 0.0
        self.research_completed = 0.0
        self.reconstructions = 0.0
        self._book_version = 0
        self._synced_book_version = -1
        self._book_sources: Dict[str, List[Modifier]] = {}

        # Startwerte (RunStart.values): Skills wirken vor dem Start
        self.sync()
        first = data.layers[0]["id"]
        self.money = 0.0
        self.research_points = 0.0
        self.unlocked = 1
        self.teams: Dict[str, float] = {first: self._whole(self.modifiers.additive("start_teams"))}
        self.blocks: Dict[str, float] = {first: 0.0}
        self.preparators = self._whole(float(data.generators_by_id[PREPARATOR].get("free", 0)))
        self.halls = self._whole(float(data.generators_by_id[HALL].get("free", 0)))
        self.lab_slots = self._whole(
            data.generators_by_id[LAB_SLOT].get("free", 0) + self.modifiers.additive("start_lab_slots")
        )
        self.tool_level = 0.0
        self.prep_progress = 0.0
        self.pieces: Dict[str, List[float]] = {}  # Schluessel -> Zonen
        self.spare_zones: Dict[Tuple[str, str], float] = {}
        self.lab_queue: List[Tuple[str, List[float]]] = []
        self.lab_jobs: List[List[Any]] = []  # [Schluessel, Zonen, Restzeit]
        self._pieces_version = 0
        self._ranking_key: Optional[Tuple[Any, ...]] = None
        self._ranking_income = 0.0

        # KI-Zustand
        self.seconds = 0.0
        self.duplicates = 0
        self.layer_unlocked_at: Dict[str, float] = {first: 0.0}
        self.first_skeleton_at: Optional[float] = None
        self._dig_tap_budget = 0.0
        self._prep_tap_budget = 0.0
        self._earned = 0.0
        self._earned_at_snapshot = 0.0
        self._seconds_at_snapshot = 0.0
        self._lab_completed = False
        self._missing_bone_weight = float(b["missingBoneWeight"]) if self.options.bad_luck_protection else EXISTING_BONE_WEIGHT

    @staticmethod
    def _whole(value: float) -> float:
        return max(0.0, math.floor(value + ROUNDING_TOLERANCE))

    def _event(self, text: str) -> None:
        if self.on_event:
            self.on_event(self.seconds, text)

    # --- Modifier-Quellen (ModifierSources.sync) ---

    def sync(self) -> None:
        wanted_by_provider: List[Tuple[str, Dict[str, List[Modifier]]]] = [
            ("upgrade:", self._upgrade_sources()),
            ("skill:", {"skill:" + s: [to_modifier(m) for m in self.data.skills_by_id[s]["effects"]] for s in self.skills}),
            ("book:", self._book_sources_now() if self.options.book_bonus else {}),
            ("milestone:", self._milestone_sources()),
        ]
        engine = self.modifiers
        existing = list(engine.sources.keys())
        for prefix, wanted in wanted_by_provider:
            for source_id in existing:
                if source_id.startswith(prefix) and source_id not in wanted:
                    engine.remove_source(source_id)
            for source_id, mods in wanted.items():
                if engine.sources.get(source_id) != mods:
                    engine.set_source(source_id, mods)

    def _upgrade_sources(self) -> Dict[str, List[Modifier]]:
        result: Dict[str, List[Modifier]] = {}
        counts = {PREPARATOR: getattr(self, "preparators", 0.0), HALL: getattr(self, "halls", 0.0),
                  LAB_SLOT: getattr(self, "lab_slots", 0.0)}
        for generator in self.data.generators:
            effects = generator.get("effects") or []
            count = counts.get(generator["id"], 0.0)
            if effects and count > 0.0:
                result["upgrade:" + generator["id"]] = [
                    (m[0], m[1], m[2] * count, m[3]) for m in (to_modifier(e) for e in effects)
                ]
        level = getattr(self, "tool_level", 0.0)
        for upgrade in self.data.upgrades:
            effects = upgrade.get("effectsPerLevel") or []
            if effects and level > 0.0 and upgrade["id"] == TOOLS:
                result["upgrade:" + upgrade["id"]] = [
                    (m[0], m[1], m[2] * level, m[3]) for m in (to_modifier(e) for e in effects)
                ]
        return result

    def _book_sources_now(self) -> Dict[str, List[Modifier]]:
        if self._synced_book_version == self._book_version:
            return self._book_sources
        result: Dict[str, List[Modifier]] = {}
        for species in self.data.species:
            min_index = None
            for bone in species["bones"]:
                q = self.book.get(piece_key(species["id"], bone))
                if q is None:
                    min_index = None
                    break
                idx = self.data.tier_index(q)
                min_index = idx if min_index is None else min(min_index, idx)
            if min_index is not None and species["bones"]:
                bonus = species["bookBonus"]
                result["book:" + species["id"]] = [
                    (bonus["stat"], bonus["op"], float(bonus["value"]) * self.data.tier_factor(min_index), "book")
                ]
            if species["id"] in self.researched:
                result["book:researched:" + species["id"]] = [self.data.researched_bonus]
        self._book_sources = result
        self._synced_book_version = self._book_version
        return result

    def _milestone_sources(self) -> Dict[str, List[Modifier]]:
        by_id = {m["id"]: m for m in self.data.milestones}
        result: Dict[str, List[Modifier]] = {}
        for milestone_id in self.milestones_done:
            effects = by_id.get(milestone_id, {}).get("reward", {}).get("effects") or []
            if effects:
                result["milestone:" + milestone_id] = [to_modifier(e) for e in effects]
        return result

    # --- Abfragen ---

    def unlocked_layers(self) -> List[Dict[str, Any]]:
        return self.data.layers[: self.unlocked]

    def blocks_per_second(self, layer: Dict[str, Any]) -> float:
        teams = self.teams.get(layer["id"], 0.0)
        if teams <= 0.0:
            return 0.0
        reached = sum(1 for m in self.data.balance["teamMilestones"] if teams >= m)
        mult = math.pow(float(self.data.balance["teamMilestoneFactor"]), reached)
        rate = self.modifiers.value("dig_rate", float(layer["teamRate"]))
        return teams * (rate * mult)

    def prep_rate(self) -> float:
        return max(0.0, self.modifiers.additive("prep_rate"))

    def complete_species(self) -> List[str]:
        present: Dict[str, set] = {}
        for key in self.pieces:
            species_id, bone_id = key.split(":", 2)[:2]
            present.setdefault(species_id, set()).add(bone_id)
        return [s["id"] for s in self.data.species
                if s["bones"] and s["id"] in present and present[s["id"]].issuperset(s["bones"])]

    def complete_species_in_book(self) -> int:
        count = 0
        for s in self.data.species:
            if s["bones"] and all(piece_key(s["id"], b) in self.book for b in s["bones"]):
                count += 1
        return count

    def museum_income(self) -> float:
        skeleton_factor = self.modifiers.value("skeleton_bonus", float(self.data.balance["skeletonMult"]))
        key = (self._pieces_version, self.halls, skeleton_factor)
        if key != self._ranking_key:
            complete = set(self.complete_species())
            slots = max(0.0, math.floor(self.halls)) * self.data.balance["slotsPerHall"]
            slot_count = int(min(slots, float(len(self.pieces))))
            ranked = []
            for k, zones in self.pieces.items():
                species_id = key_species(k)
                layer = self.data.layer_of_species(species_id)
                factor = skeleton_factor if species_id in complete else 1.0
                ranked.append((layer["value"] * (QUALITY_OFFSET + kotlin_average(zones)) * factor, k))
            ranked.sort(key=lambda item: (-item[0], item[1]))
            self._ranking_income = kotlin_sum(v for v, _ in ranked[:slot_count])
            self._ranking_key = key
        return self._ranking_income * self.modifiers.factor("museum_income")

    def fame_preview(self) -> float:
        """Ruhm bei Neustart (``Prestige.fameGainPreview``); vorher abgleichen."""
        self.sync()
        complete = self.complete_species()
        if not complete:
            return 0.0
        total = 0.0
        for species_id in complete:
            species = self.data.species_by_id[species_id]
            avg = kotlin_average([kotlin_average(self.pieces[piece_key(species_id, b)]) for b in species["bones"]])
            total += self.data.layers_by_id[species["layer"]]["fameWeight"] * (QUALITY_OFFSET + avg)
        return max(0.0, math.floor(total * self.modifiers.factor("fame_gain")))

    # --- Grabung ---

    def tap_dig(self, layer_id: str) -> None:
        if layer_id in self.teams:
            self.blocks[layer_id] = self.blocks.get(layer_id, 0.0) + self.data.balance["tapDigAmount"]

    def _dig(self, dt: float) -> None:
        for layer in self.unlocked_layers():
            produced = self.blocks_per_second(layer) * dt
            if produced <= 0.0:
                continue
            self.blocks[layer["id"]] = self.blocks.get(layer["id"], 0.0) + produced

    # --- Praeparation (PrepSystem) ---

    @staticmethod
    def _whole_count(value: float) -> int:
        if value <= 0.0:
            return 0
        return int(math.floor(value + ROUNDING_TOLERANCE))

    def _fossil_chance(self) -> float:
        find = min(1.0, max(0.0, self.modifiers.value("find_chance", float(self.data.balance["findChance"]))))
        guaranteed = min(1.0, max(0.0, self.modifiers.additive("guaranteed_fossil_chance")))
        return min(1.0, max(0.0, 1.0 - (1.0 - find) * (1.0 - guaranteed)))

    def _process(self, amount: float) -> None:
        budget = self.prep_progress + amount
        if amount <= 0.0 and budget < 1.0:
            return
        remaining = self._whole_count(budget)
        processed = 0.0
        for layer in self.data.layers_deepest_first:
            if remaining <= 0:
                break
            available = self._whole_count(self.blocks.get(layer["id"], 0.0))
            count = min(remaining, available)
            if count <= 0:
                continue
            self._process_layer(layer, count)
            remaining -= count
            processed += float(count)
        rest = budget - processed
        self.prep_progress = min(MAX_PROGRESS, max(0.0, rest - math.floor(rest + ROUNDING_TOLERANCE)))

    def _process_layer(self, layer: Dict[str, Any], count: int) -> None:
        stock = self.blocks.get(layer["id"], 0.0)
        self.blocks[layer["id"]] = max(0.0, stock - float(count))
        chance = self._fossil_chance()
        has_species = bool(self.data.species_by_layer.get(layer["id"]))
        rocks = 0
        for _ in range(count):
            is_fossil = self.rng.next_double() < chance
            find = self._roll_find(layer) if (is_fossil and has_species) else None
            if find is None:
                rocks += 1
                continue
            key, zones = find
            quality = kotlin_average(zones)
            self.pieces_found += 1.0
            if self.data.tier_index(quality) == len(self.data.tiers) - 1:
                self.pristine_found += 1.0
            self._on_find(key, zones, quality)
        if rocks > 0:
            self.money += float(rocks) * (layer["value"] * self.data.balance["rockSellFactor"])

    @staticmethod
    def _weighted_index(weights: List[float], rng: KotlinRandom) -> int:
        total = kotlin_sum(weights)
        roll = rng.next_double() * total
        cumulative = 0.0
        for i, w in enumerate(weights):
            cumulative += w
            if roll < cumulative:
                return i
        return max(i for i, w in enumerate(weights) if w > 0.0)

    def _roll_find(self, layer: Dict[str, Any]) -> Optional[Tuple[str, List[float]]]:
        candidates = self.data.species_by_layer.get(layer["id"], [])
        if not candidates:
            return None
        species = candidates[self._weighted_index([float(s["rarityWeight"]) for s in candidates], self.rng)]
        bones = species["bones"]
        weights = [EXISTING_BONE_WEIGHT if piece_key(species["id"], b) in self.pieces else self._missing_bone_weight
                   for b in bones]
        bone_id = bones[self._weighted_index(weights, self.rng)]
        no_damage = min(1.0, max(0.0, self.modifiers.additive("no_damage_chance")))
        undamaged = no_damage > 0.0 and self.rng.next_double() < no_damage
        b = self.data.balance
        base_max = float(b["qualityBaseMax"])
        base_min = min(self.modifiers.value("quality_base_min", float(b["qualityBaseMin"])), base_max)
        fragility = max(0.0, layer["fragility"] - self.modifiers.additive("damage_reduction"))
        zones = []
        for _ in self.data.bones_by_id[bone_id]["zones"]:
            base = self.rng.next_double_range(base_min, base_max)
            damage = 0.0 if undamaged else fragility * self.rng.next_double_range(
                float(b["damageRollMin"]), float(b["damageRollMax"]))
            zones.append(min(1.0, max(0.0, base - damage)))
        return piece_key(species["id"], bone_id), zones

    # --- Funde einordnen (FindClassifier) ---

    def _on_find(self, key: str, zones: List[float], quality: float) -> None:
        entry = self.book.get(key)
        if entry is None or quality > entry:
            self.book[key] = quality
            self._book_version += 1
        existing = self.pieces.get(key)
        if existing is None:
            self._set_piece(key, zones)
            return
        if quality > kotlin_average(existing):
            self._set_piece(key, zones)
            self._handle_duplicate(key, existing)
        else:
            self._handle_duplicate(key, zones)

    def _set_piece(self, key: str, zones: List[float]) -> None:
        was_complete = None
        if key not in self.pieces:
            was_complete = len(self.complete_species())
        self.pieces[key] = zones
        self._pieces_version += 1
        if was_complete is not None and len(self.complete_species()) > was_complete:
            self._event(f"Skelett komplett: {key_species(key)} ({len(self.complete_species())} gesamt)")

    def _handle_duplicate(self, key: str, zones: List[float]) -> None:
        self.duplicates += 1
        if float(len(self.lab_queue)) < self.lab_slots * self.data.balance["labQueuePerSlot"]:
            self.lab_queue.append((key, zones))
            return
        layer = self.data.layer_of_species(key_species(key))
        value = (layer["value"] * (QUALITY_OFFSET + kotlin_average(zones)) * self.data.balance["duplicateSellFactor"]
                 * self.modifiers.factor("sell_price"))
        self.money += value

    # --- Museum ---

    def _museum(self, dt: float) -> None:
        income = self.museum_income()
        if income != 0.0:
            self.money += income * dt

    # --- Labor (LabSystem) ---

    def _job_duration(self) -> float:
        return self.data.balance["labDuration"] * self.modifiers.factor("lab_speed")

    def _lab(self, dt: float) -> None:
        slots = int(max(0.0, math.floor(self.lab_slots)))
        if not self.lab_jobs and (not self.lab_queue or slots == 0):
            return
        time_left = dt
        while True:
            free = slots - len(self.lab_jobs)
            if free > 0 and self.lab_queue:
                starting = self.lab_queue[:free]
                del self.lab_queue[:free]
                duration = self._job_duration()
                self.lab_jobs.extend([key, zones, duration] for key, zones in starting)
            if not self.lab_jobs or time_left <= 0.0:
                break
            step = min(time_left, min(job[2] for job in self.lab_jobs))
            time_left -= step
            finished, running = [], []
            for job in self.lab_jobs:
                job[2] -= step
                (finished if job[2] <= DONE_EPSILON else running).append(job)
            self.lab_jobs = running
            for job in finished:
                self._complete_job(job[0], job[1])
            if not finished:
                break

    def _complete_job(self, key: str, zones: List[float]) -> None:
        species_id, bone_id = key.split(":", 2)[:2]
        layer = self.data.layer_of_species(species_id)
        points = float(layer["order"] + 1) * self.modifiers.factor("research_points")
        for index, zone_id in enumerate(self.data.bones_by_id[bone_id]["zones"]):
            if index >= len(zones):
                continue
            stored = self.spare_zones.get((key, zone_id))
            if stored is None or zones[index] > stored:
                self.spare_zones[(key, zone_id)] = zones[index]
        self.research_completed += 1.0
        if species_id not in self.researched:
            self.researched.append(species_id)
            self._book_version += 1
        self.research_points += points
        self._lab_completed = True

    # --- Rekonstruktion (Reconstruction) ---

    def _reconstruction_preview(self, key: str) -> Optional[Tuple[List[float], float, bool, bool]]:
        species_id, bone_id = key.split(":", 2)[:2]
        bone = self.data.bones_by_id.get(bone_id)
        if bone is None or not bone["zones"]:
            return None
        allowed = int(max(0.0, math.floor(self.modifiers.additive("reconstruction_missing_zones"))))
        missing = 0
        raw = []
        for zone_id in bone["zones"]:
            stored = self.spare_zones.get((key, zone_id))
            if stored is None:
                missing += 1
                stored = float(self.data.balance["reconstructionMissingZoneQuality"])
            raw.append(stored)
        if missing > allowed or missing == len(bone["zones"]):
            return None
        mean = kotlin_average(raw)
        cap = float(self.data.balance["reconstructionCap"])
        zones = [z * (cap / mean) for z in raw] if mean > cap else raw
        quality = kotlin_average(zones)
        run_piece = self.pieces.get(key)
        book_entry = self.book.get(key)
        improves_run = run_piece is None or quality > kotlin_average(run_piece)
        improves_book = book_entry is None or quality > book_entry
        return zones, quality, improves_run, improves_book

    def reconstruct_all(self) -> None:
        self.sync()
        keys = sorted({key for key, _ in self.spare_zones})
        previews = [(k, self._reconstruction_preview(k)) for k in keys]
        for key, preview in previews:
            if preview is None or not (preview[2] or preview[3]):
                continue
            self.sync()
            current = self._reconstruction_preview(key)
            if current is None or not (current[2] or current[3]):
                continue
            zones, quality, improves_run, improves_book = current
            self.reconstructions += 1.0
            if improves_book:
                self.book[key] = quality
                self._book_version += 1
            if improves_run:
                existing = self.pieces.get(key)
                self._set_piece(key, zones)
                if existing is not None:
                    self._handle_duplicate(key, existing)
            self._check_milestones()

    # --- Meilensteine (MilestoneSystem) ---

    def _counter(self, kind: str) -> float:
        if kind == "pieces_found":
            return self.pieces_found
        if kind == "skeletons_completed":
            return float(self.complete_species_in_book())
        if kind == "pristine_found":
            return self.pristine_found
        if kind == "teams_total":
            return kotlin_sum(self.teams.values())
        if kind == "layers_unlocked":
            return float(self.unlocked)
        if kind == "research_completed":
            return self.research_completed
        if kind == "reconstructions":
            return self.reconstructions
        if kind == "prestiges":
            return 0.0
        raise ValueError(f"Unbekannter Meilenstein-Typ: {kind}")

    def _check_milestones(self) -> None:
        if not self.options.milestones:
            return
        counters: Dict[str, float] = {}
        for milestone in self.data.milestones:
            if milestone["id"] in self.milestones_done:
                continue
            condition = milestone["condition"]
            if condition["type"] not in counters:
                counters[condition["type"]] = self._counter(condition["type"])
            if counters[condition["type"]] < condition["value"]:
                continue
            reward = milestone.get("reward", {})
            self.milestones_done.append(milestone["id"])
            self.money += float(reward.get("money", 0.0))
            self.research_points += float(reward.get("researchPoints", 0.0))
            self._event(f"Meilenstein {milestone['id']}")

    # --- Engine-Aktionen ---

    def advance(self, dt: float) -> None:
        if dt <= 0.0:
            return
        self.sync()
        self._dig(dt)
        self._process(self.prep_rate() * dt)
        self._museum(dt)
        self._lab(dt)
        self._check_milestones()

    def tap_prep(self) -> None:
        self.sync()
        self._process(float(self.data.balance["tapPrepAmount"]))
        self._check_milestones()

    # --- Kauf-KI (GreedyBot) ---

    @property
    def active(self) -> bool:
        return self.seconds < self.bot.active_seconds

    def step(self) -> None:
        bot = self.bot
        if self.active:
            deepest = self.data.layers[self.unlocked - 1]["id"]
            self._dig_tap_budget += bot.dig_taps_per_second * bot.tick_seconds
            self._prep_tap_budget += bot.prep_taps_per_second * bot.tick_seconds
            while self._dig_tap_budget >= 1.0 - EPSILON:
                self.tap_dig(deepest)
                self._dig_tap_budget -= 1.0
            while self._prep_tap_budget >= 1.0 - EPSILON:
                self._tracked(self.tap_prep)
                self._prep_tap_budget -= 1.0
        self._lab_completed = False
        self._tracked(lambda: self.advance(bot.tick_seconds))
        self.seconds += bot.tick_seconds
        if self.options.reconstruction and self._lab_completed:
            self.reconstruct_all()
        self._buy_tools()
        self._buy_loop()
        if self.first_skeleton_at is None and self.complete_species():
            self.first_skeleton_at = self.seconds

    def _tracked(self, action: Callable[[], None]) -> None:
        before = self.money
        action()
        if self.money > before:
            self._earned += self.money - before

    def _buy_tools(self) -> None:
        self.sync()
        upgrade = self.data.upgrades_by_id[TOOLS]
        cap = None
        if upgrade.get("maxLevel") is not None:
            cap = max(0.0, float(upgrade["maxLevel"]) - self.tool_level)
            if cap <= 0.0:
                return
        if upgrade.get("currency", "research_points") != "research_points":
            raise ValueError("Werkzeugstufen werden in der Simulation mit FP bezahlt")
        base, growth = float(upgrade["baseCost"]), float(upgrade["growth"])
        quantity = max_affordable(base, growth, self.tool_level, self.research_points, cap)
        if quantity <= 0.0:
            return
        self.research_points -= cost_raw(base, growth, self.tool_level, quantity)
        self.tool_level += quantity

    def _generator_curve(self, generator_id: str, owned: float, cost_factor: float) -> Tuple[float, float, float]:
        generator = self.data.generators_by_id[generator_id]
        paid = max(0.0, owned - float(generator.get("free", 0)))
        return float(generator["baseCost"]) * cost_factor, float(generator["growth"]), paid

    def _options(self) -> List[Tuple[float, float, str, Optional[str]]]:
        bot = self.bot
        options: List[Tuple[float, float, str, Optional[str]]] = []
        open_layers = self.unlocked_layers()
        for layer in open_layers:
            price = price_raw(float(layer["teamBaseCost"]), float(layer["teamGrowth"]), self.teams.get(layer["id"], 0.0))
            options.append((price, price, "team", layer["id"]))
        if self.unlocked < len(self.data.layers):
            nxt = self.data.layers[self.unlocked]
            price = nxt["unlockCost"] * self.modifiers.factor("layer_unlock_cost")
            options.append((price, price, "layer", nxt["id"]))
        produced = kotlin_sum(self.blocks_per_second(layer) for layer in open_layers)
        if self.active:
            produced += bot.dig_taps_per_second * self.data.balance["tapDigAmount"]
        if self.prep_rate() < produced * bot.prep_capacity_factor:
            base, growth, paid = self._generator_curve(PREPARATOR, self.preparators, 1.0)
            price = price_raw(base, growth, paid)
            options.append((price * bot.prep_price_weight, price, "prep", None))
        slots = math.floor(self.halls) * self.data.balance["slotsPerHall"]
        if len(self.pieces) > slots * bot.hall_fill_threshold:
            base, growth, paid = self._generator_curve(HALL, self.halls, self.modifiers.factor("hall_cost"))
            price = price_raw(base, growth, paid)
            options.append((price, price, "hall", None))
        if self.duplicates > bot.duplicates_per_lab_slot * self.lab_slots:
            base, growth, paid = self._generator_curve(LAB_SLOT, self.lab_slots, 1.0)
            price = price_raw(base, growth, paid)
            options.append((price, price, "lab", None))
        return options

    def _buy_loop(self) -> None:
        while True:
            self.sync()
            options = self._options()
            if not options:
                return
            _, price, kind, layer_id = min(options, key=lambda o: o[0])
            if price > self.money:
                return
            self.money -= price
            if kind == "team":
                self.teams[layer_id] = self.teams.get(layer_id, 0.0) + 1.0
            elif kind == "layer":
                self.unlocked += 1
                self.teams.setdefault(layer_id, 0.0)
                self.blocks.setdefault(layer_id, 0.0)
                self.layer_unlocked_at.setdefault(layer_id, self.seconds)
                self._event(f"Schicht {layer_id} freigeschaltet")
            elif kind == "prep":
                self.preparators += 1.0
            elif kind == "hall":
                self.halls += 1.0
            elif kind == "lab":
                self.lab_slots += 1.0

    # --- Verlauf ---

    def snapshot(self) -> Snapshot:
        self.sync()
        window = self.seconds - self._seconds_at_snapshot
        rate = (self._earned - self._earned_at_snapshot) / window if window > 0.0 else 0.0
        self._earned_at_snapshot = self._earned
        self._seconds_at_snapshot = self.seconds
        return Snapshot(
            seconds=self.seconds,
            money=self.money,
            museum_income=self.museum_income(),
            earned_per_second=rate,
            pieces=len(self.pieces),
            skeletons=len(self.complete_species()),
            fame=self.fame_preview(),
            teams=[self.teams.get(l["id"], 0.0) for l in self.unlocked_layers()],
            preparators=self.preparators,
            halls=self.halls,
            lab_slots=self.lab_slots,
            tool_level=self.tool_level,
            duplicates=self.duplicates,
            unlocked_layers=self.unlocked,
            research_points=self.research_points,
        )

    def run(self, until_seconds: float, snapshot_every: float) -> List[Snapshot]:
        snapshots: List[Snapshot] = []
        next_snapshot = snapshot_every
        while self.seconds < until_seconds - EPSILON:
            self.step()
            if self.seconds >= next_snapshot - EPSILON:
                snapshots.append(self.snapshot())
                next_snapshot += snapshot_every
        return snapshots


# --------------------------------------------------------------------------------------------
# Ausgabe
# --------------------------------------------------------------------------------------------


@dataclass
class SeedResult:
    seed: int
    snapshots: List[Snapshot]
    layer_unlocked_at: Dict[str, float]
    first_skeleton_at: Optional[float]
    final_fame: float


def simulate(raw_data: Dict[str, Any], seed: int, minutes: float, interval: float,
             options: Optional[SimOptions] = None, bot: Optional[BotConfig] = None,
             on_event: Optional[Callable[[float, str], None]] = None) -> SeedResult:
    """Simuliert einen Durchlauf und gibt Verlauf und Kennzahlen zurueck."""
    sim = Simulation(GameData(raw_data), seed, options, bot, on_event)
    snapshots = sim.run(minutes * 60.0, interval * 60.0)
    return SeedResult(seed, snapshots, dict(sim.layer_unlocked_at), sim.first_skeleton_at, sim.fame_preview())


CSV_FIELDS = ["seed", "minute", "money", "museum_income", "earned_per_second", "pieces", "skeletons", "fame",
              "teams", "preparators", "halls", "lab_slots", "tool_level", "research_points", "duplicates",
              "unlocked_layers"]


def snapshot_row(seed: int, s: Snapshot) -> Dict[str, Any]:
    return {
        "seed": seed,
        "minute": round(s.seconds / 60.0, 3),
        "money": round(s.money, 2),
        "museum_income": round(s.museum_income, 3),
        "earned_per_second": round(s.earned_per_second, 3),
        "pieces": s.pieces,
        "skeletons": s.skeletons,
        "fame": int(s.fame),
        "teams": "/".join(str(int(t)) for t in s.teams),
        "preparators": int(s.preparators),
        "halls": int(s.halls),
        "lab_slots": int(s.lab_slots),
        "tool_level": int(s.tool_level),
        "research_points": round(s.research_points, 2),
        "duplicates": s.duplicates,
        "unlocked_layers": s.unlocked_layers,
    }


def format_table(result: SeedResult) -> str:
    header = (f"{'min':>5} {'Geld':>11} {'Museum/s':>9} {'Zufluss/s':>9} {'Funde':>5} {'Skel':>4} {'Ruhm':>4} "
              f"{'Teams':>11} {'Prep':>4} {'Hall':>4} {'Labs':>4} {'Werkz':>5} {'Dups':>5}")
    lines = [header, "-" * len(header)]
    for s in result.snapshots:
        teams = "/".join(str(int(t)) for t in s.teams)
        lines.append(
            f"{s.seconds / 60.0:5.0f} {s.money:11.0f} {s.museum_income:9.1f} {s.earned_per_second:9.1f} "
            f"{s.pieces:5d} {s.skeletons:4d} {s.fame:4.0f} {teams:>11} {s.preparators:4.0f} {s.halls:4.0f} "
            f"{s.lab_slots:4.0f} {s.tool_level:5.0f} {s.duplicates:5d}"
        )
    return "\n".join(lines)


def format_minutes(seconds: Optional[float]) -> str:
    """Minuten mit einer Nachkommastelle, kaufmaennisch gerundet wie ``"%.1f".format`` in Kotlin."""
    if seconds is None:
        return "-"
    minutes = Decimal(repr(seconds / 60.0)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return f"{minutes} min"


def format_summary(result: SeedResult, data: GameData) -> str:
    unlocks = [f"{layer['id']} {format_minutes(result.layer_unlocked_at.get(layer['id']))}" for layer in data.layers[1:]]
    skeleton = format_minutes(result.first_skeleton_at)
    return (f"Seed {result.seed}: " + ", ".join(unlocks) + f", erstes Skelett {skeleton}, "
            f"Ruhm am Ende {result.final_fame:.0f}")


def parse_seeds(text: str) -> List[int]:
    seeds: List[int] = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part[1:]:
            lo, hi = part.split("-", 1)
            seeds.extend(range(int(lo), int(hi) + 1))
        else:
            seeds.append(int(part))
    if not seeds:
        raise argparse.ArgumentTypeError("mindestens ein Seed")
    return seeds


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Balancing-Simulation Fossil Collector (erster Durchlauf, gierige Kauf-KI wie PacingTest).")
    p.add_argument("seed", nargs="?", type=int, help="einzelner Seed (Kurzform, wie frueher)")
    p.add_argument("--seeds", type=parse_seeds, help="Seeds, z. B. 1,2,3 oder 1-5 (Standard 1)")
    p.add_argument("--minutes", type=float, default=60.0, help="Dauer in Minuten (Standard 60)")
    p.add_argument("--interval", type=float, default=5.0, help="Ausgabe-Intervall in Minuten (Standard 5)")
    p.add_argument("--data", default=DEFAULT_DATA_DIR, help="Datenordner (Standard core/src/main/resources/data)")
    p.add_argument("--csv", metavar="DATEI", help="Verlauf zusaetzlich als CSV schreiben ('-' = Standardausgabe)")
    p.add_argument("--set", action="append", default=[], metavar="PFAD=WERT",
                   help="Datenwert nur fuer diesen Lauf ueberschreiben, z. B. layers.neogene.unlockCost=8000 "
                        "oder balance.bookTierFactor=[1,1.5,2,3,4] (mehrfach moeglich)")
    p.add_argument("--no-book-bonus", action="store_true", help="Buch-Boni und researchedBonus aus")
    p.add_argument("--no-milestones", action="store_true", help="Meilensteine (Belohnungen) aus")
    p.add_argument("--no-bad-luck-protection", action="store_true", help="Pech-Schutz aus (missingBoneWeight wie 1)")
    p.add_argument("--no-reconstruction", action="store_true", help="KI rekonstruiert nicht")
    p.add_argument("--skills", default="", help="gekaufte Skills, Komma-getrennt (z. B. dig_1,dig_2,dig_3)")
    p.add_argument("--active-minutes", type=float, default=None,
                   help="so lange tippt der simulierte Spieler (Standard 10)")
    p.add_argument("--tick", type=float, default=None, help="Schrittweite in Sekunden (Standard 1)")
    p.add_argument("-v", "--verbose", action="store_true", help="Ereignisse (Schichten, Skelette, Meilensteine) ausgeben")
    p.add_argument("-q", "--quiet", action="store_true", help="nur Zusammenfassung ausgeben")
    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.minutes <= 0 or args.interval <= 0 or (args.tick is not None and args.tick <= 0):
        parser.error("--minutes, --interval und --tick muessen > 0 sein")
    raw = load_data(args.data)
    try:
        for assignment in args.set:
            apply_override(raw, assignment)
    except ValueError as error:
        parser.error(str(error))
    data = GameData(raw)
    skills = tuple(s.strip() for s in args.skills.split(",") if s.strip())
    unknown = [s for s in skills if s not in data.skills_by_id]
    if unknown:
        parser.error(f"Unbekannte Skills: {', '.join(unknown)}")
    options = SimOptions(
        book_bonus=not args.no_book_bonus,
        milestones=not args.no_milestones,
        bad_luck_protection=not args.no_bad_luck_protection,
        reconstruction=not args.no_reconstruction,
        skills=skills,
    )
    bot = BotConfig()
    if args.active_minutes is not None:
        bot.active_seconds = args.active_minutes * 60.0
    if args.tick is not None:
        bot.tick_seconds = args.tick
    seeds = args.seeds or ([args.seed] if args.seed is not None else [1])
    # Bei CSV auf der Standardausgabe gehen die Texte nach stderr.
    text_out = sys.stderr if args.csv == "-" else sys.stdout

    def say(text: str = "") -> None:
        print(text, file=text_out)

    results: List[SeedResult] = []
    for seed in seeds:
        log = None
        if args.verbose and not args.quiet:
            def log(seconds: float, text: str) -> None:
                say(f"  [{int(seconds) // 60:3d}:{int(seconds) % 60:02d}] {text}")
        if not args.quiet:
            say(f"Seed {seed}")
        result = simulate(copy.deepcopy(raw), seed, args.minutes, args.interval, options, bot, log)
        results.append(result)
        if not args.quiet:
            say(format_table(result))
            say()

    say("Zusammenfassung")
    for result in results:
        say("  " + format_summary(result, data))

    if args.csv:
        out = sys.stdout if args.csv == "-" else open(args.csv, "w", newline="", encoding="utf-8")
        try:
            writer = csv.DictWriter(out, fieldnames=CSV_FIELDS)
            writer.writeheader()
            for result in results:
                for s in result.snapshots:
                    writer.writerow(snapshot_row(result.seed, s))
        finally:
            if out is not sys.stdout:
                out.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
