#!/usr/bin/env python3
"""Hilfsgenerator fuer die Skelett-SVGs der Arten (AP-27, GAME_DESIGN.md Kapitel 8.5).

Die SVGs in art/skeletons/ bleiben die Quelle der Grafik. Dieses Skript ist nur ein
Zeichenwerkzeug: Es baut jede Zone aus parametrisierten Grundformen (Kurven, verjuengte
Knochen, Kreise, Umrisse mit Schnittlinien), passt die Figur in die viewBox ein und schneidet
ueberlappende Formen in Zeichenreihenfolge frei, sodass zwischen allen Zonen eine schmale Fuge
bleibt. Danach schreibt es art/skeletons/<speciesId>.svg nach der Konvention aus Kapitel 8.5
(Gruppe id="<boneId>", Pfad id="<boneId>__<zoneId>", keine transform-Attribute).

Aufruf (aus dem Repo-Wurzelverzeichnis):
  pip install shapely                       # nur fuer dieses Werkzeug, nicht fuer Build oder CI
  python3 tools/art/skeleton_gen.py         # alle Arten dieses Skripts neu zeichnen
  python3 tools/art/skeleton_gen.py cave_bear smilodon   # nur einzelne Arten
  python3 tools/art/skeleton_gen.py --list  # Zonenflaechen ausgeben (Kontrolle auf zu kleine Zonen)
Anschliessend wie immer: python3 tools/svg2json.py

Alle Arten blicken nach rechts. Koordinaten der Artdefinitionen sind frei gewaehlte
Zeichenkoordinaten (y nach unten); das Einpassen in die viewBox uebernimmt fit().
"""

import argparse
import json
import math
import sys
from pathlib import Path

try:
    from shapely import affinity
    from shapely.geometry import LineString, MultiPolygon, Point, Polygon
    from shapely.geometry.polygon import orient
    from shapely.ops import unary_union
except ImportError:  # pragma: no cover - Hinweis fuer Nutzer
    sys.exit("Dieses Werkzeug braucht shapely: pip install shapely")

ROOT = Path(__file__).resolve().parents[2]
ART_DIR = ROOT / "art" / "skeletons"
BONES_JSON = ROOT / "core" / "src" / "main" / "resources" / "data" / "bones.json"

VIEW_W, VIEW_H = 400, 200
MARGIN = 8.0
GAP = 1.8               # Fuge zwischen Zonen in viewBox-Einheiten
MIN_PART = 2.5          # kleinere Splitter nach dem Freischneiden werden verworfen
BONE_COLOR = "#E8DFC8"
BACKGROUND = "#1C1A17"
DRAW_ORDER = ["ribs", "dorsal", "cervical", "pelvis", "hindlimb", "forelimb", "jaw", "skull"]


# --------------------------------------------------------------------------- Geometrie

def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def norm(v):
    length = math.hypot(v[0], v[1]) or 1.0
    return (v[0] / length, v[1] / length)


