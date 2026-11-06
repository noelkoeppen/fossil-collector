#!/usr/bin/env python3
"""Tests fuer tools/sim/fossil_sim.py (nur Standardbibliothek): python3 tools/sim/test_fossil_sim.py

Die Tests pruefen Funktion und Determinismus mit den echten JSON-Daten, aber keine konkreten
Balancing-Ergebnisse (die aendern sich mit AP-29). Der Vergleich mit dem Kotlin-PacingTest steht
in tools/sim/README.md.
"""

import contextlib
import copy
import csv
import io
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fossil_sim  # noqa: E402

SHORT_MINUTES = 15.0
INTERVAL = 5.0


def bits(value):
    return struct.unpack("<q", struct.pack("<d", value))[0]


class KotlinRandomTest(unittest.TestCase):
    # Erzeugt mit SeededRandom(seed) in core (kotlin.random.Random): nextDouble(), nextDouble(),
    # nextDouble(0.3, 1.0), nextDouble(0.5, 2.0), als Bitmuster (Double.toBits()).
    KOTLIN_REFERENCE = {
        1: [4594202224818004148, 4606076761292517787, 4606387582285392484, 4611423652585181039],
        42: [4597321887070544904, 4606326345915054478, 4602489843109665034, 4611486667048333388],
        -7: [4592945291025193720, 4604627794073849227, 4606150776781980194, 4606923584331348094],
        123456789012345: [4602164294972699566, 4592059986196110840, 4606557267300101910, 4608242005414693632],
    }

    def test_sequence_matches_kotlin(self):
        for seed, expected in self.KOTLIN_REFERENCE.items():
            rng = fossil_sim.KotlinRandom(seed)
            actual = [
                bits(rng.next_double()),
                bits(rng.next_double()),
                bits(rng.next_double_range(0.3, 1.0)),
                bits(rng.next_double_range(0.5, 2.0)),
            ]
            self.assertEqual(expected, actual, f"Seed {seed}")

    def test_range_with_equal_bounds_returns_bound(self):
        self.assertEqual(0.4, fossil_sim.KotlinRandom(1).next_double_range(0.4, 0.4))


class DataTest(unittest.TestCase):
    def test_loads_all_files(self):
        raw = fossil_sim.load_data()
        self.assertEqual(set(fossil_sim.DATA_FILES), set(raw))
        data = fossil_sim.GameData(raw)
        self.assertGreater(len(data.layers), 1)
        self.assertGreater(len(data.species), 0)

    def test_override_by_id_and_key(self):
        raw = fossil_sim.load_data()
        layer_id = raw["layers"][1]["id"]
        fossil_sim.apply_override(raw, f"layers.{layer_id}.unlockCost=1234")
        fossil_sim.apply_override(raw, "balance.bookTierFactor=[1, 1.5, 2, 3, 4]")
        self.assertEqual(1234, next(l for l in raw["layers"] if l["id"] == layer_id)["unlockCost"])
        self.assertEqual([1, 1.5, 2, 3, 4], raw["balance"]["bookTierFactor"])

    def test_override_rejects_unknown_paths(self):
        raw = fossil_sim.load_data()
        for bad in ("foo.bar=1", "balance.noSuchValue=1", "layers.no_such_layer.value=1", "balance.findChance"):
            with self.assertRaises(ValueError, msg=bad):
                fossil_sim.apply_override(raw, bad)


class SimulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = fossil_sim.load_data()

    def run_sim(self, seed=1, options=None, raw=None, minutes=SHORT_MINUTES):
        return fossil_sim.simulate(copy.deepcopy(raw or self.raw), seed, minutes, INTERVAL, options)

    def test_runs_with_real_data(self):
        result = self.run_sim()
        self.assertEqual(int(SHORT_MINUTES / INTERVAL), len(result.snapshots))
        last = result.snapshots[-1]
        self.assertAlmostEqual(SHORT_MINUTES * 60.0, last.seconds)
        self.assertGreater(last.pieces, 0)
        self.assertGreater(last.museum_income, 0.0)
        self.assertGreater(sum(last.teams), 0.0)
        for s in result.snapshots:
            self.assertGreaterEqual(s.money, 0.0)
            self.assertGreaterEqual(s.fame, 0.0)

    def test_same_seed_same_result(self):
        self.assertEqual(self.run_sim(seed=2), self.run_sim(seed=2))

    def test_different_seeds_differ(self):
        self.assertNotEqual(self.run_sim(seed=1).snapshots, self.run_sim(seed=2).snapshots)

    def test_values_come_from_data(self):
        raw = copy.deepcopy(self.raw)
        fossil_sim.apply_override(raw, "balance.findChance=0")
        result = self.run_sim(raw=raw)
        self.assertEqual(0, result.snapshots[-1].pieces)

    def test_milestones_can_be_disabled(self):
        data = fossil_sim.GameData(copy.deepcopy(self.raw))
        on = fossil_sim.Simulation(data, 1)
        off = fossil_sim.Simulation(data, 1, fossil_sim.SimOptions(milestones=False))
        on.run(SHORT_MINUTES * 60.0, INTERVAL * 60.0)
        off.run(SHORT_MINUTES * 60.0, INTERVAL * 60.0)
        self.assertTrue(on.milestones_done)
        self.assertEqual([], off.milestones_done)

    def test_book_bonus_can_be_disabled(self):
        data = fossil_sim.GameData(copy.deepcopy(self.raw))
        sim = fossil_sim.Simulation(data, 1, fossil_sim.SimOptions(book_bonus=False))
        sim.run(SHORT_MINUTES * 60.0, INTERVAL * 60.0)
        self.assertFalse([s for s in sim.modifiers.sources if s.startswith("book:")])
        with_bonus = fossil_sim.Simulation(data, 1)
        with_bonus.run(SHORT_MINUTES * 60.0, INTERVAL * 60.0)
        self.assertTrue([s for s in with_bonus.modifiers.sources if s.startswith("book:")])

    def test_ablation_options_run(self):
        options = fossil_sim.SimOptions(bad_luck_protection=False, reconstruction=False, skills=("dig_1",))
        result = self.run_sim(options=options)
        self.assertEqual(int(SHORT_MINUTES / INTERVAL), len(result.snapshots))


class CliTest(unittest.TestCase):
    def test_csv_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "out.csv")
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = fossil_sim.main(["--seeds", "1,2", "--minutes", "10", "--interval", "5", "--csv", path])
            self.assertEqual(0, code)
            self.assertIn("Zusammenfassung", out.getvalue())
            with open(path, newline="", encoding="utf-8") as fh:
                rows = list(csv.DictReader(fh))
        self.assertEqual(4, len(rows))
        self.assertEqual(fossil_sim.CSV_FIELDS, list(rows[0].keys()))
        self.assertEqual(["1", "1", "2", "2"], [r["seed"] for r in rows])

    def test_seed_ranges(self):
        self.assertEqual([1, 2, 3, 7], fossil_sim.parse_seeds("1-3,7"))


if __name__ == "__main__":
    unittest.main()
