#!/usr/bin/env python3
"""Rechnet aus den Umrissmasken der Knochenbilder die Umrisse im Raster der Grabungsflaeche.

Quelle pro Art sind die Ausgaben von bone_images.py (AP2-06):

    app/src/main/assets/bones/<speciesId>/manifest.json         pxPerUnit, Bildgroessen
    app/src/main/assets/bones/<speciesId>/masks/<boneId>.png    1 Bit, 1 = Knochen

und aus den Spieldaten `balance.json: excavation.cellsPerUnit` (Zellen pro Modelleinheit, ein
Balancing-Wert, der nur dort gepflegt wird) sowie die Knochenreihenfolge aus `species.json`.

Ziel (Spieldaten, von core geladen):

    core/src/main/resources/data/footprints/<speciesId>.json

Verfahren pro Knochen (ohne Drehung, GAME_DESIGN.md Kapitel 10/11):
1. Eine Zelle ist pxPerUnit / cellsPerUnit Maskenpixel breit (Hoehlenbaer: 384 / 8 = 48).
2. Das Zellraster wird in Schritten von `offsetStep` Pixeln gegen die Maske verschoben. Fuer jede Lage
   gilt eine Zelle als belegt, wenn mindestens `minCoverage` ihrer Flaeche Knochen ist. Hat keine
   Zelle genug Anteil, ist die Zelle mit dem groessten Anteil belegt (jeder Knochen hat >= 1 Zelle).
3. Gewaehlt wird die Lage mit den wenigsten Zellen, bei Gleichstand die mit den meisten abgedeckten
   Maskenpixeln, dann die kleinste Verschiebung (y, dann x). Das Ergebnis ist deterministisch.
4. Gespeichert werden die belegten Zellen als Zeilen (`#` belegt, `.` frei, umschliessendes Rechteck)
   und die linke obere Bildecke in Maskenpixeln relativ zur linken oberen Ecke dieses Rechtecks
   (`imageLeft`, `imageTop`), damit die App das Bild passend zu den Zellen zeichnet.

Aufruf (aus dem Repo-Wurzelverzeichnis):
    python3 tools/models3d/bone_footprints.py              # alle Arten mit Knochenbildern
    python3 tools/models3d/bone_footprints.py cave_bear    # nur eine Art
    python3 tools/models3d/bone_footprints.py --check      # nichts schreiben, Abweichung = Exit-Code 1
    python3 tools/models3d/bone_footprints.py --min-coverage 0.1   # andere Schwelle ausprobieren

Abhaengigkeiten: numpy, Pillow.
"""

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
ASSETS_DIR = os.path.join(REPO, "app", "src", "main", "assets", "bones")
DATA_DIR = os.path.join(REPO, "core", "src", "main", "resources", "data")

FOOTPRINTS_DIR = "footprints"
FORMAT_VERSION = 1
OCCUPIED = "#"
FREE = "."

DEFAULTS = {
    # Mindestanteil Knochen an der Zellflaeche, damit die Zelle belegt ist.
    "minCoverage": 0.05,
    # Schrittweite (Maskenpixel) beim Verschieben des Zellrasters gegen die Maske.
    "offsetStep": 4,
}


class FootprintError(Exception):
    pass


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def cells_per_unit(data_dir=DATA_DIR):
    """`excavation.cellsPerUnit` aus balance.json (einzige Quelle des Werts)."""
    balance = read_json(os.path.join(data_dir, "balance.json"))
    try:
        value = balance["excavation"]["cellsPerUnit"]
    except KeyError as e:
        raise FootprintError("balance.json: excavation.cellsPerUnit fehlt") from e
    if not isinstance(value, int) or value < 1:
        raise FootprintError(f"balance.json: excavation.cellsPerUnit muss eine ganze Zahl >= 1 sein, ist {value!r}")
    return value


def species_bones(species_id, data_dir=DATA_DIR):
    """Knochen-IDs der Art in der Reihenfolge von species.json."""
    for entry in read_json(os.path.join(data_dir, "species.json")):
        if entry["id"] == species_id:
            return list(entry["bones"])
    raise FootprintError(f"species.json: Art '{species_id}' fehlt")