def cr_point(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return tuple(0.5 * (2 * p1[i] + (-p0[i] + p2[i]) * t + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2
                        + (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3) for i in range(2))


def cr_open(pts, n=12):
    """Catmull-Rom-Kurve durch alle Punkte (offen)."""
    ext = [pts[0]] + list(pts) + [pts[-1]]
    out = []
    for i in range(1, len(ext) - 2):
        for k in range(n):
            out.append(cr_point(ext[i - 1], ext[i], ext[i + 1], ext[i + 2], k / n))
    out.append(pts[-1])
    return out


def cr_closed(pts, n=10):
    """Geschlossene Catmull-Rom-Kurve; doppelte Punkte ergeben Ecken."""
    out = []
    m = len(pts)
    for i in range(m):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % m], pts[(i + 2) % m]
        for k in range(n):
            out.append(cr_point(p0, p1, p2, p3, k / n))
    return out


def blob(pts, n=10):
    """Weiche geschlossene Form durch die Stuetzpunkte."""
    return Polygon(cr_closed(pts, n)).buffer(0)


def poly(pts):
    return Polygon(pts).buffer(0)


def circle(c, r):
    return Point(c).buffer(r, 32)


def ring(c, r, hole):
    return circle(c, r).difference(circle(c, hole))


def taper(pts, radii):
    """Verjuengter Strich: Huelle aufeinanderfolgender Kreise (Radien je Punkt)."""
    if len(radii) != len(pts):
        radii = [radii[0] + (radii[-1] - radii[0]) * i / (len(pts) - 1) for i in range(len(pts))]
    parts = []
    for i in range(len(pts) - 1):
        parts.append(unary_union([circle(pts[i], radii[i]), circle(pts[i + 1], radii[i + 1])]).convex_hull)
    return unary_union(parts)


def curve_taper(ctrl, r0, r1, n=10):
    pts = cr_open(ctrl, n)
    return taper(pts, [r0 + (r1 - r0) * i / (len(pts) - 1) for i in range(len(pts))])


def bone(a, b, ra, rm, rb):
    """Langknochen: Schaft mit verdickten Enden."""
    return taper([a, lerp(a, b, 0.22), lerp(a, b, 0.78), b], [ra, rm, rm, rb])


def rrect(center, tangent, half_len, half_h, r):
    """Abgerundetes Rechteck entlang einer Richtung."""
    t = norm(tangent)
    nrm = (t[1], -t[0])
    a, b = max(half_len - r, 0.1), max(half_h - r, 0.1)
    cx, cy = center
    corners = [(cx + t[0] * su * a + nrm[0] * sv * b, cy + t[1] * su * a + nrm[1] * sv * b)
               for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    return Polygon(corners).buffer(r, 8)


def local(origin, angle_deg, scale=1.0):
    """Abbildung lokaler Koordinaten (Teilzeichnung) auf Zeichenkoordinaten."""
    a = math.radians(angle_deg)
    ca, sa = math.cos(a), math.sin(a)

    def f(p):
        x, y = p[0] * scale, p[1] * scale
        return (origin[0] + x * ca - y * sa, origin[1] + x * sa + y * ca)
    return f


def mapped(f, pts):
    return [f(p) for p in pts]


class Curve:
    """Polylinie mit Bogenlaengen-Parameter u in [0, 1]."""

    def __init__(self, ctrl, n=16):
        self.pts = cr_open(ctrl, n)
        self.cum = [0.0]
        for i in range(1, len(self.pts)):
            self.cum.append(self.cum[-1] + math.dist(self.pts[i - 1], self.pts[i]))
        self.length = self.cum[-1]

    def at(self, u):
        s = max(0.0, min(1.0, u)) * self.length
        for i in range(1, len(self.pts)):
            if self.cum[i] >= s or i == len(self.pts) - 1:
                seg = self.cum[i] - self.cum[i - 1] or 1.0
                t = (s - self.cum[i - 1]) / seg
                p = lerp(self.pts[i - 1], self.pts[i], t)
                return p, norm((self.pts[i][0] - self.pts[i - 1][0], self.pts[i][1] - self.pts[i - 1][1]))
        return self.pts[-1], (1.0, 0.0)

    def sub(self, u0, u1, n=12):
        return [self.at(u0 + (u1 - u0) * k / n)[0] for k in range(n + 1)]


def interp(values, t):
    """Lineare Interpolation in einer Werteliste fuer t in [0, 1]."""
    if len(values) == 1:
        return values[0]
    x = max(0.0, min(1.0, t)) * (len(values) - 1)
    i = min(int(x), len(values) - 2)
    return values[i] + (values[i + 1] - values[i]) * (x - i)


def split_outline(outline, cuts, seeds):
    """Teilt einen Umriss mit Schnitt-Polylinien; jede Teilflaeche geht an den naechsten Saatpunkt.

    seeds: {zoneId: (x, y)}. Rueckgabe {zoneId: Geometrie}.
    """
    knife = unary_union([LineString(c).buffer(0.05) for c in cuts])
    pieces = outline.difference(knife)
    pieces = list(pieces.geoms) if hasattr(pieces, "geoms") else [pieces]
    result = {z: [] for z in seeds}
    for piece in pieces:
        if piece.area < 0.5:
            continue
        rp = piece.representative_point()
        best = None
        for z, s in seeds.items():
            d = 0.0 if piece.contains(Point(s)) else piece.distance(Point(s)) + 1e-3
            if best is None or d < best[0]:
                best = (d, z)
        result[best[1]].append(piece)
    return {z: unary_union(p) for z, p in result.items()}


# --------------------------------------------------------------------------- Bausteine

class Skeleton:
    def __init__(self):
        self.zones = {}

    def add(self, bone_id, zone_id, geom):
        key = (bone_id, zone_id)
        self.zones[key] = unary_union([self.zones[key], geom]) if key in self.zones else geom

    def cut(self, bone_id, zone_id, geom):
        self.zones[(bone_id, zone_id)] = self.zones[(bone_id, zone_id)].difference(geom)


def column(sk, bone_id, ctrl, n, h, spine_len, tilt=18.0, spine_w=(2.4, 1.4), wing=0.6,
           u0=0.0, u1=1.0):
    """Wirbelsaeulenabschnitt von hinten nach vorn: Wirbelkoerper, Dornfortsaetze und je ein
    Querfortsatz nach hinten (links) und vorn (rechts). Gibt die Wirbel-Rahmen zurueck."""
    curve = Curve(ctrl)
    step = (u1 - u0) / n
    half = curve.length * step / 2 * 0.86
    frames = []
    for i in range(n):
        p, t = curve.at(u0 + (i + 0.5) * step)
        nrm = (t[1], -t[0])
        b = h / 2
        sk.add(bone_id, "centrum", rrect(p, t, half, b, min(half, b) * 0.5))
        frames.append((p, t, nrm, b + h * wing, half))
        length = interp(spine_len, i / max(n - 1, 1))
        a = math.radians(tilt)
        d = (nrm[0] * math.cos(a) - t[0] * math.sin(a), nrm[1] * math.cos(a) - t[1] * math.sin(a))
        base = (p[0] + nrm[0] * (b + 0.6), p[1] + nrm[1] * (b + 0.6))
        tip = (base[0] + d[0] * length, base[1] + d[1] * length)
        sk.add(bone_id, "spinous", taper([base, tip], [spine_w[0], spine_w[1]]))
        # Querfortsaetze, vereinfacht als Lappen unter dem Wirbelkoerper, abwechselnd links (gerade
        # Wirbel) und rechts (ungerade Wirbel); die Rippen setzen darunter an.
        wh = h * wing

        def pt(u, v):
            return (p[0] + t[0] * u + nrm[0] * v, p[1] + t[1] * u + nrm[1] * v)
        shape = poly([pt(-half * 0.9, -b - 0.4), pt(half * 0.9, -b - 0.4), pt(half * 0.6, -b - wh),
                      pt(-half * 0.6, -b - wh)])
        sk.add(bone_id, "transverse_l" if i % 2 == 0 else "transverse_r", shape.buffer(-0.4).buffer(0.6))
    return frames


def tail(sk, ctrl, n, h0, h1):
    """Schwanzwirbel (als Teil der Wirbelkoerper-Zone der Rueckenwirbel, vereinfacht)."""
    curve = Curve(ctrl)
    for i in range(n):
        u = (i + 0.5) / n
        p, t = curve.at(u)
        h = h0 + (h1 - h0) * u
        half = curve.length / n / 2 * 0.8
        sk.add("dorsal", "centrum", rrect(p, t, half, h / 2, min(half, h / 2) * 0.55))


def ribs(sk, frames, belly_ctrl, bow=8.0, r=(2.6, 1.9), split=(0.15, 0.33, 0.78)):
    """Rippen von den Wirbeln (hinten nach vorn) zur Bauchlinie (hinten nach vorn)."""
    belly = Curve(belly_ctrl)
    k = len(frames)
    for i, (p, t, nrm, b, _half) in enumerate(frames):
        a = (p[0] - nrm[0] * (b + 1.2), p[1] - nrm[1] * (b + 1.2))
        e, _ = belly.at(i / max(k - 1, 1))
        mid = lerp(a, e, 0.5)
        c = (mid[0] - t[0] * bow, mid[1] - t[1] * bow)
        curve = Curve([a, c, e], 10)
        bounds = (0.0,) + tuple(split) + (1.0,)
        for zi, zone in enumerate(("head", "neck", "shaft", "end")):
            seg = curve.sub(bounds[zi], bounds[zi + 1], 10)
            rr = r[0] * (1.25 if zone == "head" else 1.0)
            r1 = r[0] + (r[1] - r[0]) * bounds[zi + 1]
            sk.add("ribs", zone, taper(seg, [rr, r1]))


def pelvis(sk, acet, ilium_pts, ischium_pts, pubis_pts, r=6.0, ischium_r=(3.6, 2.8), pubis_r=(3.2, 2.6)):
    sk.add("pelvis", "ilium", blob(ilium_pts))
    sk.add("pelvis", "ischium", curve_taper(ischium_pts, *ischium_r))
    sk.add("pelvis", "pubis", curve_taper(pubis_pts, *pubis_r))
    sk.add("pelvis", "acetabulum", ring(acet, r, r * 0.42))


def outlined_bone(sk, bone_id, outline, cuts, seeds, holes=(), extras=None):
    parts = split_outline(outline, cuts, seeds)
    for zone, geom in parts.items():
        sk.add(bone_id, zone, geom)
    for (zone, hole) in holes:
        sk.cut(bone_id, zone, hole)
    for zone, geom in (extras or {}).items():
        sk.add(bone_id, zone, geom)


# --------------------------------------------------------------------------- Arten

def cave_bear():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(122, 88), (160, 80), (205, 76), (252, 80)], 11, 10,
                    [10, 9, 10, 12, 15, 18], tilt=14)
    tail(sk, [(110, 92), (101, 97), (94, 104)], 3, 6, 4)
    ribs(sk, frames[3:], [(166, 126), (196, 142), (236, 144), (252, 134)], bow=9, r=(2.9, 2.2))
    column(sk, "cervical", [(262, 84), (280, 90), (296, 98)], 5, 10, [9, 8], tilt=-12, wing=0.65,
           spine_w=(2.8, 1.8))
    pelvis(sk, (118, 100), [(108, 98), (104, 90), (114, 82), (136, 80), (142, 85), (126, 94)],
           [(112, 106), (103, 112), (97, 113)], [(124, 106), (132, 113)], ischium_r=(4.2, 3.2),
           pubis_r=(3.8, 3.2))
    # Hinterbein, Sohlengaenger: ganzer Fuss am Boden
    sk.add("hindlimb", "femur", bone((119, 109), (126, 138), 5.6, 4.0, 5.4))
    sk.add("hindlimb", "tibia", bone((125, 144), (118, 163), 5.2, 3.8, 4.6))
    sk.add("hindlimb", "tarsus", blob([(104, 174), (104, 167), (114, 166), (126, 168), (131, 172), (118, 176)]))
    for dy, dx in ((0, 0), (-3, -2), (2, -1)):
        sk.add("hindlimb", "toes", taper([(134 + dx, 172 + dy * 0.6), (144 + dx, 174 + dy * 0.3), (151 + dx, 176)], [2.6, 2.2, 1.0]))
    # Vorderbein mit Schulterblatt (Schulterblatt zaehlt zur Zone Oberarm)
    sk.add("forelimb", "humerus", blob([(236, 90), (255, 88), (258, 94), (252, 106), (246, 107)]))
    sk.add("forelimb", "humerus", bone((249, 112), (246, 140), 5.6, 4.0, 5.2))
    sk.add("forelimb", "radius_ulna", bone((242, 146), (249, 164), 4.0, 2.9, 3.4))
    sk.add("forelimb", "radius_ulna", bone((249, 146), (256, 164), 3.8, 2.7, 3.4))
    sk.add("forelimb", "radius_ulna", circle((240, 145), 3.4))
    sk.add("forelimb", "carpus", blob([(245, 175), (246, 169), (256, 168), (264, 171), (264, 176)]))
    for dy, dx in ((0, 0), (-3, -2), (2, -1)):
        sk.add("forelimb", "digits", taper([(268 + dx, 173 + dy * 0.6), (278 + dx, 175 + dy * 0.3), (285 + dx, 177)], [2.6, 2.2, 1.0]))
    # Schaedel: hohe, steile Stirn, kraeftige Eckzaehne
    f = local((294, 86), 16, 1.05)
    outline = blob(mapped(f, [(0, 2), (2, -8), (14, -17), (28, -20), (40, -16), (46, -9), (54, -5),
                              (66, -3), (71, 2), (70, 8), (62, 10), (62, 10), (60, 18), (57, 10),
                              (44, 11), (30, 13), (16, 12), (5, 10)]))
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(48, -30), (46, 30)]), mapped(f, [(16, -30), (16, 30)]), mapped(f, [(10, -5), (54, -4)])],
                  {"snout": f((60, 2)), "orbit": f((32, 4)), "skull_roof": f((30, -12)), "occiput": f((7, 0))},
                  holes=[("orbit", circle(f((38, 2)), 4.0))])
    g = local(f((10, 13)), 16, 1.05)
    jaw = blob(mapped(g, [(-3, -2), (4, -11), (10, -10), (12, -3), (24, 0), (48, 1), (50, -5), (52, -5),
                          (55, 3), (54, 9), (44, 12), (24, 12), (6, 11)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(44, -20), (42, 20)]), mapped(g, [(15, -20), (15, 20)]), mapped(g, [(10, 5.5), (48, 5.5)])],
                  {"tip": g((49, 4)), "tooth_row": g((30, 3)), "lower_edge": g((30, 9)), "joint": g((5, 0))})
    return sk


