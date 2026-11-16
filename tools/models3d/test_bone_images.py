"""Tests fuer bone_images.py. Aufruf: python3 -m unittest discover -s tools/models3d -p 'test_*.py'"""

import json
import math
import os
import struct
import sys
import tempfile
import unittest

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bone_images  # noqa: E402

BOX_FACES = [
    (0, 1, 2), (1, 3, 2),  # x = 0
    (4, 6, 5), (5, 6, 7),  # x = 1
    (0, 4, 1), (1, 4, 5),  # y = 0
    (2, 3, 6), (3, 7, 6),  # y = 1
    (0, 2, 4), (2, 6, 4),  # z = 0
    (1, 5, 3), (3, 5, 7),  # z = 1
]


def box(origin, size):
    """Quader: Eckpunkte (Index = 4*x + 2*y + z) und Dreiecke (gegen den Uhrzeigersinn von aussen)."""
    ox, oy, oz = origin
    sx, sy, sz = size
    vertices = [[ox + dx * sx, oy + dy * sy, oz + dz * sz] for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)]
    return vertices, BOX_FACES


def write_glb(path, vertices, faces, color=None):
    """Minimale GLB-Datei mit einem indizierten Mesh (ohne Normalen)."""
    pos = np.asarray(vertices, dtype="<f4").tobytes()
    idx = np.asarray(faces, dtype="<u4").reshape(-1).tobytes()
    data = idx + pos
    prim = {"attributes": {"POSITION": 1}, "indices": 0}
    gltf = {
        "asset": {"version": "2.0"},
        "buffers": [{"byteLength": len(data)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": len(idx)},
            {"buffer": 0, "byteOffset": len(idx), "byteLength": len(pos)},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5125, "count": len(faces) * 3, "type": "SCALAR"},
            {"bufferView": 1, "componentType": 5126, "count": len(vertices), "type": "VEC3"},
        ],
        "meshes": [{"primitives": [prim]}],
        "nodes": [{"mesh": 0}],
    }
    if color is not None:
        prim["material"] = 0
        gltf["materials"] = [{"pbrMetallicRoughness": {"baseColorFactor": list(color) + [1.0]}}]
    js = json.dumps(gltf).encode("utf-8")
    js += b" " * (-len(js) % 4)
    data += b"\0" * (-len(data) % 4)
    total = 12 + 8 + len(js) + 8 + len(data)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", bone_images.GLB_MAGIC, 2, total))
        f.write(struct.pack("<II", len(js), bone_images.CHUNK_JSON) + js)
        f.write(struct.pack("<II", len(data), bone_images.CHUNK_BIN) + data)


class SyntheticSpecies:
    """Temporaerer Modell- und Asset-Ordner mit Art 'toy' aus Quadern."""

    def __init__(self, boxes):
        self.tmp = tempfile.TemporaryDirectory()
        self.models = os.path.join(self.tmp.name, "models")
        self.assets = os.path.join(self.tmp.name, "assets")
        self.set_boxes(boxes)

    def set_boxes(self, boxes):
        folder = os.path.join(self.models, "toy")
        os.makedirs(os.path.join(folder, "bones"), exist_ok=True)
        for name in os.listdir(os.path.join(folder, "bones")):
            os.remove(os.path.join(folder, "bones", name))
        bones = []
        for bone_id, (origin, size) in boxes.items():
            write_glb(os.path.join(folder, "bones", f"{bone_id}.glb"), *box(origin, size))
            bones.append({"id": bone_id, "file": f"{bone_id}.glb"})
        with open(os.path.join(folder, "bones.json"), "w") as f:
            json.dump({"species": "toy", "bones": bones}, f)

    def run(self, *args):
        return bone_images.main(list(args) + ["--models-dir", self.models, "--assets-dir", self.assets])

    def path(self, *parts):
        return os.path.join(self.assets, "toy", *parts)

    def close(self):
        self.tmp.cleanup()


def params(**changes):
    result = dict(bone_images.DEFAULTS)
    result.update(changes)
    return result


def render_mesh(vertices, faces, **changes):
    pos = np.asarray(vertices, dtype=np.float64)
    tris = np.asarray(faces, dtype=np.int64)
    return bone_images.render_bone(pos, tris, None, None, params(**changes))


