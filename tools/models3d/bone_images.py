#!/usr/bin/env python3
"""Rendert pro Knochen einer Art ein Draufsicht-Bild mit Transparenz und eine Umrissmaske.

Quelle pro Art ist der Modellordner art/models3d/<speciesId>/ (bones.json, bones/<id>.glb).
Ziel ist das App-Modul, damit die Grabungsflaeche (Kapitel 8) die Bilder per Art- und Knochen-ID
findet:

    app/src/main/assets/bones/<speciesId>/manifest.json
    app/src/main/assets/bones/<speciesId>/images/<boneId>.png   RGBA, Hintergrund transparent
    app/src/main/assets/bones/<speciesId>/masks/<boneId>.png    1 Bit, 1 = Knochen

Alle Knochen einer Art werden mit derselben Blickrichtung (--view) und demselben Massstab
(--px-per-unit, Pixel pro Modelleinheit) gerendert, die Groessenverhaeltnisse stimmen also. Das
Manifest haelt die Parameter und pro Knochen Bildgroesse und Lage der linken oberen Bildecke in
Modellkoordinaten fest; --check rendert mit den Parametern aus dem Manifest neu.

Renderer: eigener Software-Rasterizer mit numpy (orthografische Projektion, Z-Puffer,
Ueberabtastung fuer glatte Kanten), PNG ueber Pillow. Keine GPU, kein OpenGL. Das Ergebnis ist
deterministisch; --check vergleicht trotzdem dekodierte Pixel mit kleiner Toleranz statt Bytes, weil
zlib-Versionen dieselben Pixel unterschiedlich komprimieren koennen.

Aufruf (aus dem Repo-Wurzelverzeichnis):
    python3 tools/models3d/bone_images.py                 # alle Arten mit Modellordner
    python3 tools/models3d/bone_images.py cave_bear       # nur eine Art
    python3 tools/models3d/bone_images.py --check         # nichts schreiben, Abweichung = Exit-Code 1
    python3 tools/models3d/bone_images.py --preview /tmp/sheet.png   # zusaetzlich Kontaktbogen

Abhaengigkeiten: numpy, Pillow.
"""

import argparse
import io
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
MODELS_DIR = os.path.join(REPO, "art", "models3d")
ASSETS_DIR = os.path.join(REPO, "app", "src", "main", "assets", "bones")

MANIFEST_FILE = "manifest.json"
IMAGES_DIR = "images"
MASKS_DIR = "masks"
FORMAT_VERSION = 1

# Blickrichtungen im Skelett-Koordinatensystem (x nach hinten, y nach oben, z zur linken Seite des
# Tieres). Je Ansicht: Modellachse fuer Bild-rechts, fuer Bild-oben und zur Kamera hin (Tiefe).
VIEWS = {
    # Von der linken Seite: das Tier liegt auf der rechten Seite in der Grabung.
    "left": ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0)),
    # Von der rechten Seite (gespiegelt, Nase rechts).
    "right": ((-1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, -1.0)),
    # Von oben auf das stehende Skelett (Ruecken), Kopf links.
    "top": ((1.0, 0.0, 0.0), (0.0, 0.0, -1.0), (0.0, 1.0, 0.0)),
}

DEFAULTS = {
    "view": "left",
    "pxPerUnit": 384,
    "supersample": 4,
    "padding": 2,
    # Licht von oben (zur Kamera hin), leicht von links oben, in Bildkoordinaten (rechts, oben, Kamera).
    "light": [-0.35, 0.45, 0.82],
    "ambient": 0.32,
    "diffuse": 0.68,
    "specular": 0.10,
    # Tiefer liegende Flaechen werden bis zu diesem Anteil dunkler (wirkt wie im Boden eingebettet).
    "depthShade": 0.18,
    # Knochenfarbe, wenn die .glb keine baseColorFactor hat.
    "color": [0.82, 0.76, 0.62],
}

# Toleranz fuer --check: Ein Pixel weicht ab, wenn ein Kanal um mehr als CHECK_CHANNEL_TOLERANCE
# abweicht (Maske: wenn es kippt). Ein Bild gilt als veraltet, wenn mehr als CHECK_PIXEL_FRACTION
# seiner Pixel abweichen. Das faengt Rundungsunterschiede zwischen Plattformen ab (ein Teilpixel genau
# auf einer Dreieckskante), jede echte Aenderung an Modell oder Parametern betrifft viel mehr Pixel.
CHECK_CHANNEL_TOLERANCE = 2
CHECK_PIXEL_FRACTION = 0.002

