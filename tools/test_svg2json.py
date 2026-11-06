#!/usr/bin/env python3
"""Tests fuer tools/svg2json.py (nur Standardbibliothek): python3 tools/test_svg2json.py"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import svg2json  # noqa: E402

VALID = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50">
  <rect id="background" width="100" height="50"/>
  <g fill="#E8DFC8">
    <g id="skull">
      <path id="skull__snout" d="M0,0   L10,0
        L10,10 Z"/>
      <path id="skull__orbit" d="M20,0 L30,0 L30,10 Z"/>
    </g>
    <g id="jaw">
      <path id="jaw__tip" d="m0,20 l5,0 l0,5 z"/>
    </g>
  </g>
</svg>"""


def svg_with_body(body, view_box="0 0 100 50"):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view_box}">{body}</svg>'


class ConvertTest(unittest.TestCase):

    def test_extracts_bones_zones_and_view_box_in_document_order(self):
        result = svg2json.convert_svg_text(VALID, "test")
        self.assertEqual("test", result["name"])
        self.assertEqual({"x": 0.0, "y": 0.0, "width": 100.0, "height": 50.0}, result["viewBox"])
        self.assertEqual(["skull", "jaw"], [b["id"] for b in result["bones"]])
        self.assertEqual(["snout", "orbit"], [z["id"] for z in result["bones"][0]["zones"]])
        # Leerraum wird zusammengefasst, die Pfadsyntax bleibt.
        self.assertEqual("M0,0 L10,0 L10,10 Z", result["bones"][0]["zones"][0]["d"])
        self.assertEqual("m0,20 l5,0 l0,5 z", result["bones"][1]["zones"][0]["d"])

    def test_rejects_transform_anywhere(self):
        bodies = [
            '<g id="skull" transform="translate(1,2)"><path id="skull__snout" d="M0,0 Z"/></g>',
            '<g id="skull"><path id="skull__snout" d="M0,0 Z" transform="scale(2)"/></g>',
            '<g transform="rotate(10)"><g id="skull"><path id="skull__snout" d="M0,0 Z"/></g></g>',
        ]
        for body in bodies:
            with self.subTest(body=body), self.assertRaisesRegex(svg2json.SvgError, "transform"):
                svg2json.convert_svg_text(svg_with_body(body), "t")

    def test_rejects_invalid_view_box(self):
        body = '<g id="skull"><path id="skull__snout" d="M0,0 Z"/></g>'
        for view_box in ["0 0 100", "0 0 0 50", "a b c d"]:
            with self.subTest(view_box=view_box), self.assertRaisesRegex(svg2json.SvgError, "viewBox"):
                svg2json.convert_svg_text(svg_with_body(body, view_box), "t")

    def test_rejects_convention_violations(self):
        cases = {
            "nur <path>": '<g id="skull"><path id="skull__snout" d="M0,0 Z"/><rect width="1" height="1"/></g>',
            "Pfad braucht id": '<g id="skull"><path id="skull__snout" d="M0,0 Z"/><path d="M1,1 Z"/></g>',
            "gehoert zu": '<g id="skull"><path id="skull__snout" d="M0,0 Z"/><path id="jaw__tip" d="M0,0 Z"/></g>',
            "doppelt": '<g id="skull"><path id="skull__snout" d="M0,0 Z"/><path id="skull__snout" d="M1,1 Z"/></g>',
            "ausserhalb": '<path id="skull__snout" d="M0,0 Z"/>',
            "leer": '<g id="skull"><path id="skull__snout" d=" "/></g>',
            "M/m": '<g id="skull"><path id="skull__snout" d="L0,0 Z"/></g>',
            "keine Knochengruppen": '<rect width="1" height="1"/>',
        }
        for message, body in cases.items():
            with self.subTest(message=message), self.assertRaisesRegex(svg2json.SvgError, message):
                svg2json.convert_svg_text(svg_with_body(body), "t")

    def test_main_writes_json_and_check_detects_stale_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "src"
            out = Path(tmp) / "out"
            src.mkdir()
            (src / "quadruped.svg").write_text(VALID, encoding="utf-8")
            self.assertEqual(0, svg2json.main(["--src", str(src), "--out", str(out)]))
            target = out / "skeleton_quadruped.json"
            self.assertEqual("quadruped", json.loads(target.read_text(encoding="utf-8"))["name"])
            self.assertEqual(0, svg2json.main(["--src", str(src), "--out", str(out), "--check"]))
            target.write_text("{}", encoding="utf-8")
            self.assertEqual(1, svg2json.main(["--src", str(src), "--out", str(out), "--check"]))

    def test_committed_json_files_are_up_to_date(self):
        self.assertEqual(0, svg2json.main(["--check"]))


if __name__ == "__main__":
    unittest.main()