def hoof_leg(sk, bone_id, zone_carpus, zone_digits, wrist, ankle_len, toe_dir=(1.0, 0.35), r=2.6):
    """Huftierfuss: Knoechelhoecker und langer Mittelfussknochen (Zone Hand-/Fusswurzel),
    Fesselbein und Huf (Zone Finger/Zehen)."""
    x, y = wrist
    sk.add(bone_id, zone_carpus, circle((x - 1.5, y + 1.5), r * 1.35))
    sk.add(bone_id, zone_carpus, bone((x, y + 5), (x + 1.5, y + 5 + ankle_len), r * 1.15, r * 0.8, r))
    fx, fy = x + 1.5, y + 5 + ankle_len + 5
    tip = (fx + toe_dir[0] * 9, fy + toe_dir[1] * 9)
    sk.add(bone_id, zone_digits, taper([(fx, fy), tip], [r * 0.95, r * 0.8]))
    sk.add(bone_id, zone_digits, poly([(tip[0] - 2, tip[1] - 2.5), (tip[0] + 6, tip[1] + 3.5),
                                       (tip[0] - 3, tip[1] + 3.5)]).buffer(0.8))
    return tip[1] + 3.5


def giant_deer():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(118, 84), (160, 80), (200, 77), (238, 72)], 10, 10,
                    [9, 8, 9, 13, 19, 26], tilt=16, spine_w=(2.8, 1.8))
    tail(sk, [(108, 87), (100, 92), (95, 99)], 3, 6, 4.5)
    ribs(sk, frames[3:], [(164, 118), (190, 130), (222, 130), (236, 118)], bow=8, r=(3.0, 2.3),
         split=(0.2, 0.36, 0.78))
    column(sk, "cervical", [(246, 70), (259, 61), (272, 54)], 4, 12, [12, 10], tilt=12, wing=0.9,
           spine_w=(3.2, 2.2))
    pelvis(sk, (116, 95), [(108, 93), (104, 84), (112, 78), (134, 77), (138, 82), (124, 91)],
           [(110, 102), (100, 109), (94, 110)], [(122, 102), (131, 112)], r=7.2, ischium_r=(4.2, 3.4),
           pubis_r=(4.6, 3.8))
    # Hinterbein: lang und schlank, Sprunggelenk hoch
    sk.add("hindlimb", "femur", bone((117, 104), (130, 132), 5.4, 3.6, 5.0))
    sk.add("hindlimb", "tibia", bone((128, 138), (114, 166), 5.0, 3.2, 4.4))
    hoof_leg(sk, "hindlimb", "tarsus", "toes", (114, 170), 19, r=3.2)
    # Vorderbein
    sk.add("forelimb", "humerus", blob([(214, 82), (234, 80), (236, 86), (230, 104), (224, 104)]))
    sk.add("forelimb", "humerus", bone((228, 110), (222, 134), 5.4, 3.6, 5.0))
    sk.add("forelimb", "radius_ulna", bone((222, 140), (226, 168), 4.4, 3.0, 3.8))
    sk.add("forelimb", "radius_ulna", circle((217, 140), 3.4))
    hoof_leg(sk, "forelimb", "carpus", "digits", (226, 172), 17, r=3.2)
    # Schaedel: lang und schmal; das Schaufelgeweih gehoert zur Zone Schaedeldach
    f = local((277, 52), 30, 1.3)
    outline = blob(mapped(f, [(0, 1), (3, -7), (14, -10), (26, -8), (36, -5), (52, -2), (60, 1),
                              (59, 6), (44, 7), (28, 9), (12, 9), (3, 7)]))
    rx, ry = f((15, -9))
    # Zwei breite Schaufeln (nahe nach hinten, ferne nach vorn ausladend), Augsprosse, Sprossen am Rand
    antler = [
        taper([(rx, ry), (rx - 4, ry - 7), (rx - 9, ry - 11)], [4.2, 3.6, 3.4]),
        taper([(rx + 1, ry - 2), (rx + 6, ry - 10), (rx + 12, ry - 15)], [3.8, 3.4, 3.2]),
        taper([(rx - 1, ry - 3), (rx + 8, ry - 4), (rx + 16, ry - 2)], [2.8, 2.0, 1.2]),
        blob([(rx - 8, ry - 9), (rx - 30, ry - 13), (rx - 56, ry - 16), (rx - 66, ry - 26),
              (rx - 50, ry - 33), (rx - 28, ry - 31), (rx - 10, ry - 22)]),
        blob([(rx + 10, ry - 13), (rx + 30, ry - 17), (rx + 50, ry - 21), (rx + 58, ry - 31),
              (rx + 44, ry - 36), (rx + 26, ry - 32), (rx + 11, ry - 24)]),
    ]
    for (x0, y0), (x1, y1) in (((-62, -22), (-79, -24)), ((-58, -29), (-71, -42)), ((-46, -32), (-52, -47)),
                               ((-34, -31), (-36, -46)), ((-21, -27), (-19, -41)),
                               ((55, -27), (70, -31)), ((51, -33), (61, -46)), ((40, -35), (44, -49)),
                               ((28, -33), (28, -47))):
        antler.append(taper([(rx + x0, ry + y0), (rx + x1, ry + y1)], [3.0, 1.2]))
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(34, -30), (34, 30)]), mapped(f, [(11, -30), (11, 30)]), mapped(f, [(6, -2.5), (40, -1.5)])],
                  {"snout": f((48, 2)), "orbit": f((22, 4)), "skull_roof": f((22, -6)), "occiput": f((5, 0))},
                  holes=[("orbit", circle(f((24, 2)), 3.6))],
                  extras={"skull_roof": affinity.scale(unary_union(antler), 1.3, 1.05, origin=(rx, ry))})
    g = local(f((9, 11)), 34, 1.3)
    jaw = blob(mapped(g, [(-2, -2), (3, -10), (8, -9), (10, -2), (24, 0), (46, 0), (51, 1), (50, 6),
                          (40, 7), (22, 9), (5, 9)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(38, -20), (38, 20)]), mapped(g, [(13, -20), (13, 20)]), mapped(g, [(8, 4.2), (42, 3.8)])],
                  {"tip": g((45, 3)), "tooth_row": g((26, 2)), "lower_edge": g((26, 7)), "joint": g((4, 0))})
    return sk


def woolly_rhino():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(112, 84), (150, 81), (195, 76), (236, 70)], 12, 10,
                    [9, 8, 9, 12, 20, 30], tilt=18)
    tail(sk, [(100, 88), (92, 94), (86, 102)], 3, 6, 4)
    ribs(sk, frames[3:], [(152, 124), (184, 138), (220, 138), (236, 126)], bow=9, r=(3.0, 2.3))
    column(sk, "cervical", [(244, 78), (256, 88), (268, 98)], 4, 11, [11, 9], tilt=-10, wing=0.7,
           spine_w=(3.2, 2.2))
    pelvis(sk, (110, 98), [(100, 96), (96, 86), (108, 80), (134, 79), (138, 85), (120, 94)],
           [(104, 104), (95, 111), (89, 112)], [(116, 104), (124, 112)], r=6.4, ischium_r=(4.4, 3.4),
           pubis_r=(4, 3.4))
    # Kurze, stämmige Beine mit drei Zehen
    sk.add("hindlimb", "femur", bone((111, 108), (117, 138), 6.0, 4.4, 5.6))
    sk.add("hindlimb", "tibia", bone((116, 144), (110, 162), 5.4, 4.0, 4.8))
    sk.add("hindlimb", "tarsus", blob([(102, 172), (101, 166), (110, 165), (120, 167), (121, 172), (110, 174)]))
    for dx in (0, 6, 12):
        sk.add("hindlimb", "toes", taper([(108 + dx, 177), (112 + dx, 180)], [2.8, 2.6]))
    sk.add("forelimb", "humerus", blob([(220, 84), (242, 82), (244, 88), (238, 106), (232, 106)]))
    sk.add("forelimb", "humerus", bone((236, 112), (232, 140), 6.0, 4.4, 5.4))
    sk.add("forelimb", "radius_ulna", bone((231, 146), (235, 164), 4.6, 3.4, 4.0))
    sk.add("forelimb", "radius_ulna", circle((227, 145), 3.4))
    sk.add("forelimb", "carpus", blob([(226, 172), (226, 166), (236, 165), (244, 167), (244, 172), (236, 174)]))
    for dx in (0, 6, 12):
        sk.add("forelimb", "digits", taper([(231 + dx, 177), (235 + dx, 180)], [2.8, 2.6]))
    # Schaedel: lang, tief getragen, hohes Hinterhaupt; zwei Hoerner als Schaedelform
    f = local((266, 94), 24, 1.0)
    outline = blob(mapped(f, [(0, 2), (-2, -12), (2, -20), (10, -18), (30, -10), (44, -13), (56, -11),
                              (74, -9), (90, -8), (95, -2), (92, 5), (82, 7), (60, 9), (40, 11),
                              (20, 12), (6, 9)]))
    nasal_horn = blob(mapped(f, [(74, -7), (78, -20), (84, -36), (92, -52), (96, -54), (96, -38),
                                 (95, -20), (93, -7)]), 8)
    frontal_horn = blob(mapped(f, [(40, -11), (44, -22), (50, -30), (53, -28), (56, -18), (58, -10)]), 8)
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(64, -40), (62, 40)]), mapped(f, [(18, -40), (18, 40)]), mapped(f, [(12, -1), (68, -1)])],
                  {"snout": f((80, 0)), "orbit": f((40, 5)), "skull_roof": f((40, -7)), "occiput": f((6, -2))},
                  holes=[("orbit", circle(f((46, 3)), 3.4))],
                  extras={"snout": nasal_horn, "skull_roof": frontal_horn})
    g = local(f((14, 13)), 24, 1.0)
    jaw = blob(mapped(g, [(-3, -2), (2, -12), (8, -11), (10, -3), (24, 0), (60, 0), (72, 1), (72, 6),
                          (58, 9), (30, 11), (6, 10)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(58, -20), (58, 20)]), mapped(g, [(14, -20), (14, 20)]), mapped(g, [(8, 5), (62, 5)])],
                  {"tip": g((66, 3)), "tooth_row": g((36, 2.5)), "lower_edge": g((36, 8)), "joint": g((5, 0))})
    return sk


