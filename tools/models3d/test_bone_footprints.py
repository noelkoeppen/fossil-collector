"""Tests fuer bone_footprints.py. Aufruf: python3 -m unittest discover -s tools/models3d -p 'test_*.py'"""

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bone_footprints  # noqa: E402

CELL = 48


def write_mask(path, mask):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(mask.astype(np.uint8) * 255, "L").convert("1").save(path)


class FootprintTest(unittest.TestCase):

    def test_exact_rectangle_gives_full_cells_without_offset(self):
        mask = np.ones((CELL, 2 * CELL), dtype=bool)
        rows, left, top, count, kept = bone_footprints.footprint(mask, CELL, 0.05, 4)
        self.assertEqual(["##"], rows)
        self.assertEqual((0, 0), (left, top))
        self.assertEqual(2, count)
        self.assertEqual(1.0, kept)

    def test_offset_is_chosen_to_minimise_cells(self):
        # 40 x 40 Pixel: in einer Zelle bei Verschiebung 0..8, sonst ueber vier Zellen verteilt.
        mask = np.ones((40, 40), dtype=bool)
        rows, left, top, count, _kept = bone_footprints.footprint(mask, CELL, 0.05, 4)
        self.assertEqual(["#"], rows)
        self.assertEqual(1, count)
        self.assertEqual((0, 0), (left, top))

    def test_small_bone_has_at_least_one_cell(self):
        mask = np.zeros((6, 6), dtype=bool)
        mask[2:4, 2:4] = True
        rows, _left, _top, count, kept = bone_footprints.footprint(mask, CELL, 0.5, 4)
        self.assertEqual(["#"], rows)
        self.assertEqual(1, count)
        self.assertEqual(1.0, kept)

    def test_cells_below_coverage_are_free_and_image_offset_matches(self):
        # L-Form: volle Zelle links oben, volle Zelle darunter, rechts oben nur ein schmaler Streifen.
        mask = np.zeros((2 * CELL, 2 * CELL), dtype=bool)
        mask[:, :CELL] = True
        mask[:4, CELL:] = True  # 4 / 48 = 8 % der Zelle
        rows, left, top, _count, _kept = bone_footprints.footprint(mask, CELL, 0.1, 4)
        self.assertEqual(["#", "#"], rows)
        self.assertEqual((0, 0), (left, top))
        rows_low, *_ = bone_footprints.footprint(mask, CELL, 0.05, 4)
        self.assertEqual(["##", "#."], rows_low)

    def test_rows_are_bounding_box_and_offset_relative_to_it(self):
        # Knochen in der Bildmitte: Bild ist groesser als der Knochen, imageLeft/Top werden negativ.
        mask = np.zeros((3 * CELL, 3 * CELL), dtype=bool)
        mask[CELL:2 * CELL, CELL:2 * CELL] = True
        rows, left, top, count, _kept = bone_footprints.footprint(mask, CELL, 0.05, 4)
        self.assertEqual(["#"], rows)
        self.assertEqual(1, count)
        self.assertEqual((-CELL, -CELL), (left, top))

    def test_empty_mask_is_an_error(self):
        with self.assertRaises(bone_footprints.FootprintError):
            bone_footprints.footprint(np.zeros((4, 4), dtype=bool), CELL, 0.05, 4)


class SpeciesTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = self.tmp.name
        self.assets = os.path.join(root, "assets")
        self.data = os.path.join(root, "data")
        os.makedirs(self.data)
        with open(os.path.join(self.data, "balance.json"), "w") as f:
            json.dump({"excavation": {"cellsPerUnit": 8}}, f)
        with open(os.path.join(self.data, "species.json"), "w") as f:
            json.dump([{"id": "toy", "bones": ["long", "dot"]}], f)
        masks = {
            "long": np.ones((CELL, 3 * CELL), dtype=bool),
            "dot": np.ones((10, 10), dtype=bool),
        }
        manifest = {"params": {"pxPerUnit": 384},
                    "bones": [{"id": k, "width": v.shape[1], "height": v.shape[0]} for k, v in masks.items()]}
        os.makedirs(os.path.join(self.assets, "toy"))
        with open(os.path.join(self.assets, "toy", "manifest.json"), "w") as f:
            json.dump(manifest, f)
        for bone_id, mask in masks.items():
            write_mask(os.path.join(self.assets, "toy", "masks", f"{bone_id}.png"), mask)

    def tearDown(self):
        self.tmp.cleanup()

    def run_main(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = bone_footprints.main(list(args) + ["--assets-dir", self.assets, "--data-dir", self.data])
        return code, out.getvalue()

    def test_writes_file_in_species_order_and_check_passes(self):
        code, _ = self.run_main()
        self.assertEqual(0, code)
        content = bone_footprints.read_json(bone_footprints.footprint_path("toy", self.data))
        self.assertEqual("toy", content["species"])
        self.assertEqual(8, content["cellsPerUnit"])
        self.assertEqual(384, content["pxPerUnit"])
        self.assertEqual(["long", "dot"], [b["id"] for b in content["bones"]])
        self.assertEqual(["###"], content["bones"][0]["rows"])
        self.assertEqual(["#"], content["bones"][1]["rows"])
        self.assertEqual(0, self.run_main("--check")[0])

    def test_cells_per_unit_comes_from_balance(self):
        with open(os.path.join(self.data, "balance.json"), "w") as f:
            json.dump({"excavation": {"cellsPerUnit": 4}}, f)
        self.assertEqual(0, self.run_main()[0])
        content = bone_footprints.read_json(bone_footprints.footprint_path("toy", self.data))
        self.assertEqual(4, content["cellsPerUnit"])
        # 96 Pixel pro Zelle: der lange Knochen (144 x 48) belegt zwei Zellen.
        self.assertEqual(["##"], content["bones"][0]["rows"])

    def test_check_detects_outdated_or_missing_file(self):
        code, out = self.run_main("--check")
        self.assertEqual(1, code)
        self.assertIn("toy (fehlt)", out)
        self.run_main()
        with open(os.path.join(self.data, "balance.json"), "w") as f:
            json.dump({"excavation": {"cellsPerUnit": 4}}, f)
        self.assertEqual(1, self.run_main("--check")[0])

    def test_params_are_kept_on_rerun_and_check_rejects_overrides(self):
        self.run_main("--min-coverage", "0.5")
        self.assertEqual(0, self.run_main()[0])
        content = bone_footprints.read_json(bone_footprints.footprint_path("toy", self.data))
        self.assertEqual(0.5, content["params"]["minCoverage"])
        self.assertEqual(2, self.run_main("--check", "--min-coverage", "0.1")[0])

    def test_missing_cells_per_unit_is_an_error(self):
        with open(os.path.join(self.data, "balance.json"), "w") as f:
            json.dump({"excavation": {}}, f)
        code, out = self.run_main()
        self.assertEqual(2, code)
        self.assertIn("cellsPerUnit fehlt", out)


class CheckedInDataTest(unittest.TestCase):

    def test_checked_in_footprints_are_current(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = bone_footprints.main(["--check"])
        self.assertEqual(0, code, out.getvalue())


if __name__ == "__main__":
    unittest.main()
