#!/usr/bin/env python3
"""Wandelt Skelett-SVGs in JSON fuer die App um (GAME_DESIGN.md, Kapitel 8.5).

Eingabe:  art/skeletons/<name>.svg
Ausgabe:  core/src/main/resources/data/skeletons/skeleton_<name>.json

Konvention fuer die SVGs:
- Das Wurzelelement hat ein viewBox-Attribut mit vier Zahlen (Breite und Hoehe > 0).
- Jeder Knochen ist eine Gruppe ``<g id="<boneId>">``, jede Zone darin ein Pfad
  ``<path id="<boneId>__<zoneId>" d="...">``. IDs sind snake_case.
- In einer Knochengruppe sind nur Zonen-Pfade erlaubt (Formen wie rect/circle vorher in Pfade
  umwandeln). Knochengruppen duerfen nicht verschachtelt sein.
- ``transform``-Attribute sind verboten (an jedem Element). Das Skript loest sie nicht auf,
  damit die Koordinaten im JSON genau denen im SVG entsprechen; beim Zeichnen Transformationen
  vor dem Export anwenden (z. B. Inkscape: "Objekt in Pfad umwandeln" und Gruppen aufloesen).
- Alles ausserhalb von Knochengruppen (Hintergrund, Hilfslinien) wird ignoriert.
- Reihenfolge der Knochen = Zeichenreihenfolge in der App, Reihenfolge der Zonen muss der
  Reihenfolge in bones.json entsprechen (prueft DataValidationTest).

Aufruf:
  python3 tools/svg2json.py            # alle SVGs umwandeln
  python3 tools/svg2json.py --check    # nur pruefen, ob die JSONs aktuell sind (CI)

Nur Python-Standardbibliothek.
"""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SRC = ROOT / "art" / "skeletons"
DEFAULT_OUT = ROOT / "core" / "src" / "main" / "resources" / "data" / "skeletons"

SVG_NS = "{http://www.w3.org/2000/svg}"
ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]*$")
ZONE_ID_PATTERN = re.compile(r"^([a-z][a-z0-9_]*?)__([a-z][a-z0-9_]*)$")
NUMBER_SPLIT = re.compile(r"[\s,]+")


class SvgError(Exception):
    """Fehler in einer SVG-Datei; die Meldung nennt Datei und Element."""


def local_name(tag):
    """Tag ohne Namensraum."""
    if not isinstance(tag, str):
        return ""
    return tag[len(SVG_NS):] if tag.startswith(SVG_NS) else tag.split("}")[-1]


def parse_view_box(value, source):
    if value is None:
        raise SvgError(f"{source}: viewBox fehlt am <svg>-Element")
    parts = [p for p in NUMBER_SPLIT.split(value.strip()) if p]
    if len(parts) != 4:
        raise SvgError(f"{source}: viewBox '{value}' braucht vier Zahlen")
    try:
        x, y, width, height = (float(p) for p in parts)
    except ValueError:
        raise SvgError(f"{source}: viewBox '{value}' enthaelt keine Zahl") from None
    if width <= 0 or height <= 0:
        raise SvgError(f"{source}: viewBox '{value}' braucht Breite und Hoehe > 0")
    return {"x": x, "y": y, "width": width, "height": height}


def normalize_path_data(d):
    """Fasst Leerraum zusammen; die Pfadsyntax selbst bleibt unveraendert."""
    return " ".join(d.split())