def render_box(origin, size, **changes):
    return render_mesh(*box(origin, size), **changes)


def boxes(parts):
    """Mehrere Quader als ein Mesh: parts = [(origin, size), ...]."""
    vertices, faces = [], []
    for origin, size in parts:
        v, f = box(origin, size)
        faces += [tuple(i + len(vertices) for i in face) for face in f]
        vertices += v
    return vertices, faces


def arc(radius=1.0, pieces=60, thickness=0.06):
    """Rippenartiger Bogen aus kleinen Quadern in der y-z-Ebene (Halbkreis)."""
    parts = []
    for k in range(pieces):
        angle = math.pi * (k + 0.5) / pieces
        parts.append(((0.0, -radius * math.sin(angle), radius * math.cos(angle)), (thickness,) * 3))
    return boxes(parts)


def rotation_matrix(ax, ay, az):
    cx, sx, cy, sy, cz, sz = math.cos(ax), math.sin(ax), math.cos(ay), math.sin(ay), math.cos(az), math.sin(az)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


class RenderTest(unittest.TestCase):

    def test_frame_and_mask_follow_scale(self):
        rgba, mask, (left, top, width, height), _rot = render_box((0, 0, 0), (1.0, 0.5, 0.25), pxPerUnit=40, padding=2)
        # Von links gesehen: Breite = x-Ausdehnung, Hoehe = y-Ausdehnung, plus Rand.
        self.assertEqual((width, height), (40 + 1 + 4, 20 + 1 + 4))
        self.assertEqual(rgba.shape, (height, width, 4))
        self.assertEqual((left, top), (-2, -22))
        self.assertAlmostEqual(mask.sum(), 40 * 20, delta=40 + 20 + 1)

    def test_same_scale_for_all_bones(self):
        _r, small, _f, _rot = render_box((0, 0, 0), (0.5, 0.5, 0.5), pxPerUnit=40)
        _r, large, _f, _rot = render_box((0, 0, 0), (1.0, 1.0, 1.0), pxPerUnit=40)
        self.assertAlmostEqual(large.sum() / small.sum(), 4.0, delta=0.2)

    def test_view_changes_projection(self):
        size = (1.0, 0.25, 0.5)
        _r, _m, side, _rot = render_box((0, 0, 0), size, pxPerUnit=40, padding=0, view="left")
        _r, _m, top, _rot = render_box((0, 0, 0), size, pxPerUnit=40, padding=0, view="top")
        _r, _m, lying, rot = render_box((0, 0, 0), size, pxPerUnit=40, padding=0)
        self.assertEqual((side[2], side[3]), (41, 11))
        self.assertEqual((top[2], top[3]), (41, 21))
        # Liegelage: Blick entlang der kleinsten Ausdehnung (y), laengste Ausdehnung (x) horizontal.
        self.assertEqual((lying[2], lying[3]), (41, 21))
        self.assertAlmostEqual(abs(rot[0][0]), 1.0, places=9)
        self.assertAlmostEqual(abs(rot[2][1]), 1.0, places=9)

    def test_lying_rotation_is_proper_rotation(self):
        vertices, faces = arc()
        pos = np.asarray(vertices) @ rotation_matrix(0.3, -1.1, 0.7).T
        rot = np.asarray(bone_images.lying_rotation(pos, np.asarray(faces)))
        np.testing.assert_allclose(rot @ rot.T, np.eye(3), atol=1e-12)
        self.assertAlmostEqual(np.linalg.det(rot), 1.0, places=12)

    def test_lying_shows_curvature_of_rib(self):
        vertices, faces = arc()
        _r, side, side_frame, _rot = render_mesh(vertices, faces, pxPerUnit=40, view="left")
        _r, lying, frame, _rot = render_mesh(vertices, faces, pxPerUnit=40)
        # Von der Seite ein schmaler Stab, in Liegelage der Bogen in voller Breite (Sehne horizontal).
        self.assertGreater(lying.sum(), 2 * side.sum())
        self.assertGreater(frame[2], 5 * side_frame[2])
        self.assertGreater(frame[2], frame[3])

    def test_lying_is_independent_of_model_orientation(self):
        vertices, faces = boxes([((0, 0, 0), (1.0, 0.4, 0.1)), ((0.8, 0.4, 0), (0.2, 0.3, 0.1))])
        _r, mask, frame, _rot = render_mesh(vertices, faces, pxPerUnit=40)
        turned = np.asarray(vertices) @ rotation_matrix(0.9, 0.4, -2.0).T
        _r, mask2, frame2, _rot = render_mesh(turned, faces, pxPerUnit=40)
        self.assertEqual(frame[2:], frame2[2:])
        self.assertLess(np.mean(mask != mask2), 0.02)

    def test_mirrored_bones_have_same_outline(self):
        vertices, faces = boxes([((0, 0, 0), (1.0, 0.4, 0.1)), ((0.8, 0.4, 0), (0.2, 0.3, 0.1))])
        turned = np.asarray(vertices) @ rotation_matrix(0.5, 0.2, 0.3).T
        mirrored = turned * np.array([1.0, 1.0, -1.0])
        # Spiegeln dreht die Dreiecke um; die Flaechennormalen zeigen wieder nach aussen.
        flipped = [(a, c, b) for a, b, c in faces]
        _r, mask, frame, _rot = render_mesh(turned, faces, pxPerUnit=40)
        _r, mask2, frame2, _rot = render_mesh(mirrored, flipped, pxPerUnit=40)
        self.assertEqual(frame, frame2)
        np.testing.assert_array_equal(mask, mask2)

    def test_jacobi_matches_numpy(self):
        m = np.array([[4.0, 1.0, 0.5], [1.0, 3.0, 0.2], [0.5, 0.2, 1.0]])
        values, vectors = bone_images._jacobi_eigen(m.tolist())
        np.testing.assert_allclose(values, sorted(np.linalg.eigvalsh(m), reverse=True), atol=1e-12)
        for value, vector in zip(values, vectors):
            np.testing.assert_allclose(m @ np.asarray(vector), value * np.asarray(vector), atol=1e-12)

    def test_background_transparent_and_bone_opaque(self):
        rgba, mask, _f, _rot = render_box((0, 0, 0), (1.0, 1.0, 1.0), pxPerUnit=30, padding=3)
        self.assertEqual(rgba[0, 0, 3], 0)
        self.assertEqual(rgba[-1, -1, 3], 0)
        self.assertTrue((rgba[..., 3][mask] >= 128).all())
        self.assertTrue((rgba[..., 3][~mask] < 128).all())
        center = rgba[rgba.shape[0] // 2, rgba.shape[1] // 2]
        self.assertEqual(center[3], 255)
        # Knochenfarbe: warm und hell (Rot > Blau), nicht schwarz.
        self.assertGreater(int(center[0]), int(center[2]))
        self.assertGreater(int(center[0]), 100)

    def test_deterministic(self):
        a = render_box((0.1, -0.2, 0.3), (0.7, 0.4, 0.2), pxPerUnit=37)
        b = render_box((0.1, -0.2, 0.3), (0.7, 0.4, 0.2), pxPerUnit=37)
        np.testing.assert_array_equal(a[0], b[0])
        np.testing.assert_array_equal(a[1], b[1])
        self.assertEqual(a[2], b[2])
        self.assertEqual(a[3], b[3])

    def test_material_color_is_used(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = os.path.join(tmp.name, "b.glb")
        write_glb(path, *box((0, 0, 0), (1, 1, 1)), color=(0.1, 0.2, 0.9))
        pos, tris, normals, color = bone_images.read_glb_mesh(path)
        self.assertIsNone(normals)
        self.assertEqual(len(tris), 12)
        self.assertAlmostEqual(color[2], 0.9, places=5)
        rgba, _m, _f, _rot = bone_images.render_bone(pos, tris, normals, color, params(pxPerUnit=20))
        center = rgba[rgba.shape[0] // 2, rgba.shape[1] // 2]
        self.assertGreater(int(center[2]), int(center[0]))


class OutputTest(unittest.TestCase):

    def setUp(self):
        self.species = SyntheticSpecies({
            "a": ((0, 0, 0), (0.5, 0.25, 0.25)),
            "b": ((0.6, 0, 0), (0.25, 0.5, 0.25)),
        })
        self.addCleanup(self.species.close)

    def test_writes_all_files_and_check_passes(self):
        self.assertEqual(self.species.run("toy", "--px-per-unit", "40"), 0)
        for bone_id in ("a", "b"):
            self.assertTrue(os.path.isfile(self.species.path("images", f"{bone_id}.png")))
            self.assertTrue(os.path.isfile(self.species.path("masks", f"{bone_id}.png")))
        with open(self.species.path("manifest.json")) as f:
            manifest = json.load(f)
        self.assertEqual([b["id"] for b in manifest["bones"]], ["a", "b"])
        self.assertEqual(manifest["params"]["pxPerUnit"], 40)
        self.assertEqual(manifest["params"]["view"], "lying")
        self.assertEqual(len(manifest["bones"][0]["rotation"]), 3)
        with Image.open(self.species.path("images", "a.png")) as img:
            self.assertEqual(img.mode, "RGBA")
            self.assertEqual(img.size, (manifest["bones"][0]["width"], manifest["bones"][0]["height"]))
        with Image.open(self.species.path("masks", "a.png")) as img:
            self.assertEqual(img.mode, "1")
            self.assertEqual(int((np.asarray(img.convert("L")) > 0).sum()), manifest["bones"][0]["maskPixels"])
        # --check nimmt pxPerUnit aus dem Manifest, ein erneuter Lauf schreibt nichts.
        self.assertEqual(self.species.run("--check"), 0)
        mtime = os.path.getmtime(self.species.path("images", "a.png"))
        self.assertEqual(self.species.run("toy"), 0)
        self.assertEqual(os.path.getmtime(self.species.path("images", "a.png")), mtime)

    def test_check_detects_changed_image(self):
        self.species.run("toy", "--px-per-unit", "40")
        path = self.species.path("images", "b.png")
        with Image.open(path) as img:
            pixels = np.asarray(img).copy()
        pixels[..., 0] = 255 - pixels[..., 0]
        Image.fromarray(pixels, "RGBA").save(path)
        self.assertEqual(self.species.run("--check"), 1)
        self.assertEqual(self.species.run("toy"), 0)
        self.assertEqual(self.species.run("--check"), 0)

    def test_check_detects_missing_and_extra_files(self):
        self.species.run("toy", "--px-per-unit", "40")
        os.remove(self.species.path("masks", "a.png"))
        self.assertEqual(self.species.run("--check"), 1)
        self.species.run("toy")
        open(self.species.path("images", "old.png"), "wb").close()
        self.assertEqual(self.species.run("--check"), 1)
        self.species.run("toy")
        self.assertFalse(os.path.exists(self.species.path("images", "old.png")))
        self.assertEqual(self.species.run("--check"), 0)

    def test_new_bone_needs_only_rerun(self):
        self.species.run("toy", "--px-per-unit", "40")
        self.species.set_boxes({
            "a": ((0, 0, 0), (0.5, 0.25, 0.25)),
            "b": ((0.6, 0, 0), (0.25, 0.5, 0.25)),
            "c": ((1.0, 0, 0), (0.25, 0.25, 0.25)),
        })
        self.assertEqual(self.species.run("--check"), 1)
        self.assertEqual(self.species.run(), 0)
        self.assertTrue(os.path.isfile(self.species.path("images", "c.png")))
        self.assertEqual(self.species.run("--check"), 0)

    def test_check_detects_changed_rotation(self):
        self.species.run("toy", "--px-per-unit", "40")
        path = self.species.path("manifest.json")
        with open(path) as f:
            manifest = json.load(f)
        manifest["bones"][0]["rotation"][0][0] += 0.01
        with open(path, "w") as f:
            json.dump(manifest, f)
        self.assertEqual(self.species.run("--check"), 1)

    def test_check_rejects_parameter_options(self):
        self.assertEqual(self.species.run("--check", "--px-per-unit", "10"), 2)

    def test_unknown_species_or_view_is_error(self):
        self.assertEqual(self.species.run("nope"), 2)
        with self.assertRaises(bone_images.BoneImageError):
            bone_images.render_species("toy", params(view="front"), self.species.models)

    def test_contact_sheet(self):
        out = os.path.join(self.species.tmp.name, "sheet.png")
        self.assertEqual(self.species.run("toy", "--px-per-unit", "40", "--preview", out), 0)
        with Image.open(out) as img:
            self.assertGreater(img.width, 0)


if __name__ == "__main__":
    unittest.main()
