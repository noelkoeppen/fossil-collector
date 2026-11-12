"""Tests fuer neighbors.py. Aufruf: python3 -m unittest discover -s tools/models3d -p 'test_*.py'"""

import json
import os
import struct
import sys
import tempfile
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import neighbors  # noqa: E402


def write_glb(path, vertices):
    """Minimale GLB-Datei mit einem Mesh aus den gegebenen Eckpunkten."""
    data = np.asarray(vertices, dtype="<f4").tobytes()
    gltf = {
        "asset": {"version": "2.0"},
        "buffers": [{"byteLength": len(data)}],
        "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": len(data)}],
        "accessors": [{"bufferView": 0, "componentType": 5126, "count": len(vertices), "type": "VEC3"}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
        "nodes": [{"mesh": 0}],
    }
    js = json.dumps(gltf).encode("utf-8")
    js += b" " * (-len(js) % 4)
    data += b"\0" * (-len(data) % 4)
    total = 12 + 8 + len(js) + 8 + len(data)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", neighbors.GLB_MAGIC, 2, total))
        f.write(struct.pack("<II", len(js), neighbors.CHUNK_JSON) + js)
        f.write(struct.pack("<II", len(data), neighbors.CHUNK_BIN) + data)


def cube(x):
    """Kleiner Wuerfel (Kantenlaenge 1) ab x auf der x-Achse."""
    return [[x + dx, dy, dz] for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)]


class SyntheticModel:
    """Temporaerer Modell- und Datenordner mit drei Knochen a-b-c in einer Reihe."""

    def __init__(self, gaps=(0.01, 0.01), overrides=None):
        self.tmp = tempfile.TemporaryDirectory()
        root = self.tmp.name
        self.models = os.path.join(root, "models")
        self.data = os.path.join(root, "data")
        folder = os.path.join(self.models, "toy")
        os.makedirs(os.path.join(folder, "bones"))
        os.makedirs(os.path.join(self.data, "locale"))
        xs = [0.0, 1.0 + gaps[0], 2.0 + gaps[0] + gaps[1]]
        bones = []
        for bone_id, x in zip("abc", xs):
            write_glb(os.path.join(folder, "bones", f"{bone_id}.glb"), cube(x))
            bones.append({"id": bone_id, "file": f"{bone_id}.glb", "name": {"de": f"{bone_id}-de", "en": f"{bone_id}-en"}})
        with open(os.path.join(folder, "bones.json"), "w") as f:
            json.dump({"species": "toy", "bones": bones}, f)
        if overrides is not None:
            with open(os.path.join(folder, neighbors.OVERRIDES_FILE), "w") as f:
                json.dump(overrides, f)
        with open(os.path.join(self.data, "species.json"), "w") as f:
            json.dump([{"id": "toy", "layer": "quaternary", "rarityWeight": 1, "bones": []}], f)
        for lang in neighbors.LANGUAGES:
            with open(os.path.join(self.data, "locale", f"{lang}.json"), "w") as f:
                json.dump({"species.toy.name": "Toy", "bone.toy.old.name": "alt"}, f)

    def run(self, *args):
        return neighbors.main(list(args) + ["--models-dir", self.models, "--data-dir", self.data])

    def read(self, rel):
        with open(os.path.join(self.data, rel), encoding="utf-8") as f:
            return json.load(f)

    def close(self):
        self.tmp.cleanup()


class GraphTest(unittest.TestCase):

    def test_derive_edges_uses_surface_distance(self):
        ids = ["a", "b", "c"]
        verts = {"a": np.array(cube(0.0)), "b": np.array(cube(1.02)), "c": np.array(cube(3.0))}
        self.assertEqual({("a", "b")}, neighbors.derive_edges(ids, verts, 0.03))
        self.assertEqual(set(), neighbors.derive_edges(ids, verts, 0.01))

    def test_overrides_add_and_remove(self):
        ids = ["a", "b", "c"]
        edges = {("a", "b"), ("a", "c")}
        result = neighbors.apply_overrides(ids, edges, {
            "remove": [{"bones": ["c", "a"], "reason": "x"}],
            "add": [["c", "b"]],
        })
        self.assertEqual({("a", "b"), ("b", "c")}, result)

    def test_stale_or_invalid_overrides_fail(self):
        ids = ["a", "b", "c"]
        edges = {("a", "b")}
        for overrides in (
            {"remove": [["b", "c"]]},
            {"add": [["a", "b"]]},
            {"add": [["a", "a"]]},
            {"add": [["a", "x"]]},
        ):
            with self.assertRaises(neighbors.NeighborError, msg=str(overrides)):
                neighbors.apply_overrides(ids, edges, overrides)

    def test_components(self):
        self.assertEqual(1, len(neighbors.components(["a", "b", "c"], {("a", "b"), ("b", "c")})))
        self.assertEqual([{"a", "b"}, {"c"}], neighbors.components(["a", "b", "c"], {("a", "b")}))

    def test_update_locale_replaces_only_species_bones(self):
        table = {"x": "1", "bone.toy.old.name": "alt", "bone.other.a.name": "o"}
        result = neighbors.update_locale(table, "toy", {"a": "A"})
        self.assertEqual({"x": "1", "bone.other.a.name": "o", "bone.toy.a.name": "A"}, result)


class EndToEndTest(unittest.TestCase):

    def test_writes_species_and_locales_then_check_passes(self):
        model = SyntheticModel()
        try:
            self.assertEqual(1, model.run("--check"))
            self.assertEqual(0, model.run())
            species = model.read("species.json")[0]
            self.assertEqual(["a", "b", "c"], species["bones"])
            self.assertEqual([["a", "b"], ["b", "c"]], species["neighbors"])
            self.assertEqual("quaternary", species["layer"])
            de = model.read("locale/de.json")
            self.assertEqual("b-de", de["bone.toy.b.name"])
            self.assertNotIn("bone.toy.old.name", de)
            self.assertEqual(0, model.run("--check"))
        finally:
            model.close()

    def test_disconnected_graph_fails(self):
        model = SyntheticModel(gaps=(0.01, 0.5))
        try:
            self.assertEqual(2, model.run())
            self.assertEqual(0, model.run("--threshold", "0.6"))
        finally:
            model.close()

    def test_overrides_can_connect_graph(self):
        model = SyntheticModel(gaps=(0.01, 0.5), overrides={"add": [{"bones": ["b", "c"], "reason": "test"}]})
        try:
            self.assertEqual(0, model.run())
            self.assertEqual([["a", "b"], ["b", "c"]], model.read("species.json")[0]["neighbors"])
        finally:
            model.close()

    def test_unknown_species_in_data_fails(self):
        model = SyntheticModel()
        try:
            with open(os.path.join(model.data, "species.json"), "w") as f:
                json.dump([], f)
            self.assertEqual(2, model.run())
        finally:
            model.close()


class RepositoryTest(unittest.TestCase):
    """Die eingecheckten Daten entsprechen dem Skript-Ergebnis."""

    def test_repository_data_is_current(self):
        self.assertEqual(0, neighbors.main(["--check"]))


if __name__ == "__main__":
    unittest.main()