def convert_svg_text(text, name, source="<svg>"):
    """Wandelt den Inhalt einer SVG-Datei in das JSON-Objekt um (siehe Moduldokumentation)."""
    try:
        root = ET.fromstring(text)
    except ET.ParseError as e:
        raise SvgError(f"{source}: kein gueltiges XML ({e})") from None
    if local_name(root.tag) != "svg":
        raise SvgError(f"{source}: Wurzelelement ist nicht <svg>")
    view_box = parse_view_box(root.get("viewBox"), source)
    if root.get("transform") is not None:
        raise SvgError(f"{source}: transform am <svg>-Element ist nicht erlaubt")

    bones = []
    seen_bones = set()

    def walk(element, bone):
        tag = local_name(element.tag)
        element_id = element.get("id")
        where = f"{source}: <{tag}{' id=' + repr(element_id) if element_id else ''}>"
        if element.get("transform") is not None:
            raise SvgError(f"{where}: transform ist nicht erlaubt (vor dem Export anwenden)")
        if tag in ("defs", "metadata", "title", "desc", "style"):
            return
        zone_match = ZONE_ID_PATTERN.match(element_id) if element_id else None

        if bone is not None:
            # Innerhalb einer Knochengruppe: nur Zonen-Pfade.
            if tag == "g":
                raise SvgError(f"{where}: Gruppen in Knochengruppe '{bone['id']}' sind nicht erlaubt")
            if tag != "path":
                raise SvgError(f"{where}: in Knochengruppe '{bone['id']}' sind nur <path> erlaubt")
            if zone_match is None:
                raise SvgError(f"{where}: Pfad braucht id '{bone['id']}__<zoneId>'")
            bone_id, zone_id = zone_match.groups()
            if bone_id != bone["id"]:
                raise SvgError(f"{where}: Zone gehoert zu '{bone_id}', liegt aber in Gruppe '{bone['id']}'")
            if any(z["id"] == zone_id for z in bone["zones"]):
                raise SvgError(f"{where}: Zone '{zone_id}' doppelt")
            d = normalize_path_data(element.get("d") or "")
            if not d:
                raise SvgError(f"{where}: d-Attribut fehlt oder ist leer")
            if d[0] not in "Mm":
                raise SvgError(f"{where}: Pfad muss mit M/m beginnen")
            bone["zones"].append({"id": zone_id, "d": d})
            return

        if zone_match is not None:
            raise SvgError(f"{where}: Zonen-Pfad ausserhalb der Gruppe '{zone_match.group(1)}'")

        if tag == "g" and element_id and is_bone_group(element, element_id):
            if not ID_PATTERN.match(element_id):
                raise SvgError(f"{where}: Knochen-ID ist nicht snake_case")
            if element_id in seen_bones:
                raise SvgError(f"{where}: Knochen '{element_id}' doppelt")
            seen_bones.add(element_id)
            new_bone = {"id": element_id, "zones": []}
            for child in element:
                walk(child, new_bone)
            bones.append(new_bone)
            return

        for child in element:
            walk(child, None)

    for child in root:
        walk(child, None)
    if not bones:
        raise SvgError(f"{source}: keine Knochengruppen gefunden")
    return {"name": name, "viewBox": view_box, "bones": bones}


def is_bone_group(group, group_id):
    """Eine Gruppe ist ein Knochen, wenn ein direktes Kind eine Zonen-ID '<group_id>__...' hat."""
    prefix = group_id + "__"
    return any((child.get("id") or "").startswith(prefix) for child in group)


def to_json_text(data):
    return json.dumps(data, indent=2, ensure_ascii=True) + "\n"


def output_name(svg_path):
    return f"skeleton_{svg_path.stem}.json"


def main(argv=None):
    parser = argparse.ArgumentParser(description="Skelett-SVGs nach JSON umwandeln (Kapitel 8.5).")
    parser.add_argument("--src", type=Path, default=DEFAULT_SRC, help="Ordner mit den SVGs")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="Zielordner fuer die JSONs")
    parser.add_argument("--check", action="store_true", help="nur pruefen, ob die JSONs aktuell sind")
    args = parser.parse_args(argv)

    svgs = sorted(args.src.glob("*.svg"))
    if not svgs:
        print(f"Keine SVGs in {args.src}", file=sys.stderr)
        return 1
    if not args.check:
        args.out.mkdir(parents=True, exist_ok=True)

    failed = False
    for svg in svgs:
        if not ID_PATTERN.match(svg.stem):
            print(f"{svg}: Dateiname ist nicht snake_case", file=sys.stderr)
            failed = True
            continue
        try:
            text = to_json_text(convert_svg_text(svg.read_text(encoding="utf-8"), svg.stem, str(svg)))
        except SvgError as e:
            print(e, file=sys.stderr)
            failed = True
            continue
        target = args.out / output_name(svg)
        if args.check:
            current = target.read_text(encoding="utf-8") if target.exists() else None
            if current != text:
                print(f"{target}: veraltet, bitte 'python3 tools/svg2json.py' ausfuehren", file=sys.stderr)
                failed = True
            else:
                print(f"{target}: aktuell")
        else:
            target.write_text(text, encoding="utf-8")
            print(f"{svg} -> {target}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