def asset_species(assets_dir=ASSETS_DIR):
    """Alle Arten mit Knochenbildern (Ordner mit manifest.json)."""
    if not os.path.isdir(assets_dir):
        return []
    return sorted(d for d in os.listdir(assets_dir) if os.path.isfile(os.path.join(assets_dir, d, "manifest.json")))


def read_mask(path):
    with Image.open(path) as img:
        return np.asarray(img.convert("L")) > 0


def footprint(mask, cell_px, min_coverage, offset_step):
    """Umriss einer Maske: (Zeilen, imageLeft, imageTop, Zellen, abgedeckter Anteil der Maskenpixel)."""
    h, w = mask.shape
    total = int(mask.sum())
    if total == 0:
        raise FootprintError("Maske ohne Knochenpixel")
    best = None
    for oy in range(0, cell_px, offset_step):
        for ox in range(0, cell_px, offset_step):
            rows = (oy + h + cell_px - 1) // cell_px
            cols = (ox + w + cell_px - 1) // cell_px
            pad = np.zeros((rows * cell_px, cols * cell_px), dtype=bool)
            pad[oy:oy + h, ox:ox + w] = mask
            counts = pad.reshape(rows, cell_px, cols, cell_px).sum(axis=(1, 3))
            occupied = counts >= min_coverage * cell_px * cell_px
            if not occupied.any():
                # Mindestens eine Zelle: die mit dem groessten Anteil (bei Gleichstand die erste).
                occupied = np.zeros_like(occupied)
                occupied.flat[int(np.argmax(counts))] = True
            covered = int(counts[occupied].sum())
            key = (int(occupied.sum()), -covered, oy, ox)
            if best is None or key < best[0]:
                best = (key, occupied, ox, oy, covered)
    _key, occupied, ox, oy, covered = best
    ys, xs = np.nonzero(occupied)
    y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
    box = occupied[y0:y1 + 1, x0:x1 + 1]
    lines = ["".join(OCCUPIED if v else FREE for v in row) for row in box]
    image_left = int(ox - x0 * cell_px)
    image_top = int(oy - y0 * cell_px)
    return lines, image_left, image_top, int(box.sum()), covered / total