def woolly_mammoth():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(120, 90), (160, 76), (200, 64), (236, 58)], 12, 10,
                    [8, 8, 10, 14, 20, 26], tilt=20)
    tail(sk, [(110, 94), (103, 102), (99, 112)], 3, 6, 4)
    ribs(sk, frames[3:], [(158, 136), (190, 150), (226, 150), (240, 138)], bow=9, r=(3.0, 2.3))
    column(sk, "cervical", [(243, 62), (256, 67), (268, 74)], 3, 14, [8, 7], tilt=55, wing=0.8,
           spine_w=(3.6, 2.6))
    pelvis(sk, (122, 102), [(112, 102), (106, 90), (114, 80), (134, 78), (140, 84), (130, 96)],
           [(116, 108), (107, 116), (101, 118)], [(128, 108), (138, 118)], r=7.4, ischium_r=(4.6, 3.6),
           pubis_r=(4.4, 3.8))
    # Saeulenbeine
    sk.add("hindlimb", "femur", bone((123, 112), (127, 150), 6.2, 4.6, 5.8))
    sk.add("hindlimb", "tibia", bone((127, 156), (125, 184), 5.6, 4.2, 5.2))
    sk.add("hindlimb", "tarsus", blob([(116, 195), (116, 189), (126, 188), (135, 189), (135, 195), (126, 197)]))
    sk.add("hindlimb", "toes", blob([(113, 202), (118, 199), (134, 199), (141, 202), (140, 205), (114, 205)]))
    sk.add("forelimb", "humerus", blob([(224, 74), (244, 72), (246, 80), (242, 98), (234, 98)]))
    sk.add("forelimb", "humerus", bone((240, 102), (238, 146), 6.4, 4.8, 6.0))
    sk.add("forelimb", "radius_ulna", bone((236, 152), (238, 184), 5.0, 3.6, 4.4))
    sk.add("forelimb", "radius_ulna", bone((242, 154), (244, 184), 3.8, 2.8, 3.6))
    sk.add("forelimb", "radius_ulna", circle((232, 152), 3.6))
    sk.add("forelimb", "carpus", blob([(231, 195), (231, 189), (241, 188), (250, 189), (250, 195), (241, 197)]))
    sk.add("forelimb", "digits", blob([(228, 202), (233, 199), (249, 199), (256, 202), (255, 205), (229, 205)]))
    # Schaedel: hohe Kuppel, kurzes Gesicht; Stosszaehne gehoeren zur Zone Schnauze
    f = local((8, 0), 0, 1.0)  # Verschiebung gegenueber dem Rumpf
    outline = blob(mapped(f, [(260, 84), (258, 62), (264, 42), (278, 30), (292, 34), (302, 50), (306, 76),
                              (310, 98), (310, 108), (302, 110), (294, 102), (282, 96), (270, 92)]))
    tusks = []
    for (ox, oy, sc) in ((0, 0, 1.0), (-6, -2, 0.88)):
        x0, y0 = 306 + ox, 100 + oy
        pts = [(0, 0), (8, 24), (24, 42), (46, 46), (64, 32), (68, 14)]
        tusks.append(curve_taper(mapped(f, [(x0 + x * sc, y0 + y * sc) for x, y in pts]), 5.8 * sc, 2.0, 10))
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(272, 20), (272, 120)]), mapped(f, [(272, 56), (320, 58)]),
                   mapped(f, [(272, 84), (320, 80)])],
                  {"snout": f((300, 96)), "orbit": f((288, 70)), "skull_roof": f((288, 42)), "occiput": f((264, 70))},
                  holes=[("orbit", circle(f((290, 70)), 4.4))],
                  extras={"snout": unary_union(tusks)})
    g = local(f((261, 104)), 2, 1.3)
    jaw = blob(mapped(g, [(-3, -2), (2, -10), (8, -9), (10, -2), (20, 0), (25, -1), (28, 4), (26, 10),
                          (16, 14), (5, 12)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(19, -20), (19, 20)]), mapped(g, [(9, -20), (9, 20)]), mapped(g, [(5, 5), (24, 5)])],
                  {"tip": g((24, 4)), "tooth_row": g((15, 2)), "lower_edge": g((15, 9)), "joint": g((4, 0))})
    return sk


def smilodon():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(114, 86), (150, 79), (190, 80), (228, 80)], 11, 8,
                    [9, 10, 9, 9, 12, 15], tilt=12)
    tail(sk, [(104, 90), (96, 98), (92, 110)], 4, 5, 3)
    ribs(sk, frames[3:], [(160, 118), (186, 130), (216, 130), (228, 120)], bow=8, r=(2.5, 1.9))
    column(sk, "cervical", [(236, 78), (247, 68), (258, 60)], 4, 10, [9, 7], tilt=-6, wing=0.7,
           spine_w=(3.0, 2.0))
    pelvis(sk, (114, 94), [(106, 92), (102, 84), (110, 79), (132, 78), (136, 83), (122, 90)],
           [(108, 100), (99, 106), (94, 107)], [(120, 100), (128, 109)], r=6.0, pubis_r=(3.8, 3.2))
    # Hinterbein: Zehengaenger
    sk.add("hindlimb", "femur", bone((116, 102), (131, 128), 4.8, 3.2, 4.6))
    sk.add("hindlimb", "tibia", bone((130, 134), (114, 154), 4.4, 3.0, 3.8))
    sk.add("hindlimb", "tarsus", circle((109, 156), 3.6))
    sk.add("hindlimb", "tarsus", bone((114, 159), (121, 170), 3.4, 2.6, 3.0))
    for dy in (0, -2.5, 2):
        sk.add("hindlimb", "toes", taper([(125, 174 + dy * 0.4), (133, 176 + dy * 0.3), (137, 178)], [2.4, 2.0, 1.1]))
    # Vorderbein: kraeftig
    sk.add("forelimb", "humerus", blob([(212, 86), (232, 84), (234, 90), (228, 106), (222, 106)]))
    sk.add("forelimb", "humerus", bone((226, 112), (220, 138), 5.4, 3.8, 5.0))
    sk.add("forelimb", "radius_ulna", bone((220, 144), (226, 166), 4.2, 3.0, 3.6))
    sk.add("forelimb", "radius_ulna", circle((215, 143), 3.4))
    sk.add("forelimb", "carpus", blob([(222, 173), (224, 168), (232, 168), (235, 172), (230, 175)]))
    for dy in (0, -2.5, 2):
        sk.add("forelimb", "digits", taper([(239, 174 + dy * 0.4), (247, 176 + dy * 0.3), (252, 178)], [2.5, 2.1, 1.1]))
    # Schaedel: kurz und rund mit Scheitelkamm, lange Saebelzaehne (Zone Schnauze), Maul weit offen
    f = local((259, 57), 8, 1.18)
    outline = blob(mapped(f, [(0, 2), (0, -6), (8, -13), (20, -15), (30, -12), (38, -6), (44, -3),
                              (46, 2), (44, 7), (38, 8), (24, 9), (12, 9), (4, 8)]))
    saber = curve_taper(mapped(f, [(38, 6), (39, 16), (37, 26), (33, 34)]), 3.0, 0.9, 10)
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(34, -30), (34, 30)]), mapped(f, [(11, -30), (11, 30)]), mapped(f, [(6, -4), (40, -3)])],
                  {"snout": f((40, 2)), "orbit": f((22, 4)), "skull_roof": f((22, -10)), "occiput": f((5, 0))},
                  holes=[("orbit", circle(f((27, 1)), 4.0))],
                  extras={"snout": saber})
    g = local(f((8, 10)), 54, 1.18)
    jaw = blob(mapped(g, [(-2, -2), (3, -9), (8, -8), (10, -2), (20, 0), (30, -1), (33, 1), (33, 8),
                          (30, 12), (26, 9), (18, 8), (5, 8)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(25, -20), (25, 20)]), mapped(g, [(11, -20), (11, 20)]), mapped(g, [(6, 3.8), (28, 3.8)])],
                  {"tip": g((30, 5)), "tooth_row": g((18, 1.5)), "lower_edge": g((18, 6.5)), "joint": g((4, 0))})
    return sk


