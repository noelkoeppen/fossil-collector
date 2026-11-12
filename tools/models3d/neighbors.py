#!/usr/bin/env python3
"""Leitet Knochenliste und Nachbarschaftsgraph einer Art aus ihrem 3D-Modell ab.

Quelle pro Art ist der Modellordner art/models3d/<speciesId>/:
- bones.json: Knochenliste (Reihenfolge, Namen de/en, Datei je Knochen)
- bones/<id>.glb: ein Mesh pro Knochen im gemeinsamen Skelett-Koordinatensystem
- neighbors_overrides.json (optional): anatomische Korrekturen (add/remove)

Zwei Knochen sind Nachbarn, wenn der kleinste Abstand ihrer Mesh-Oberflaechen (Abstand der
naechsten Eckpunkte) hoechstens --threshold Modelleinheiten betraegt. Danach werden die Korrekturen
angewendet. Der Graph muss zusammenhaengend sein, sonst bricht das Skript ab.

Geschrieben werden:
- core/src/main/resources/data/species.json: Felder "bones" und "neighbors" der Art
  (die Art muss dort schon mit "layer" und "rarityWeight" angelegt sein)
- core/src/main/resources/data/locale/{de,en}.json: Schluessel bone.<speciesId>.<boneId>.name

Aufruf (aus dem Repo-Wurzelverzeichnis):
    python3 tools/models3d/neighbors.py                # alle Arten mit Modellordner
    python3 tools/models3d/neighbors.py cave_bear      # nur eine Art
    python3 tools/models3d/neighbors.py --check        # nichts schreiben, Abweichung = Exit-Code 1

Abhaengigkeiten: numpy, scipy.
"""

import argparse
import json
import os
import struct
import sys

import numpy as np
from scipy.spatial import cKDTree

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
MODELS_DIR = os.path.join(REPO, "art", "models3d")
DATA_DIR = os.path.join(REPO, "core", "src", "main", "resources", "data")
LANGUAGES = ("de", "en")

# Standard-Schwelle in Modelleinheiten (1 Einheit = Schaedellaenge). Kleinster Wert, bei dem der
# Graph des Hoehlenbaeren zusammenhaengt (Atlas-Hinterhaupt 0.026, Brustbein-Rippe 1 0.029).
DEFAULT_THRESHOLD = 0.03

OVERRIDES_FILE = "neighbors_overrides.json"
GLB_MAGIC = 0x46546C67
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942


class NeighborError(Exception):
    """Fehler in den Modelldaten oder Korrekturen."""


# --- Laden ---------------------------------------------------------------------------------


def read_glb_vertices(path):
    """Liefert alle Eckpunkte (N x 3, float64) der Meshes einer .glb-Datei.

    Knoten-Transformationen werden nicht unterstuetzt: Die Modelle liegen bereits im gemeinsamen
    Skelett-Koordinatensystem. Eine Transformation fuehrt zu einem Fehler statt zu falschen Daten.
    """
    with open(path, "rb") as f:
        data = f.read()
    magic, _version, _length = struct.unpack_from("<III", data, 0)
    if magic != GLB_MAGIC:
        raise NeighborError(f"{path}: keine GLB-Datei")
    offset = 12
    gltf = None
    binary = None
    while offset < len(data):
        chunk_len, chunk_type = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8: offset + 8 + chunk_len]
        if chunk_type == CHUNK_JSON:
            gltf = json.loads(chunk.decode("utf-8"))
        elif chunk_type == CHUNK_BIN:
            binary = chunk
        offset += 8 + chunk_len
    if gltf is None or binary is None:
        raise NeighborError(f"{path}: JSON- oder BIN-Block fehlt")
    for node in gltf.get("nodes", []):
        if any(k in node for k in ("matrix", "translation", "rotation", "scale")):
            raise NeighborError(f"{path}: Knoten-Transformationen werden nicht unterstuetzt")
    parts = []
    for mesh in gltf.get("meshes", []):
        for prim in mesh.get("primitives", []):
            acc = gltf["accessors"][prim["attributes"]["POSITION"]]
            if acc.get("componentType") != 5126 or acc.get("type") != "VEC3":
                raise NeighborError(f"{path}: POSITION ist nicht float VEC3")
            view = gltf["bufferViews"][acc["bufferView"]]
            stride = view.get("byteStride", 12)
            start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
            parts.append(np.ndarray(shape=(acc["count"], 3), dtype="<f4", buffer=binary,
                                    offset=start, strides=(stride, 4)))
    if not parts:
        raise NeighborError(f"{path}: keine Meshes")
    return np.vstack(parts).astype(np.float64)


