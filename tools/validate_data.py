#!/usr/bin/env python3
"""Prueft die Spieldaten gegen die JSON-Schemas (GAME_DESIGN.md, Kapitel 11).

Ergaenzt DataValidationTest: Die Schemas pruefen die Struktur, der Kotlin-Test zusaetzlich
Referenzen und Vollstaendigkeit. Aufruf aus dem Projektverzeichnis:

    pip install jsonschema
    python3 tools/validate_data.py
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "core" / "src" / "main" / "resources"
DATA = RES / "data"
SCHEMA = RES / "schema"

# Datendatei -> Schema
FILES = {
    "layers.json": "layers.schema.json",
    "species.json": "species.schema.json",
    "bones.json": "bones.schema.json",
    "generators.json": "generators.schema.json",
    "upgrades.json": "upgrades.schema.json",
    "skilltree.json": "skilltree.schema.json",
    "milestones.json": "milestones.schema.json",
    "balance.json": "balance.schema.json",
}


def load(path: Path):
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def main() -> int:
    targets = dict(FILES)
    for locale in sorted((DATA / "locale").glob("*.json")):
        targets[f"locale/{locale.name}"] = "locale.schema.json"

    errors = 0
    for data_file, schema_file in targets.items():
        validator = Draft202012Validator(load(SCHEMA / schema_file))
        problems = sorted(validator.iter_errors(load(DATA / data_file)), key=lambda e: list(e.path))
        for p in problems:
            location = "/".join(str(x) for x in p.path) or "(Wurzel)"
            print(f"{data_file}: {location}: {p.message}")
        errors += len(problems)
        if not problems:
            print(f"{data_file}: ok")

    if errors:
        print(f"{errors} Fehler gefunden.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