def megatherium():
    sk = Skeleton()
    # Halb aufgerichtet: Rumpf schraeg, Hinterbeine und kraeftiger Schwanz als Stuetze
    frames = column(sk, "dorsal", [(128, 128), (150, 108), (178, 88), (206, 72)], 11, 11,
                    [11, 10, 10, 11, 13, 15], tilt=14)
    tail(sk, [(112, 146), (94, 160), (74, 172), (54, 180)], 7, 13, 6)
    ribs(sk, frames[2:], [(180, 150), (198, 137), (212, 122), (218, 110)], bow=8, r=(3.0, 2.3),
         split=(0.2, 0.38, 0.78))
    column(sk, "cervical", [(212, 66), (222, 54), (232, 44)], 4, 11, [12, 10], tilt=4, wing=0.9,
           spine_w=(3.2, 2.2))
    pelvis(sk, (124, 142), [(112, 140), (106, 126), (118, 114), (142, 112), (148, 120), (134, 136)],
           [(118, 148), (108, 156), (102, 158)], [(131, 149), (142, 160)], r=7.4, ischium_r=(4.8, 3.8),
           pubis_r=(4.6, 4.0))
    sk.add("hindlimb", "femur", bone((126, 151), (140, 172), 7.4, 5.6, 7.0))
    sk.add("hindlimb", "tibia", bone((139, 178), (130, 192), 6.4, 5.0, 5.8))
    sk.add("hindlimb", "tarsus", blob([(118, 204), (118, 197), (130, 196), (142, 198), (143, 204), (130, 206)]))
    for dx in (0, 7):
        sk.add("hindlimb", "toes", taper([(147 + dx, 204), (154 + dx, 206), (160 + dx, 207)], [2.8, 2.4, 1.2]))
    # Vorderbein haengt leicht nach vorn, riesige gebogene Krallen
    sk.add("forelimb", "humerus", blob([(196, 80), (218, 72), (222, 80), (214, 98), (206, 100)]))
    sk.add("forelimb", "humerus", bone((212, 104), (226, 130), 6.4, 4.6, 6.0))
    sk.add("forelimb", "radius_ulna", bone((228, 136), (248, 154), 5.0, 3.6, 4.4))
    sk.add("forelimb", "radius_ulna", bone((232, 132), (252, 149), 4.0, 2.8, 3.6))
    sk.add("forelimb", "radius_ulna", circle((223, 137), 3.8))
    sk.add("forelimb", "carpus", blob([(250, 162), (250, 155), (258, 152), (264, 156), (262, 163), (255, 165)]))
    for (x0, y0) in ((264, 166), (258, 169), (270, 162)):
        sk.add("forelimb", "digits", curve_taper([(x0, y0), (x0 + 8, y0 + 8), (x0 + 10, y0 + 20), (x0 + 5, y0 + 28)], 3.2, 0.8, 10))
    # Schaedel: klein und lang, tiefer Unterkiefer
    f = local((235, 41), 22, 1.15)
    outline = blob(mapped(f, [(0, 1), (3, -7), (16, -9), (30, -7), (42, -3), (48, 0), (47, 5),
                              (38, 7), (22, 9), (8, 9), (2, 7)]))
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(34, -30), (34, 30)]), mapped(f, [(11, -30), (11, 30)]), mapped(f, [(6, -2.5), (40, -1)])],
                  {"snout": f((42, 2)), "orbit": f((22, 4)), "skull_roof": f((22, -6)), "occiput": f((5, 0))},
                  holes=[("orbit", circle(f((26, 2)), 3.2))])
    g = local(f((9, 11)), 22, 1.15)
    jaw = blob(mapped(g, [(-2, -3), (3, -11), (8, -10), (10, -2), (22, 0), (38, 0), (42, 2), (38, 6),
                          (28, 14), (18, 16), (8, 12)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(30, -20), (30, 20)]), mapped(g, [(12, -20), (12, 20)]), mapped(g, [(8, 5), (36, 5)])],
                  {"tip": g((38, 3)), "tooth_row": g((22, 2.5)), "lower_edge": g((22, 10)), "joint": g((4, 0))})
    return sk


# --------------------------------------------------------------------------- Neogen (AP-27b)

def side_toes(sk, bone_id, zone, fetlock, r=1.7):
    """Zwei kurze Seitenzehen am Fesselgelenk (dreizehiges Urpferd)."""
    x, y = fetlock
    sk.add(bone_id, zone, taper([(x - 2.5, y - 1), (x - 5.5, y + 6)], [r, r * 0.8]))
    sk.add(bone_id, zone, taper([(x + 1.5, y - 2), (x + 3.5, y + 5)], [r * 0.9, r * 0.7]))


def hipparion():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(118, 84), (160, 82), (200, 80), (234, 76)], 10, 9,
                    [8, 7, 8, 11, 16, 21], tilt=16, spine_w=(2.6, 1.6))
    tail(sk, [(108, 87), (101, 93), (97, 103), (95, 114)], 4, 5, 3.5)
    ribs(sk, frames[3:], [(162, 116), (188, 126), (218, 126), (232, 114)], bow=8, r=(2.7, 2.0),
         split=(0.2, 0.36, 0.78))
    column(sk, "cervical", [(240, 72), (250, 58), (258, 44), (266, 30)], 5, 10, [6, 5], tilt=10,
           wing=0.95, spine_w=(2.8, 2.0))
    pelvis(sk, (116, 95), [(108, 93), (104, 84), (112, 78), (134, 78), (138, 83), (124, 91)],
           [(110, 102), (100, 109), (94, 110)], [(122, 102), (131, 112)], r=6.6, ischium_r=(4.0, 3.2),
           pubis_r=(4.2, 3.4))
    # Lange, schlanke Beine; Mittelhuf mit zwei kurzen Seitenzehen (Zone Finger/Zehen)
    sk.add("hindlimb", "femur", bone((117, 104), (131, 130), 5.0, 3.2, 4.6))
    sk.add("hindlimb", "tibia", bone((129, 136), (114, 166), 4.6, 2.9, 4.0))
    hoof_leg(sk, "hindlimb", "tarsus", "toes", (114, 170), 24, r=2.9)
    side_toes(sk, "hindlimb", "toes", (115.5, 199))
    sk.add("forelimb", "humerus", blob([(212, 82), (232, 79), (234, 85), (228, 102), (222, 102)]))
    sk.add("forelimb", "humerus", bone((226, 108), (221, 132), 5.0, 3.2, 4.6))
    sk.add("forelimb", "radius_ulna", bone((221, 138), (225, 170), 4.0, 2.7, 3.4))
    sk.add("forelimb", "radius_ulna", circle((216, 138), 3.2))
    hoof_leg(sk, "forelimb", "carpus", "digits", (225, 174), 20, r=2.9)
    side_toes(sk, "forelimb", "digits", (226.5, 199))
    # Schaedel: lang gestreckter, schmaler Pferdekopf, schraeg nach unten getragen
    f = local((268, 24), 40, 1.12)
    outline = blob(mapped(f, [(0, 1), (3, -7), (14, -9), (26, -7), (40, -4), (56, -2), (66, -1),
                              (69, 3), (64, 6), (50, 5), (32, 7), (14, 8), (3, 7)]))
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(38, -30), (38, 30)]), mapped(f, [(11, -30), (11, 30)]), mapped(f, [(10, -2.5), (39, -1)])],
                  {"snout": f((54, 2)), "orbit": f((22, 4)), "skull_roof": f((22, -5)), "occiput": f((5, 0))},
                  holes=[("orbit", circle(f((24, 1.5)), 3.2))])
    g = local(f((8, 9.5)), 42, 1.12)
    jaw = blob(mapped(g, [(-2, -3), (2, -11), (8, -10), (11, -2), (24, -1), (50, -2), (60, -1), (59, 3),
                          (48, 5), (28, 7), (14, 9), (4, 8)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(44, -20), (44, 20)]), mapped(g, [(14, -20), (14, 20)]), mapped(g, [(13, 3.0), (45, 2.0)])],
                  {"tip": g((52, 1)), "tooth_row": g((28, 0.5)), "lower_edge": g((28, 4.5)), "joint": g((4, -2))})
    return sk