def load_model(species_id, models_dir=MODELS_DIR):
    """Liest bones.json und die Meshes einer Art. Liefert (bones, vertices je id)."""
    folder = os.path.join(models_dir, species_id)
    with open(os.path.join(folder, "bones.json"), encoding="utf-8") as f:
        model = json.load(f)
    if model.get("species") != species_id:
        raise NeighborError(f"{folder}/bones.json: 'species' ist nicht '{species_id}'")
    bones = model["bones"]
    ids = [b["id"] for b in bones]
    if len(set(ids)) != len(ids):
        raise NeighborError(f"{folder}/bones.json: doppelte Knochen-IDs")
    vertices = {}
    for bone in bones:
        expected = bone["id"] + ".glb"
        if bone["file"] != expected:
            raise NeighborError(f"{folder}/bones.json: Datei von '{bone['id']}' muss '{expected}' heissen")
        path = os.path.join(folder, "bones", bone["file"])
        if not os.path.isfile(path):
            raise NeighborError(f"{path}: Modell fehlt")
        vertices[bone["id"]] = read_glb_vertices(path)
    return bones, vertices


def load_overrides(species_id, models_dir=MODELS_DIR):
    """Liest die Korrekturdatei einer Art; ohne Datei keine Korrekturen."""
    path = os.path.join(models_dir, species_id, OVERRIDES_FILE)
    if not os.path.isfile(path):
        return {"add": [], "remove": []}
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    unknown = set(raw) - {"description", "add", "remove"}
    if unknown:
        raise NeighborError(f"{path}: unbekannte Felder {sorted(unknown)}")
    return {"add": raw.get("add", []), "remove": raw.get("remove", [])}


# --- Graph ---------------------------------------------------------------------------------


def edge_key(a, b, order):
    """Kante als Tupel in Knochenreihenfolge (kleinerer Index zuerst)."""
    return (a, b) if order[a] < order[b] else (b, a)


def derive_edges(ids, vertices, threshold):
    """Kanten zwischen allen Knochen, deren Oberflaechen hoechstens `threshold` auseinanderliegen."""
    order = {bone_id: i for i, bone_id in enumerate(ids)}
    lo = {i: vertices[i].min(axis=0) for i in ids}
    hi = {i: vertices[i].max(axis=0) for i in ids}
    trees = {}
    edges = set()
    for i, a in enumerate(ids):
        for b in ids[i + 1:]:
            gap = np.maximum(lo[a] - hi[b], lo[b] - hi[a]).max()
            if gap > threshold:
                continue
            if b not in trees:
                trees[b] = cKDTree(vertices[b])
            dist, _ = trees[b].query(vertices[a], k=1, distance_upper_bound=threshold * 1.000001)
            if np.isfinite(dist).any() and dist.min() <= threshold:
                edges.add(edge_key(a, b, order))
    return edges


def apply_overrides(ids, edges, overrides):
    """Wendet Korrekturen an. Veraltete Eintraege (Kante fehlt bzw. existiert schon) sind Fehler."""
    order = {bone_id: i for i, bone_id in enumerate(ids)}
    result = set(edges)

    def key_of(entry, kind):
        pair = entry["bones"] if isinstance(entry, dict) else entry
        if len(pair) != 2 or pair[0] == pair[1]:
            raise NeighborError(f"{kind}: ungueltige Kante {pair}")
        for bone_id in pair:
            if bone_id not in order:
                raise NeighborError(f"{kind}: unbekannter Knochen '{bone_id}'")
        return edge_key(pair[0], pair[1], order)

    for entry in overrides.get("remove", []):
        key = key_of(entry, "remove")
        if key not in result:
            raise NeighborError(f"remove: Kante {list(key)} wird gar nicht abgeleitet")
        result.remove(key)
    for entry in overrides.get("add", []):
        key = key_of(entry, "add")
        if key in result:
            raise NeighborError(f"add: Kante {list(key)} existiert bereits")
        result.add(key)
    return result


def components(ids, edges):
    """Zusammenhangskomponenten als Liste von Mengen (erste enthaelt ids[0])."""
    adjacency = {i: set() for i in ids}
    for a, b in edges:
        adjacency[a].add(b)
        adjacency[b].add(a)
    seen = set()
    result = []
    for start in ids:
        if start in seen:
            continue
        comp = {start}
        stack = [start]
        while stack:
            for nxt in adjacency[stack.pop()]:
                if nxt not in comp:
                    comp.add(nxt)
                    stack.append(nxt)
        seen |= comp
        result.append(comp)
    return result


def sorted_edges(ids, edges):
    order = {bone_id: i for i, bone_id in enumerate(ids)}
    return sorted(edges, key=lambda e: (order[e[0]], order[e[1]]))


def build_species_graph(species_id, threshold=DEFAULT_THRESHOLD, models_dir=MODELS_DIR):
    """Knochen-IDs, Kanten und Namen einer Art aus Modell und Korrekturen."""
    bones, vertices = load_model(species_id, models_dir)
    ids = [b["id"] for b in bones]
    edges = derive_edges(ids, vertices, threshold)
    edges = apply_overrides(ids, edges, load_overrides(species_id, models_dir))
    comps = components(ids, edges)
    if len(comps) > 1:
        rest = sorted(set(ids) - comps[0])
        raise NeighborError(f"{species_id}: Graph nicht zusammenhaengend, getrennt: {rest}")
    names = {lang: {b["id"]: b["name"][lang] for b in bones} for lang in LANGUAGES}
    return ids, sorted_edges(ids, edges), names


# --- Ausgabe -------------------------------------------------------------------------------