def build_species(species_id, params, assets_dir=ASSETS_DIR, data_dir=DATA_DIR):
    """Inhalt von footprints/<speciesId>.json und Kennzahlen je Knochen."""
    manifest_path = os.path.join(assets_dir, species_id, "manifest.json")
    if not os.path.isfile(manifest_path):
        raise FootprintError(f"{species_id}: manifest.json fehlt (zuerst bone_images.py ausfuehren)")
    manifest = read_json(manifest_path)
    px_per_unit = manifest["params"]["pxPerUnit"]
    cpu = cells_per_unit(data_dir)
    if px_per_unit % cpu != 0:
        raise FootprintError(f"{species_id}: pxPerUnit {px_per_unit} ist kein Vielfaches von cellsPerUnit {cpu}")
    cell_px = int(px_per_unit // cpu)
    min_coverage = float(params["minCoverage"])
    offset_step = int(params["offsetStep"])
    if not 0.0 < min_coverage <= 1.0:
        raise FootprintError(f"minCoverage muss in (0, 1] liegen, ist {min_coverage}")
    if not 1 <= offset_step <= cell_px:
        raise FootprintError(f"offsetStep muss in 1..{cell_px} liegen, ist {offset_step}")
    sizes = {b["id"]: (b["width"], b["height"]) for b in manifest["bones"]}
    bones = []
    stats = {}
    for bone_id in species_bones(species_id, data_dir):
        mask_path = os.path.join(assets_dir, species_id, "masks", f"{bone_id}.png")
        if not os.path.isfile(mask_path) or bone_id not in sizes:
            raise FootprintError(f"{species_id}/{bone_id}: Maske oder Manifesteintrag fehlt")
        mask = read_mask(mask_path)
        if (mask.shape[1], mask.shape[0]) != tuple(sizes[bone_id]):
            raise FootprintError(f"{species_id}/{bone_id}: Maskengroesse passt nicht zum Manifest")
        lines, left, top, count, kept = footprint(mask, cell_px, min_coverage, offset_step)
        bones.append({"id": bone_id, "rows": lines, "imageLeft": left, "imageTop": top})
        stats[bone_id] = (count, kept)
    content = {
        "formatVersion": FORMAT_VERSION,
        "species": species_id,
        "cellsPerUnit": cpu,
        "pxPerUnit": px_per_unit,
        "params": {"minCoverage": min_coverage, "offsetStep": offset_step},
        "bones": bones,
    }
    return content, stats


def format_footprints(content):
    """JSON mit einem Knochen pro Zeile (lesbare Diffs)."""
    head = {k: v for k, v in content.items() if k != "bones"}
    lines = ["{"]
    for key, value in head.items():
        lines.append(f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)},")
    lines.append('  "bones": [')
    bones = content["bones"]
    for i, bone in enumerate(bones):
        sep = "," if i < len(bones) - 1 else ""
        lines.append("    " + json.dumps(bone, ensure_ascii=False) + sep)
    lines.append("  ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


def footprint_path(species_id, data_dir=DATA_DIR):
    return os.path.join(data_dir, FOOTPRINTS_DIR, f"{species_id}.json")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Knochenumrisse in Zellen aus den Umrissmasken ableiten.")
    parser.add_argument("species", nargs="*", help="Art-IDs (Standard: alle Arten mit Knochenbildern)")
    parser.add_argument("--check", action="store_true",
                        help="nichts schreiben, nur pruefen, ob die Umrisse aktuell sind (Parameter aus der Datei)")
    parser.add_argument("--min-coverage", type=float,
                        help=f"Mindestanteil Knochen je Zelle (Standard {DEFAULTS['minCoverage']})")
    parser.add_argument("--offset-step", type=int,
                        help=f"Schrittweite der Rasterverschiebung in Pixeln (Standard {DEFAULTS['offsetStep']})")
    parser.add_argument("--stats", action="store_true", help="Kennzahlen je Knochen ausgeben")
    parser.add_argument("--assets-dir", default=ASSETS_DIR, help=argparse.SUPPRESS)
    parser.add_argument("--data-dir", default=DATA_DIR, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    overrides = {"minCoverage": args.min_coverage, "offsetStep": args.offset_step}
    overrides = {k: v for k, v in overrides.items() if v is not None}
    if args.check and overrides:
        print("Fehler: --check nimmt die Parameter aus der Datei", file=sys.stderr)
        return 2
    species_ids = args.species or asset_species(args.assets_dir)
    problems = []
    written = []
    try:
        for species_id in species_ids:
            path = footprint_path(species_id, args.data_dir)
            current = read_json(path) if os.path.isfile(path) else None
            params = dict(DEFAULTS)
            if current is not None:
                params.update(current.get("params", {}))
            params.update(overrides)
            content, stats = build_species(species_id, params, args.assets_dir, args.data_dir)
            if args.stats:
                cells = sum(c for c, _k in stats.values())
                kept = min(k for _c, k in stats.values())
                print(f"{species_id}: {len(stats)} Knochen, {cells} Zellen, kleinster Anteil abgedeckter Pixel {kept:.3f}")
                for bone_id, (count, share) in stats.items():
                    print(f"  {bone_id}: {count} Zellen, {share:.3f}")
            text = format_footprints(content)
            if args.check:
                if current is None:
                    problems.append(f"{species_id} (fehlt)")
                elif current != json.loads(text):
                    problems.append(species_id)
            else:
                old = None
                if os.path.isfile(path):
                    with open(path, encoding="utf-8") as f:
                        old = f.read()
                if old != text:
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    with open(path, "w", encoding="utf-8", newline="\n") as f:
                        f.write(text)
                    written.append(path)
    except FootprintError as e:
        print(f"Fehler: {e}", file=sys.stderr)
        return 2
    if args.check:
        if problems:
            print("Nicht aktuell (bone_footprints.py ausfuehren): " + ", ".join(problems), file=sys.stderr)
            return 1
        print(f"Aktuell: {', '.join(species_ids)}")
        return 0
    print(f"Geschrieben: {', '.join(written)}" if written else "Nichts geschrieben (schon aktuell)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