GLB_MAGIC = 0x46546C67
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942
COMPONENT_DTYPES = {5121: "<u1", 5123: "<u2", 5125: "<u4", 5126: "<f4"}
TYPE_SIZES = {"SCALAR": 1, "VEC3": 3}


class BoneImageError(Exception):
    """Fehler in den Modelldaten oder Parametern."""


# --- Laden ---------------------------------------------------------------------------------


def _accessor(gltf, binary, index, path):
    acc = gltf["accessors"][index]
    dtype = COMPONENT_DTYPES.get(acc.get("componentType"))
    size = TYPE_SIZES.get(acc.get("type"))
    if dtype is None or size is None:
        raise BoneImageError(f"{path}: Accessor {index} hat ein nicht unterstuetztes Format")
    view = gltf["bufferViews"][acc["bufferView"]]
    item = np.dtype(dtype).itemsize * size
    stride = view.get("byteStride", item)
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    arr = np.ndarray(shape=(acc["count"], size), dtype=dtype, buffer=binary, offset=start,
                     strides=(stride, np.dtype(dtype).itemsize))
    return np.array(arr)


def read_glb_mesh(path):
    """Liest Dreiecke einer .glb-Datei.

    Liefert (positions N x 3 float64, triangles M x 3 int64, normals N x 3 oder None, Farbe RGB oder
    None). Mehrere Primitive werden zusammengefasst. Knoten-Transformationen werden nicht unterstuetzt
    (die Modelle liegen schon im gemeinsamen Skelett-Koordinatensystem).
    """
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 12 or struct.unpack_from("<I", data, 0)[0] != GLB_MAGIC:
        raise BoneImageError(f"{path}: keine GLB-Datei")
    offset = 12
    gltf = None
    binary = None
    while offset + 8 <= len(data):
        chunk_len, chunk_type = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8: offset + 8 + chunk_len]
        if chunk_type == CHUNK_JSON:
            gltf = json.loads(chunk.decode("utf-8"))
        elif chunk_type == CHUNK_BIN:
            binary = chunk
        offset += 8 + chunk_len
    if gltf is None or binary is None:
        raise BoneImageError(f"{path}: JSON- oder BIN-Block fehlt")
    for node in gltf.get("nodes", []):
        if any(k in node for k in ("matrix", "translation", "rotation", "scale")):
            raise BoneImageError(f"{path}: Knoten-Transformationen werden nicht unterstuetzt")
    positions, triangles, normals = [], [], []
    color = None
    base = 0
    for mesh in gltf.get("meshes", []):
        for prim in mesh.get("primitives", []):
            if prim.get("mode", 4) != 4:
                raise BoneImageError(f"{path}: nur Dreiecke werden unterstuetzt")
            pos = _accessor(gltf, binary, prim["attributes"]["POSITION"], path).astype(np.float64)
            if "indices" in prim:
                idx = _accessor(gltf, binary, prim["indices"], path).reshape(-1).astype(np.int64)
            else:
                idx = np.arange(len(pos), dtype=np.int64)
            if len(idx) % 3:
                raise BoneImageError(f"{path}: Indexanzahl ist kein Vielfaches von 3")
            if "NORMAL" in prim["attributes"]:
                normals.append(_accessor(gltf, binary, prim["attributes"]["NORMAL"], path).astype(np.float64))
            else:
                normals.append(None)
            positions.append(pos)
            triangles.append(idx.reshape(-1, 3) + base)
            base += len(pos)
            if color is None and "material" in prim:
                pbr = gltf["materials"][prim["material"]].get("pbrMetallicRoughness", {})
                if "baseColorFactor" in pbr:
                    color = [float(c) for c in pbr["baseColorFactor"][:3]]
    if not positions:
        raise BoneImageError(f"{path}: keine Meshes")
    pos = np.vstack(positions)
    tris = np.vstack(triangles)
    nrm = np.vstack(normals) if all(n is not None for n in normals) else None
    return pos, tris, nrm, color