def proboscidean_legs(sk, hind_top, fore_top, femur_len, tibia_len, humerus_len, radius_len, foot_y):
    """Saeulenbeine eines Ruesseltiers mit Fuss- und Zehenpolster auf der Grundlinie foot_y."""
    hx, hy = hind_top
    sk.add("hindlimb", "femur", bone((hx, hy), (hx + 4, hy + femur_len), 6.2, 4.6, 5.8))
    ty = hy + femur_len + 6
    sk.add("hindlimb", "tibia", bone((hx + 4, ty), (hx + 2, ty + tibia_len), 5.6, 4.2, 5.2))
    tx = hx + 2
    sk.add("hindlimb", "tarsus", blob([(tx - 9, foot_y - 10), (tx - 9, foot_y - 16), (tx + 1, foot_y - 17),
                                       (tx + 10, foot_y - 16), (tx + 10, foot_y - 10), (tx + 1, foot_y - 8)]))
    sk.add("hindlimb", "toes", blob([(tx - 12, foot_y - 3), (tx - 7, foot_y - 6), (tx + 9, foot_y - 6),
                                     (tx + 16, foot_y - 3), (tx + 15, foot_y), (tx - 11, foot_y)]))
    fx, fy = fore_top
    sk.add("forelimb", "humerus", blob([(fx - 16, fy - 28), (fx + 4, fy - 30), (fx + 6, fy - 22),
                                        (fx + 2, fy - 4), (fx - 6, fy - 4)]))
    sk.add("forelimb", "humerus", bone((fx, fy), (fx - 2, fy + humerus_len), 6.4, 4.8, 6.0))
    ry = fy + humerus_len + 6
    sk.add("forelimb", "radius_ulna", bone((fx - 4, ry), (fx - 2, ry + radius_len), 5.0, 3.6, 4.4))
    sk.add("forelimb", "radius_ulna", bone((fx + 2, ry + 2), (fx + 4, ry + radius_len), 3.8, 2.8, 3.6))
    sk.add("forelimb", "radius_ulna", circle((fx - 8, ry), 3.6))
    cx = fx - 2
    sk.add("forelimb", "carpus", blob([(cx - 7, foot_y - 10), (cx - 7, foot_y - 16), (cx + 3, foot_y - 17),
                                       (cx + 12, foot_y - 16), (cx + 12, foot_y - 10), (cx + 3, foot_y - 8)]))
    sk.add("forelimb", "digits", blob([(cx - 10, foot_y - 3), (cx - 5, foot_y - 6), (cx + 11, foot_y - 6),
                                       (cx + 18, foot_y - 3), (cx + 17, foot_y), (cx - 9, foot_y)]))


def gomphotherium():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(120, 86), (160, 76), (200, 68), (236, 64)], 12, 10,
                    [8, 8, 10, 13, 18, 22], tilt=20)
    tail(sk, [(110, 90), (103, 98), (99, 108)], 3, 6, 4)
    ribs(sk, frames[3:], [(158, 128), (190, 142), (226, 142), (240, 130)], bow=9, r=(3.0, 2.3))
    column(sk, "cervical", [(243, 68), (254, 72), (264, 78)], 3, 13, [7, 6], tilt=50, wing=0.8,
           spine_w=(3.4, 2.4))
    pelvis(sk, (122, 98), [(112, 98), (106, 86), (114, 76), (134, 74), (140, 80), (130, 92)],
           [(116, 104), (107, 112), (101, 114)], [(128, 104), (138, 114)], r=7.4, ischium_r=(4.6, 3.6),
           pubis_r=(4.4, 3.8))
    proboscidean_legs(sk, (123, 108), (240, 100), 34, 26, 36, 26, 188)
    # Schaedel: lang und flach; zwei obere Stosszaehne (Zone Schnauze), zwei flache untere
    # Stosszaehne vorn im langen Unterkiefer (Zone Kinnspitze)
    outline = blob([(262, 80), (261, 62), (270, 50), (286, 46), (300, 52), (312, 66), (320, 80),
                    (322, 92), (314, 97), (298, 95), (282, 93), (270, 91)])
    tusks = [curve_taper([(312, 92), (326, 100), (344, 106), (362, 112), (372, 120)], 5.0, 2.0, 10),
             curve_taper([(306, 90), (320, 96), (336, 101), (350, 106)], 4.0, 1.8, 10)]
    outlined_bone(sk, "skull", outline,
                  [[(274, 30), (274, 110)], [(274, 64), (330, 66)], [(274, 82), (330, 80)]],
                  {"snout": (306, 88), "orbit": (290, 74), "skull_roof": (290, 54), "occiput": (266, 72)},
                  holes=[("orbit", circle((294, 74), 4.2))],
                  extras={"snout": unary_union(tusks)})
    g = local((264, 102), 4, 1.0)
    jaw = blob(mapped(g, [(-3, -2), (2, -10), (8, -9), (12, -2), (26, 1), (46, 3), (64, 5), (72, 6),
                          (72, 12), (60, 15), (40, 15), (20, 14), (5, 12)]))
    lower_tusks = [rrect(g((84, 9.5)), (math.cos(math.radians(4)), math.sin(math.radians(4))), 14, 2.6, 1.4),
                   rrect(g((80, 14)), (math.cos(math.radians(6)), math.sin(math.radians(6))), 12, 1.8, 1.0)]
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(56, -20), (56, 30)]), mapped(g, [(16, -20), (16, 30)]), mapped(g, [(15, 6.5), (57, 8)])],
                  {"tip": g((64, 9)), "tooth_row": g((36, 4)), "lower_edge": g((36, 12)), "joint": g((5, 0))},
                  extras={"tip": unary_union(lower_tusks)})
    return sk


def chalicotherium():
    sk = Skeleton()
    # Abfallender Ruecken: hohe Schultern, tiefe Huefte
    frames = column(sk, "dorsal", [(118, 100), (156, 86), (196, 72), (232, 62)], 11, 10,
                    [7, 7, 8, 11, 16, 21], tilt=16, spine_w=(2.8, 1.8))
    tail(sk, [(108, 104), (101, 110), (97, 118)], 3, 5, 3.5)
    ribs(sk, frames[3:], [(160, 128), (188, 136), (218, 132), (234, 118)], bow=8, r=(2.8, 2.1),
         split=(0.2, 0.36, 0.78))
    column(sk, "cervical", [(241, 60), (254, 52), (268, 46)], 4, 11, [9, 7], tilt=6, wing=0.8,
           spine_w=(3.0, 2.0))
    pelvis(sk, (116, 110), [(108, 108), (104, 99), (112, 93), (134, 92), (138, 97), (124, 106)],
           [(110, 117), (100, 124), (94, 125)], [(122, 117), (131, 127)], r=6.6, ischium_r=(4.2, 3.4),
           pubis_r=(4.2, 3.4))
    # Kurzes Hinterbein mit kurzem Fuss und kleinen Krallen
    sk.add("hindlimb", "femur", bone((117, 119), (127, 143), 5.4, 3.8, 5.0))
    sk.add("hindlimb", "tibia", bone((126, 149), (117, 168), 5.0, 3.6, 4.4))
    sk.add("hindlimb", "tarsus", blob([(108, 180), (109, 173), (118, 172), (127, 174), (129, 179), (118, 181)]))
    for dy in (0, -3, 2.5):
        sk.add("hindlimb", "toes", curve_taper([(131, 179 + dy * 0.5), (138, 181 + dy * 0.3), (142, 184)], 2.6, 1.0, 8))
    # Sehr lange Vorderbeine; Knoechelgang: Krallen nach hinten eingeschlagen, Knoechel am Boden
    sk.add("forelimb", "humerus", blob([(210, 66), (232, 62), (235, 68), (228, 92), (220, 92)]))
    sk.add("forelimb", "humerus", bone((225, 98), (221, 130), 5.6, 3.8, 5.2))
    sk.add("forelimb", "radius_ulna", bone((221, 136), (230, 168), 4.6, 3.2, 4.0))
    sk.add("forelimb", "radius_ulna", circle((216, 136), 3.6))
    sk.add("forelimb", "carpus", blob([(228, 175), (232, 170), (240, 171), (244, 176), (240, 180), (232, 180)]))
    for dx, sc in ((0, 1.0), (7, 0.85)):
        x0 = 238 + dx
        sk.add("forelimb", "digits", curve_taper([(x0, 181), (x0 + 6, 186), (x0 + 12, 184), (x0 + 13, 177),
                                                  (x0 + 8, 172)], 3.4 * sc, 1.0, 10))
    # Pferdeartiger Kopf, schraeg nach unten
    f = local((270, 42), 38, 1.08)
    outline = blob(mapped(f, [(0, 1), (3, -8), (14, -11), (26, -9), (38, -6), (50, -3), (58, -1),
                              (60, 4), (54, 7), (40, 8), (26, 10), (12, 10), (3, 7)]))
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(34, -30), (34, 30)]), mapped(f, [(11, -30), (11, 30)]), mapped(f, [(10, -3), (35, -1.5)])],
                  {"snout": f((48, 2)), "orbit": f((22, 4)), "skull_roof": f((22, -7)), "occiput": f((5, 0))},
                  holes=[("orbit", circle(f((24, 1.5)), 3.4))])
    g = local(f((8, 11)), 40, 1.08)
    jaw = blob(mapped(g, [(-2, -3), (2, -12), (8, -11), (11, -3), (24, -1), (44, -1), (52, 0), (51, 5),
                          (40, 6), (24, 9), (12, 10), (4, 8)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(38, -20), (38, 20)]), mapped(g, [(14, -20), (14, 20)]), mapped(g, [(13, 3.6), (39, 2.8)])],
                  {"tip": g((46, 2)), "tooth_row": g((26, 1.5)), "lower_edge": g((26, 6.5)), "joint": g((4, -2))})
    return sk