def bone_name_key(species_id, bone_id):
    """Locale-Schluessel eines Knochennamens (wie LocaleKeys.boneName in core)."""
    return f"bone.{species_id}.{bone_id}.name"


def update_species(species_list, species_id, ids, edges):
    """Setzt bones/neighbors der Art; uebrige Felder und Arten bleiben unveraendert."""
    for entry in species_list:
        if entry["id"] == species_id:
            entry["bones"] = list(ids)
            entry["neighbors"] = [list(e) for e in edges]
            return species_list
    raise NeighborError(
        f"species.json: Art '{species_id}' fehlt. Erst mit 'id', 'layer' und 'rarityWeight' anlegen.")


def update_locale(table, species_id, names):
    """Ersetzt alle Knochennamen der Art; andere Schluessel behalten ihre Reihenfolge."""
    prefix = f"bone.{species_id}."
    result = {k: v for k, v in table.items() if not k.startswith(prefix)}
    for bone_id, name in names.items():
        result[bone_name_key(species_id, bone_id)] = name
    return result


def format_species(species_list):
    """species.json lesbar: eine Zeile pro Knochenliste-Abschnitt und pro Kante."""
    out = ["["]
    for si, entry in enumerate(species_list):
        out.append("  {")
        fields = list(entry.items())
        for fi, (key, value) in enumerate(fields):
            comma = "," if fi < len(fields) - 1 else ""
            if key == "bones":
                out.append(f'    "bones": [')
                for bi, bone_id in enumerate(value):
                    sep = "," if bi < len(value) - 1 else ""
                    out.append(f"      {json.dumps(bone_id)}{sep}")
                out.append(f"    ]{comma}")
            elif key == "neighbors":
                out.append(f'    "neighbors": [')
                for ei, edge in enumerate(value):
                    sep = "," if ei < len(value) - 1 else ""
                    out.append(f"      {json.dumps(edge)}{sep}")
                out.append(f"    ]{comma}")
            else:
                out.append(f"    {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}{comma}")
        out.append("  }" + ("," if si < len(species_list) - 1 else ""))
    out.append("]")
    return "\n".join(out) + "\n"


def format_locale(table):
    return json.dumps(table, ensure_ascii=False, indent=2) + "\n"


def model_species(models_dir=MODELS_DIR):
    """Alle Arten mit Modellordner (Unterordner mit bones.json), alphabetisch."""
    return sorted(
        name for name in os.listdir(models_dir)
        if os.path.isfile(os.path.join(models_dir, name, "bones.json"))
    )


def generate(species_ids, threshold, models_dir=MODELS_DIR, data_dir=DATA_DIR):
    """Erzeugt die neuen Dateiinhalte. Liefert {Pfad: Text}."""
    species_path = os.path.join(data_dir, "species.json")
    with open(species_path, encoding="utf-8") as f:
        species_list = json.load(f)
    locale_paths = {lang: os.path.join(data_dir, "locale", f"{lang}.json") for lang in LANGUAGES}
    locales = {}
    for lang, path in locale_paths.items():
        with open(path, encoding="utf-8") as f:
            locales[lang] = json.load(f)
    for species_id in species_ids:
        ids, edges, names = build_species_graph(species_id, threshold, models_dir)
        update_species(species_list, species_id, ids, edges)
        for lang in LANGUAGES:
            locales[lang] = update_locale(locales[lang], species_id, names[lang])
    outputs = {species_path: format_species(species_list)}
    for lang, path in locale_paths.items():
        outputs[path] = format_locale(locales[lang])
    return outputs


def main(argv=None):
    parser = argparse.ArgumentParser(description="Nachbarschaftsgraph aus den 3D-Modellen ableiten.")
    parser.add_argument("species", nargs="*", help="Art-IDs (Standard: alle Modellordner)")
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                        help=f"maximaler Oberflaechenabstand in Modelleinheiten (Standard {DEFAULT_THRESHOLD})")
    parser.add_argument("--check", action="store_true",
                        help="nichts schreiben, nur pruefen, ob die Daten aktuell sind")
    parser.add_argument("--models-dir", default=MODELS_DIR, help=argparse.SUPPRESS)
    parser.add_argument("--data-dir", default=DATA_DIR, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    species_ids = args.species or model_species(args.models_dir)
    try:
        outputs = generate(species_ids, args.threshold, args.models_dir, args.data_dir)
    except NeighborError as e:
        print(f"Fehler: {e}", file=sys.stderr)
        return 2

    stale = []
    for path, text in outputs.items():
        with open(path, encoding="utf-8") as f:
            current = f.read()
        if current != text:
            stale.append(path)
            if not args.check:
                with open(path, "w", encoding="utf-8", newline="\n") as f:
                    f.write(text)
    rel = [os.path.relpath(p, REPO) for p in stale]
    if args.check:
        if stale:
            print("Nicht aktuell (neighbors.py ausfuehren): " + ", ".join(rel), file=sys.stderr)
            return 1
        print(f"Aktuell: {', '.join(species_ids)}")
        return 0
    print("Geschrieben: " + (", ".join(rel) if rel else "nichts (schon aktuell)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