def load_bones(species_id, models_dir=MODELS_DIR):
    """Knochenliste aus bones.json einer Art (Reihenfolge wie dort)."""
    folder = os.path.join(models_dir, species_id)
    path = os.path.join(folder, "bones.json")
    if not os.path.isfile(path):
        raise BoneImageError(f"{path}: fehlt")
    with open(path, encoding="utf-8") as f:
        model = json.load(f)
    if model.get("species") != species_id:
        raise BoneImageError(f"{path}: 'species' ist nicht '{species_id}'")
    ids = [b["id"] for b in model["bones"]]
    if len(set(ids)) != len(ids):
        raise BoneImageError(f"{path}: doppelte Knochen-IDs")
    for bone in model["bones"]:
        glb = os.path.join(folder, "bones", bone["file"])
        if not os.path.isfile(glb):
            raise BoneImageError(f"{glb}: Modell fehlt")
    return [(b["id"], os.path.join(folder, "bones", b["file"])) for b in model["bones"]]


def model_species(models_dir=MODELS_DIR):
    """Alle Arten mit Modellordner (Unterordner mit bones.json), alphabetisch."""
    return sorted(
        name for name in os.listdir(models_dir)
        if os.path.isfile(os.path.join(models_dir, name, "bones.json"))
    )


# --- Rendern -------------------------------------------------------------------------------


def _normalize(v):
    length = np.sqrt((v * v).sum(axis=-1, keepdims=True))
    return v / np.where(length > 0, length, 1.0)


def _vertex_normals(pos, tris):
    """Flaechengewichtete Eckpunkt-Normalen, falls die .glb keine hat."""
    a, b, c = pos[tris[:, 0]], pos[tris[:, 1]], pos[tris[:, 2]]
    face = np.cross(b - a, c - a)
    result = np.zeros_like(pos)
    for k in range(3):
        np.add.at(result, tris[:, k], face)
    return _normalize(result)


def _pow(x, exponent):
    """x hoch ganzzahligem Exponenten nur ueber Multiplikationen (plattformunabhaengig exakt)."""
    result = np.ones_like(x)
    base = x
    e = int(exponent)
    while e:
        if e & 1:
            result = result * base
        base = base * base
        e >>= 1
    return result


def project(pos, view):
    """Modellkoordinaten -> (rechts, oben, Tiefe) der Ansicht."""
    right, up, depth = (np.asarray(a, dtype=np.float64) for a in VIEWS[view])
    # Elementweise statt Matrixprodukt (kein BLAS), damit das Ergebnis ueberall gleich ist.
    return np.stack([(pos * right).sum(axis=1), (pos * up).sum(axis=1), (pos * depth).sum(axis=1)], axis=1)


def image_frame(projected, params):
    """Bildrahmen in Pixeln: (Spalte links, Zeile oben, Breite, Hoehe) im Pixelraster der Art.

    Das Pixelraster ist fuer alle Knochen einer Art gleich (Pixel k deckt [k, k+1) / pxPerUnit ab),
    daher passen Bilder benachbarter Knochen pixelgenau aneinander.
    """
    s = params["pxPerUnit"]
    pad = params["padding"]
    u = projected[:, 0] * s
    v = -projected[:, 1] * s
    left = int(math.floor(u.min())) - pad
    top = int(math.floor(v.min())) - pad
    right = int(math.floor(u.max())) + 1 + pad
    bottom = int(math.floor(v.max())) + 1 + pad
    return left, top, right - left, bottom - top