def deinotherium():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(120, 78), (160, 68), (200, 60), (236, 56)], 12, 10,
                    [8, 8, 10, 13, 18, 22], tilt=20)
    tail(sk, [(110, 82), (103, 90), (99, 100)], 3, 6, 4)
    ribs(sk, frames[3:], [(158, 120), (190, 134), (226, 134), (240, 122)], bow=9, r=(3.0, 2.3))
    column(sk, "cervical", [(243, 60), (254, 64), (264, 70)], 3, 13, [7, 6], tilt=50, wing=1.0,
           spine_w=(3.4, 2.4))
    pelvis(sk, (122, 90), [(112, 90), (106, 78), (114, 68), (134, 66), (140, 72), (130, 84)],
           [(116, 96), (107, 104), (101, 106)], [(128, 96), (138, 106)], r=7.4, ischium_r=(4.6, 3.6),
           pubis_r=(4.4, 3.8))
    # Hohe Saeulenbeine
    proboscidean_legs(sk, (123, 100), (240, 92), 42, 36, 44, 36, 200)
    # Schaedel flach und niedrig; Stosszaehne im Unterkiefer, stark nach unten und hinten gebogen
    outline = blob([(262, 76), (262, 58), (272, 48), (288, 48), (302, 56), (312, 70), (316, 84),
                    (314, 92), (304, 94), (290, 92), (276, 90), (266, 88)])
    outlined_bone(sk, "skull", outline,
                  [[(274, 30), (274, 110)], [(274, 64), (330, 64)], [(274, 80), (330, 80)]],
                  {"snout": (306, 86), "orbit": (290, 72), "skull_roof": (290, 54), "occiput": (266, 70)},
                  holes=[("orbit", circle((294, 72), 4.2))])
    g = local((266, 98), 4, 1.0)
    jaw = blob(mapped(g, [(-3, -2), (2, -10), (8, -9), (12, -2), (26, 0), (40, 1), (48, 4), (52, 12),
                          (48, 18), (38, 16), (24, 14), (6, 12)]))
    tusks = [curve_taper(mapped(g, [(48, 14), (52, 28), (48, 42), (38, 52), (26, 56)]), 5.2, 1.8, 10)]
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(40, -20), (40, 30)]), mapped(g, [(16, -20), (16, 30)]), mapped(g, [(15, 6), (41, 7)])],
                  {"tip": g((46, 10)), "tooth_row": g((28, 3)), "lower_edge": g((28, 11)), "joint": g((5, 0))},
                  extras={"tip": unary_union(tusks)})
    return sk


def titanis():
    """Terrorvogel (bodyPlan biped). Abbildung der Knochen auf den Vogel:
    skull = Schaedel mit Hakenschnabel (Oberschnabel = Zone Schnauze), jaw = Unterschnabel,
    cervical = langer S-foermiger Hals, dorsal = kurzer Ruecken plus Schwanzwirbel/Pygostyl,
    pelvis = Becken (Schambein nach hinten), forelimb = kurzer, angelegter Fluegel (Zone Oberarm mit
    Schulterblatt, Unterarm, Hand-/Mittelhandknochen als carpus, Fluegelfinger mit Kralle als digits),
    hindlimb = Laufbein (Oberschenkel, Tibiotarsus als tibia, Tarsometatarsus als tarsus, Zehen)."""
    sk = Skeleton()
    frames = column(sk, "dorsal", [(150, 92), (166, 88), (182, 86), (198, 84)], 5, 9,
                    [6, 7], tilt=10, spine_w=(2.6, 1.8), wing=0.7)
    tail(sk, [(97, 97), (90, 93), (85, 86)], 3, 7, 5)
    ribs(sk, frames, [(150, 130), (166, 142), (184, 140), (198, 126)], bow=6, r=(3.0, 2.2),
         split=(0.22, 0.42, 0.78))
    column(sk, "cervical", [(206, 82), (215, 72), (218, 60), (224, 50), (236, 44)], 7, 9, [4, 3],
           tilt=0, wing=0.8, spine_w=(2.4, 1.6))
    pelvis(sk, (128, 104), [(100, 100), (99, 91), (110, 85), (144, 86), (148, 93), (138, 100)],
           [(116, 110), (104, 116), (96, 118)], [(124, 112), (110, 124), (98, 128)], r=7.0,
           ischium_r=(3.6, 2.8), pubis_r=(2.8, 2.2))
    # Laufbein: kurzer Oberschenkel, langer Tibiotarsus, langer Tarsometatarsus, drei Zehen vorn
    sk.add("hindlimb", "femur", bone((131, 112), (148, 134), 5.6, 4.0, 5.2))
    sk.add("hindlimb", "tibia", bone((147, 140), (128, 182), 5.2, 3.6, 4.6))
    sk.add("hindlimb", "tarsus", circle((126, 186), 4.0))
    sk.add("hindlimb", "tarsus", bone((128, 190), (142, 224), 4.0, 3.0, 4.2))
    for dy, ln in ((0, 22), (-3, 17), (2.5, 14)):
        sk.add("hindlimb", "toes", curve_taper([(146, 228 + dy * 0.4), (146 + ln * 0.6, 229 + dy * 0.2),
                                                (146 + ln, 231)], 2.8, 1.0, 8))
    sk.add("hindlimb", "toes", curve_taper([(140, 229), (133, 231), (129, 231)], 2.4, 1.0, 6))
    # Kurzer, am Rumpf nach hinten angelegter Fluegel (Kralle am Ende)
    sk.add("forelimb", "humerus", blob([(198, 100), (208, 98), (209, 104), (202, 110)]))
    sk.add("forelimb", "humerus", bone((200, 116), (180, 121), 4.8, 3.4, 4.2))
    sk.add("forelimb", "radius_ulna", bone((175, 122), (154, 123), 4.0, 2.8, 3.6))
    sk.add("forelimb", "carpus", bone((149, 123), (136, 123), 3.8, 2.8, 3.4))
    sk.add("forelimb", "digits", curve_taper([(131, 123), (124, 123), (120, 126), (120, 131)], 3.2, 1.2, 8))
    # Grosser Kopf mit hohem Hakenschnabel
    f = local((238, 40), -4, 1.2)
    outline = blob(mapped(f, [(0, 4), (0, -6), (8, -14), (20, -19), (34, -20), (50, -18), (64, -13),
                              (76, -6), (82, 2), (83, 10), (81, 18), (78, 22), (76, 15), (74, 9), (60, 7),
                              (40, 7), (24, 8), (10, 8)]))
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(32, -30), (32, 30)]), mapped(f, [(9, -30), (9, 30)]), mapped(f, [(8, -5), (33, -6)])],
                  {"snout": f((56, -2)), "orbit": f((20, 2)), "skull_roof": f((20, -12)), "occiput": f((4, 0))},
                  holes=[("orbit", circle(f((21, 0)), 4.4))])
    g = local(f((6, 10)), -2, 1.2)
    jaw = blob(mapped(g, [(-2, -2), (3, -8), (8, -6), (12, -1), (30, 0), (50, -1), (62, -1), (67, 1),
                          (64, 7), (48, 10), (26, 11), (6, 9)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(50, -20), (50, 20)]), mapped(g, [(13, -20), (13, 20)]), mapped(g, [(12, 4), (51, 3.5)])],
                  {"tip": g((58, 2)), "tooth_row": g((30, 1.5)), "lower_edge": g((30, 7.5)), "joint": g((4, 0))})
    return sk