def rasterize(sx, sy, sz, tris, width, height):
    """Z-Puffer: liefert je Pixel Dreieck (-1 = leer) und baryzentrische Gewichte.

    sx, sy: Bildkoordinaten der Eckpunkte in Pixeln (Pixelmitte bei k + 0.5), sz: Tiefe (groesser
    = naeher an der Kamera).
    """
    n = width * height
    best_depth = np.full(n, -np.inf)
    best_tri = np.full(n, -1, dtype=np.int64)
    x0, x1, x2 = sx[tris[:, 0]], sx[tris[:, 1]], sx[tris[:, 2]]
    y0, y1, y2 = sy[tris[:, 0]], sy[tris[:, 1]], sy[tris[:, 2]]
    area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    keep = np.abs(area) > 1e-12
    ids = np.nonzero(keep)[0]
    cmin = np.clip(np.ceil(np.minimum(np.minimum(x0, x1), x2) - 0.5), 0, width).astype(np.int64)
    cmax = np.clip(np.floor(np.maximum(np.maximum(x0, x1), x2) - 0.5), -1, width - 1).astype(np.int64)
    rmin = np.clip(np.ceil(np.minimum(np.minimum(y0, y1), y2) - 0.5), 0, height).astype(np.int64)
    rmax = np.clip(np.floor(np.maximum(np.maximum(y0, y1), y2) - 0.5), -1, height - 1).astype(np.int64)
    w = np.maximum(cmax - cmin + 1, 0)
    h = np.maximum(rmax - rmin + 1, 0)
    count = (w * h)[ids]
    ids = ids[count > 0]
    count = count[count > 0]
    # In Bloecken verarbeiten, damit der Speicher klein bleibt.
    limit = 2_000_000
    start = 0
    while start < len(ids):
        cum = np.cumsum(count[start:])
        end = start + max(1, int(np.searchsorted(cum, limit, side="right")))
        block = ids[start:end]
        cnt = count[start:end]
        total = int(cnt.sum())
        tri = np.repeat(block, cnt)
        local = np.arange(total) - np.repeat(np.cumsum(cnt) - cnt, cnt)
        bw = w[tri]
        col = cmin[tri] + local % bw
        row = rmin[tri] + local // bw
        px = col + 0.5
        py = row + 0.5
        a = area[tri]
        l1 = ((px - x0[tri]) * (y2[tri] - y0[tri]) - (x2[tri] - x0[tri]) * (py - y0[tri])) / a
        l2 = ((x1[tri] - x0[tri]) * (py - y0[tri]) - (px - x0[tri]) * (y1[tri] - y0[tri])) / a
        l0 = 1.0 - l1 - l2
        inside = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
        tri, col, row = tri[inside], col[inside], row[inside]
        l0, l1, l2 = l0[inside], l1[inside], l2[inside]
        depth = l0 * sz[tris[tri, 0]] + l1 * sz[tris[tri, 1]] + l2 * sz[tris[tri, 2]]
        pix = row * width + col
        # Je Pixel das naechste Fragment; bei gleicher Tiefe gewinnt das kleinere Dreieck (stabil).
        order = np.lexsort((tri, -depth, pix))
        pix, depth, tri = pix[order], depth[order], tri[order]
        first = np.ones(len(pix), dtype=bool)
        first[1:] = pix[1:] != pix[:-1]
        pix, depth, tri = pix[first], depth[first], tri[first]
        better = (depth > best_depth[pix]) | ((depth == best_depth[pix]) & (tri < best_tri[pix]))
        best_depth[pix[better]] = depth[better]
        best_tri[pix[better]] = tri[better]
        start = end
    return best_tri, best_depth