def thylacosmilus():
    sk = Skeleton()
    frames = column(sk, "dorsal", [(114, 90), (150, 84), (190, 84), (226, 84)], 11, 8,
                    [8, 9, 8, 8, 10, 12], tilt=12)
    tail(sk, [(104, 94), (88, 104), (70, 112), (52, 116)], 7, 5, 2.6)
    ribs(sk, frames[3:], [(160, 120), (186, 130), (214, 130), (226, 120)], bow=8, r=(2.5, 1.9))
    column(sk, "cervical", [(234, 82), (246, 74), (258, 68)], 4, 10, [8, 6], tilt=-6, wing=0.7,
           spine_w=(3.0, 2.0))
    pelvis(sk, (114, 98), [(106, 96), (102, 88), (110, 83), (132, 82), (136, 87), (122, 94)],
           [(108, 104), (99, 110), (94, 111)], [(120, 104), (128, 113)], r=6.0, pubis_r=(3.8, 3.2))
    # Kurze, kraeftige Beine, halber Sohlengaenger
    sk.add("hindlimb", "femur", bone((116, 106), (129, 130), 4.8, 3.4, 4.6))
    sk.add("hindlimb", "tibia", bone((128, 136), (116, 154), 4.4, 3.2, 3.8))
    sk.add("hindlimb", "tarsus", blob([(106, 166), (108, 159), (117, 158), (125, 161), (126, 166), (115, 168)]))
    for dy in (0, -2.5, 2):
        sk.add("hindlimb", "toes", taper([(129, 166 + dy * 0.4), (136, 168 + dy * 0.3), (140, 170)], [2.4, 2.0, 1.1]))
    sk.add("forelimb", "humerus", blob([(210, 90), (230, 88), (232, 94), (226, 110), (220, 110)]))
    sk.add("forelimb", "humerus", bone((224, 116), (218, 138), 5.4, 3.8, 5.0))
    sk.add("forelimb", "radius_ulna", bone((218, 144), (224, 158), 4.2, 3.0, 3.6))
    sk.add("forelimb", "radius_ulna", circle((213, 143), 3.4))
    sk.add("forelimb", "carpus", blob([(218, 166), (221, 160), (230, 160), (233, 165), (226, 168)]))
    for dy in (0, -2.5, 2):
        sk.add("forelimb", "digits", taper([(237, 166 + dy * 0.4), (245, 168 + dy * 0.3), (250, 170)], [2.5, 2.1, 1.1]))
    # Schaedel mit sehr langen Saebelzaehnen (Zone Schnauze); Maul geschlossen, grosser Kinnfortsatz
    f = local((259, 64), 10, 1.2)
    outline = blob(mapped(f, [(0, 2), (0, -6), (8, -12), (20, -14), (32, -11), (42, -6), (49, -1),
                              (49, 5), (42, 8), (28, 9), (12, 9), (4, 8)]))
    saber = curve_taper(mapped(f, [(38, 6), (40, 20), (39, 34), (35, 46)]), 3.4, 1.0, 10)
    outlined_bone(sk, "skull", outline,
                  [mapped(f, [(33, -30), (33, 30)]), mapped(f, [(11, -30), (11, 30)]), mapped(f, [(10, -4), (34, -3)])],
                  {"snout": f((42, 2)), "orbit": f((22, 4)), "skull_roof": f((22, -10)), "occiput": f((5, 0))},
                  holes=[("orbit", circle(f((26, 1)), 4.0))],
                  extras={"snout": saber})
    g = local(f((8, 11)), 2, 1.2)
    jaw = blob(mapped(g, [(-2, -2), (3, -9), (8, -8), (10, -2), (24, 0), (36, 0), (40, 2), (42, 12),
                          (40, 28), (36, 32), (32, 24), (28, 12), (16, 9), (5, 8)]))
    outlined_bone(sk, "jaw", jaw,
                  [mapped(g, [(29, -20), (29, 40)]), mapped(g, [(11, -20), (11, 40)]), mapped(g, [(10, 4.5), (30, 4.5)])],
                  {"tip": g((36, 14)), "tooth_row": g((20, 2)), "lower_edge": g((20, 7)), "joint": g((4, 0))})
    return sk


SPECIES = {
    "cave_bear": cave_bear,
    "giant_deer": giant_deer,
    "woolly_rhino": woolly_rhino,
    "woolly_mammoth": woolly_mammoth,
    "smilodon": smilodon,
    "megatherium": megatherium,
    "hipparion": hipparion,
    "gomphotherium": gomphotherium,
    "chalicotherium": chalicotherium,
    "deinotherium": deinotherium,
    "titanis": titanis,
    "thylacosmilus": thylacosmilus,
}


# --------------------------------------------------------------------------- Ausgabe

def fit(zones):
    allg = unary_union(list(zones.values()))
    minx, miny, maxx, maxy = allg.bounds
    s = min((VIEW_W - 2 * MARGIN) / (maxx - minx), (VIEW_H - 2 * MARGIN) / (maxy - miny))
    dx = (VIEW_W - (maxx - minx) * s) / 2 - minx * s
    dy = VIEW_H - MARGIN - maxy * s
    return {k: affinity.translate(affinity.scale(g, s, s, origin=(0, 0)), dx, dy) for k, g in zones.items()}


def knockout(zones, order):
    """Spaetere Zonen schneiden fruehere frei (Fuge GAP); Splitter fallen weg."""
    result = {}
    for key in order:
        g = zones[key]
        for prev in list(result):
            result[prev] = result[prev].difference(g.buffer(GAP, 16))
        result[key] = g
    cleaned = {}
    for key, g in result.items():
        parts = list(g.geoms) if hasattr(g, "geoms") else [g]
        parts = [p for p in parts if isinstance(p, Polygon) and p.area >= MIN_PART]
        cleaned[key] = MultiPolygon(parts) if len(parts) > 1 else (parts[0] if parts else Polygon())
    return cleaned


def fmt(v):
    s = f"{v:.1f}"
    return s[:-2] if s.endswith(".0") else s


def ring_d(coords):
    pts = list(coords)[:-1]
    return "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in pts) + " Z"


def path_d(geom):
    geom = geom.simplify(0.12, preserve_topology=True)
    parts = list(geom.geoms) if hasattr(geom, "geoms") else [geom]
    out = []
    for p in parts:
        p = orient(p, 1.0)  # Aussenrand und Loecher gegenlaeufig (Fuellregel nonzero)
        out.append(ring_d(p.exterior.coords))
        out.extend(ring_d(i.coords) for i in p.interiors)
    return " ".join(out)


def load_bones():
    return [(b["id"], b["zones"]) for b in json.loads(BONES_JSON.read_text(encoding="utf-8"))]


def build(species_id):
    sk = SPECIES[species_id]()
    bones = dict(load_bones())
    order = [(b, z) for b in DRAW_ORDER for z in bones[b]]
    missing = [k for k in order if k not in sk.zones]
    if missing:
        raise SystemExit(f"{species_id}: Zonen fehlen: {missing}")
    zones = knockout(fit(sk.zones), order)
    return zones, order


def write_svg(species_id, zones, order):
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        "<!--",
        f'  Skelett "{species_id}" (GAME_DESIGN.md, Kapitel 8.5). Selbst gezeichnet, schematisch, Blick nach rechts.',
        '  Konvention: Gruppe id="<boneId>", Zone als Pfad id="<boneId>__<zoneId>", keine transform-Attribute.',
        "  Erzeugt mit tools/art/skeleton_gen.py; die SVG ist die Quelle fuer tools/svg2json.py.",
        "-->",
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {VIEW_W} {VIEW_H}" width="{VIEW_W * 2}" height="{VIEW_H * 2}">',
        f'  <rect id="background" x="0" y="0" width="{VIEW_W}" height="{VIEW_H}" fill="{BACKGROUND}"/>',
        f'  <g fill="{BONE_COLOR}">',
    ]
    current = None
    for bone_id, zone_id in order:
        if bone_id != current:
            if current is not None:
                lines.append("    </g>")
            lines.append(f'    <g id="{bone_id}">')
            current = bone_id
        lines.append(f'      <path id="{bone_id}__{zone_id}" d="{path_d(zones[(bone_id, zone_id)])}"/>')
    lines += ["    </g>", "  </g>", "</svg>", ""]
    (ART_DIR / f"{species_id}.svg").write_text("\n".join(lines), encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Skelett-SVGs der Arten zeichnen (AP-27).")
    parser.add_argument("species", nargs="*", help="Art-IDs (Standard: alle)")
    parser.add_argument("--list", action="store_true", help="Zonenflaechen ausgeben")
    args = parser.parse_args(argv)
    for sid in args.species or list(SPECIES):
        if sid not in SPECIES:
            raise SystemExit(f"unbekannte Art: {sid}")
        zones, order = build(sid)
        write_svg(sid, zones, order)
        smallest = min(order, key=lambda k: zones[k].area)
        print(f"{sid}: geschrieben, kleinste Zone {smallest[0]}__{smallest[1]} = {zones[smallest].area:.0f}")
        if args.list:
            for k in order:
                print(f"  {k[0]}__{k[1]}: {zones[k].area:.0f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