def render_bone(pos, tris, normals, color, params):
    """Rendert einen Knochen. Liefert (RGBA uint8 H x W x 4, Maske bool H x W, Bildrahmen)."""
    view = params["view"]
    ss = params["supersample"]
    p = project(pos, view)
    left, top, width, height = image_frame(p, params)
    s = params["pxPerUnit"] * ss
    sx = p[:, 0] * s - left * ss
    sy = -p[:, 1] * s - top * ss
    sz = p[:, 2]
    W, H = width * ss, height * ss
    tri_of, depth = rasterize(sx, sy, sz, tris, W, H)
    covered = tri_of >= 0

    if normals is None:
        normals = _vertex_normals(pos, tris)
    n_view = _normalize(project(normals, view))
    # Normale je Pixel: baryzentrisch interpoliert (glatte Schattierung).
    idx = np.nonzero(covered)[0]
    t = tri_of[idx]
    col = (idx % W) + 0.5
    row = (idx // W) + 0.5
    a, b, c = tris[t, 0], tris[t, 1], tris[t, 2]
    area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a])
    l1 = ((col - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (row - sy[a])) / area
    l2 = ((sx[b] - sx[a]) * (row - sy[a]) - (col - sx[a]) * (sy[b] - sy[a])) / area
    l0 = 1.0 - l1 - l2
    n = _normalize(l0[:, None] * n_view[a] + l1[:, None] * n_view[b] + l2[:, None] * n_view[c])
    # Bildzeilen laufen nach unten, die Normale ist in (rechts, oben, Kamera): passt zu "light".
    light = _normalize(np.asarray(params["light"], dtype=np.float64))
    lambert = np.clip((n * light).sum(axis=1), 0.0, 1.0)
    half = _normalize(light + np.array([0.0, 0.0, 1.0]))
    spec = _pow(np.clip((n * half).sum(axis=1), 0.0, 1.0), 16)
    d = depth[idx]
    zmin, zmax = float(p[:, 2].min()), float(p[:, 2].max())
    rel = (d - zmin) / (zmax - zmin) if zmax > zmin else np.ones_like(d)
    shade = (params["ambient"] + params["diffuse"] * lambert) * (1.0 - params["depthShade"] * (1.0 - rel))
    base = np.asarray(color if color is not None else params["color"], dtype=np.float64)
    rgb_px = np.clip(shade[:, None] * base[None, :] + params["specular"] * spec[:, None], 0.0, 1.0)

    rgb = np.zeros((H * W, 3))
    rgb[idx] = rgb_px
    cov = covered.astype(np.float64)
    # Ueberabtastung zusammenfassen (vormultipliziert mitteln, dann zurueckrechnen).
    rgb = rgb.reshape(height, ss, width, ss, 3).sum(axis=(1, 3))
    cov = cov.reshape(height, ss, width, ss).sum(axis=(1, 3))
    alpha = cov / (ss * ss)
    with np.errstate(invalid="ignore", divide="ignore"):
        straight = np.where(cov[..., None] > 0, rgb / np.maximum(cov, 1)[..., None], 0.0)
    out = np.zeros((height, width, 4), dtype=np.uint8)
    out[..., :3] = np.floor(straight * 255.0 + 0.5).astype(np.uint8)
    out[..., 3] = np.floor(alpha * 255.0 + 0.5).astype(np.uint8)
    out[out[..., 3] == 0, :3] = 0
    mask = alpha >= 0.5
    return out, mask, (left, top, width, height)


# --- Ausgabe -------------------------------------------------------------------------------


def encode_png(image):
    buf = io.BytesIO()
    image.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def render_species(species_id, params, models_dir=MODELS_DIR):
    """Rendert alle Knochen einer Art. Liefert (manifest, {boneId: (rgba, maske)})."""
    for key in DEFAULTS:
        if key not in params:
            raise BoneImageError(f"Parameter '{key}' fehlt")
    if params["view"] not in VIEWS:
        raise BoneImageError(f"Unbekannte Ansicht '{params['view']}' (moeglich: {', '.join(VIEWS)})")
    if params["pxPerUnit"] <= 0 or params["supersample"] < 1 or params["padding"] < 0:
        raise BoneImageError("pxPerUnit > 0, supersample >= 1 und padding >= 0 erforderlich")
    bones = {}
    entries = []
    for bone_id, path in load_bones(species_id, models_dir):
        pos, tris, normals, color = read_glb_mesh(path)
        rgba, mask, (left, top, width, height) = render_bone(pos, tris, normals, color, params)
        if not mask.any():
            raise BoneImageError(f"{species_id}/{bone_id}: Maske ist leer (pxPerUnit zu klein?)")
        bones[bone_id] = (rgba, mask)
        entries.append({
            "id": bone_id,
            "width": width,
            "height": height,
            # Linke obere Bildecke im Pixelraster der Art (Pixel = Modelleinheit * pxPerUnit,
            # Spalten nach rechts, Zeilen nach unten der Ansicht).
            "left": left,
            "top": top,
            "maskPixels": int(mask.sum()),
        })
    manifest = {
        "formatVersion": FORMAT_VERSION,
        "species": species_id,
        "images": IMAGES_DIR + "/<boneId>.png",
        "masks": MASKS_DIR + "/<boneId>.png",
        "params": {k: params[k] for k in DEFAULTS},
        "bones": entries,
    }
    return manifest, bones


def format_manifest(manifest):
    head = {k: v for k, v in manifest.items() if k != "bones"}
    text = json.dumps(head, indent=2)[:-2]
    lines = [json.dumps(b, separators=(", ", ": ")) for b in manifest["bones"]]
    return text + ',\n  "bones": [\n    ' + ",\n    ".join(lines) + "\n  ]\n}\n"


def read_manifest(species_id, assets_dir=ASSETS_DIR):
    path = os.path.join(assets_dir, species_id, MANIFEST_FILE)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _decode(path, mode):
    with Image.open(path) as img:
        return np.asarray(img.convert(mode))


def compare_species(species_id, manifest, bones, assets_dir=ASSETS_DIR):
    """Liste der Abweichungen zwischen Renderergebnis und eingecheckter Ausgabe (leer = aktuell)."""
    folder = os.path.join(assets_dir, species_id)
    problems = []
    current = read_manifest(species_id, assets_dir)
    if current != json.loads(format_manifest(manifest)):
        problems.append(f"{species_id}/{MANIFEST_FILE}")
    for sub in (IMAGES_DIR, MASKS_DIR):
        present = set(os.listdir(os.path.join(folder, sub))) if os.path.isdir(os.path.join(folder, sub)) else set()
        extra = present - {f"{b}.png" for b in bones}
        problems += [f"{species_id}/{sub}/{name} (ueberzaehlig)" for name in sorted(extra)]
    for bone_id, (rgba, mask) in bones.items():
        image_path = os.path.join(folder, IMAGES_DIR, f"{bone_id}.png")
        mask_path = os.path.join(folder, MASKS_DIR, f"{bone_id}.png")
        if not os.path.isfile(image_path):
            problems.append(f"{species_id}/{IMAGES_DIR}/{bone_id}.png (fehlt)")
        else:
            old = _decode(image_path, "RGBA")
            if old.shape != rgba.shape or (
                    np.abs(old.astype(int) - rgba.astype(int)).max(axis=2) > CHECK_CHANNEL_TOLERANCE
            ).mean() > CHECK_PIXEL_FRACTION:
                problems.append(f"{species_id}/{IMAGES_DIR}/{bone_id}.png")
        if not os.path.isfile(mask_path):
            problems.append(f"{species_id}/{MASKS_DIR}/{bone_id}.png (fehlt)")
        else:
            old = _decode(mask_path, "L") > 0
            if old.shape != mask.shape or (old != mask).mean() > CHECK_PIXEL_FRACTION:
                problems.append(f"{species_id}/{MASKS_DIR}/{bone_id}.png")
    return problems


def write_species(species_id, manifest, bones, assets_dir=ASSETS_DIR):
    """Schreibt Manifest, Bilder und Masken. Unveraenderte Dateien bleiben unberuehrt; Dateien von
    Knochen, die es nicht mehr gibt, werden entfernt. Liefert die geschriebenen Pfade."""
    folder = os.path.join(assets_dir, species_id)
    written = []
    for sub in (IMAGES_DIR, MASKS_DIR):
        os.makedirs(os.path.join(folder, sub), exist_ok=True)
        for name in sorted(os.listdir(os.path.join(folder, sub))):
            if name not in {f"{b}.png" for b in bones}:
                os.remove(os.path.join(folder, sub, name))
                written.append(os.path.join(folder, sub, name))
    stale = set(compare_species(species_id, manifest, bones, assets_dir))
    for bone_id, (rgba, mask) in bones.items():
        image_rel = f"{species_id}/{IMAGES_DIR}/{bone_id}.png"
        mask_rel = f"{species_id}/{MASKS_DIR}/{bone_id}.png"
        if image_rel in stale or image_rel + " (fehlt)" in stale:
            path = os.path.join(assets_dir, image_rel)
            with open(path, "wb") as f:
                f.write(encode_png(Image.fromarray(rgba, "RGBA")))
            written.append(path)
        if mask_rel in stale or mask_rel + " (fehlt)" in stale:
            path = os.path.join(assets_dir, mask_rel)
            with open(path, "wb") as f:
                f.write(encode_png(Image.fromarray(mask.astype(np.uint8) * 255, "L").convert("1")))
            written.append(path)
    manifest_path = os.path.join(folder, MANIFEST_FILE)
    text = format_manifest(manifest)
    old = None
    if os.path.isfile(manifest_path):
        with open(manifest_path, encoding="utf-8") as f:
            old = f.read()
    if old != text:
        with open(manifest_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        written.append(manifest_path)
    return written


def contact_sheet(rendered, path, columns=12, cell=128):
    """Kontaktbogen aller Knochen (gleicher Massstab je Art, auf die Zellgroesse verkleinert)."""
    items = [(sid, bid, rgba) for sid, bones in rendered for bid, (rgba, _m) in bones.items()]
    if not items:
        return
    largest = max(max(r.shape[0], r.shape[1]) for _s, _b, r in items)
    scale = min(1.0, (cell - 16) / largest)
    rows = (len(items) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * cell, rows * cell), (92, 72, 52, 255))
    draw = ImageDraw.Draw(sheet)
    for i, (_sid, bone_id, rgba) in enumerate(items):
        img = Image.fromarray(rgba, "RGBA")
        w = max(1, int(round(img.width * scale)))
        h = max(1, int(round(img.height * scale)))
        img = img.resize((w, h), Image.LANCZOS)
        cx = (i % columns) * cell
        cy = (i // columns) * cell
        sheet.alpha_composite(img, (cx + (cell - w) // 2, cy + (cell - 12 - h) // 2))
        draw.text((cx + 3, cy + cell - 12), bone_id, fill=(235, 225, 205, 255))
    sheet.save(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Knochenbilder und Umrissmasken aus den 3D-Modellen rendern.")
    parser.add_argument("species", nargs="*", help="Art-IDs (Standard: alle Modellordner)")
    parser.add_argument("--all", action="store_true", help="alle Arten mit Modellordner (Standard ohne Art-IDs)")
    parser.add_argument("--check", action="store_true",
                        help="nichts schreiben, nur pruefen, ob die Ausgabe aktuell ist (Parameter aus dem Manifest)")
    parser.add_argument("--px-per-unit", type=float, help=f"Pixel pro Modelleinheit (Standard {DEFAULTS['pxPerUnit']})")
    parser.add_argument("--view", choices=sorted(VIEWS), help=f"Blickrichtung (Standard {DEFAULTS['view']})")
    parser.add_argument("--supersample", type=int, help=f"Ueberabtastung je Achse (Standard {DEFAULTS['supersample']})")
    parser.add_argument("--padding", type=int, help=f"freier Rand in Pixeln (Standard {DEFAULTS['padding']})")
    parser.add_argument("--preview", help="Kontaktbogen aller Knochen als PNG hierhin schreiben")
    parser.add_argument("--models-dir", default=MODELS_DIR, help=argparse.SUPPRESS)
    parser.add_argument("--assets-dir", default=ASSETS_DIR, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    species_ids = args.species if args.species and not args.all else model_species(args.models_dir)
    overrides = {"pxPerUnit": args.px_per_unit, "view": args.view,
                 "supersample": args.supersample, "padding": args.padding}
    overrides = {k: v for k, v in overrides.items() if v is not None}
    if "pxPerUnit" in overrides and float(overrides["pxPerUnit"]).is_integer():
        overrides["pxPerUnit"] = int(overrides["pxPerUnit"])
    if args.check and overrides:
        print("Fehler: --check nimmt die Parameter aus dem Manifest", file=sys.stderr)
        return 2

    problems = []
    written = []
    rendered = []
    try:
        for species_id in species_ids:
            # Parameter: Manifest der Art (falls vorhanden), sonst Standard; Optionen ueberschreiben.
            current = read_manifest(species_id, args.assets_dir)
            params = dict(DEFAULTS)
            if current is not None:
                params.update(current.get("params", {}))
            params.update(overrides)
            manifest, bones = render_species(species_id, params, args.models_dir)
            rendered.append((species_id, bones))
            if args.check:
                problems += compare_species(species_id, manifest, bones, args.assets_dir)
            else:
                written += write_species(species_id, manifest, bones, args.assets_dir)
    except BoneImageError as e:
        print(f"Fehler: {e}", file=sys.stderr)
        return 2
    if args.preview:
        contact_sheet(rendered, args.preview)
    if args.check:
        if problems:
            print("Nicht aktuell (bone_images.py ausfuehren): " + ", ".join(problems), file=sys.stderr)
            return 1
        print(f"Aktuell: {', '.join(species_ids)}")
        return 0
    print(f"Geschrieben: {len(written)} Dateien" if written else "Nichts geschrieben (schon aktuell)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
