"""Giant deer (Megaloceros giganteus), adult male, standing.

Built after art/models3d/MODELLING_RULES.md. All proportions derive from MEASURES_MM
and the pose angles below; joint positions are computed, not placed by hand.
"""
import math
import numpy as np
from sdf import (F, V, unit, frame, to_local, sphere, cone, capsule, box, ell, tri_plate, smin, smax, tube,
                 fold_z, scaled_y)
from parts import vertebra, long_bone, rib_path, rib, cloven_foot

SPECIES = 'giant_deer'
UNIT_M = 0.50  # one unit = skull length

# Greatest lengths in mm. Sources:
#  [1] Lister & Stuart 2019, Quaternary International: Irish Allerod sample and Kamyshlov skeleton
#  [2] Shpansky 2014, Paleontological Journal: skeleton from Dzhambul near Pavlodar
#  [3] Aaris-Sorensen & Liljegren 2004, Boreas: Scandinavian finds, antler length
#  [4] Croitor 2021, Quaternary: antler length and span
#  [5] estimated from mounted skeletons, no published value used
MEASURES_MM = {
    'skull': 500,          # [2] basal length 465, so about 500 overall
    'humerus': 380,        # [2] 355 to 400
    'radius': 410,         # [1] 385 to 423
    'metacarpal': 330,     # [1] 322 to 354
    'femur': 480,          # [1] 460 to 508
    'tibia': 470,          # [2]
    'metatarsal': 360,     # [1] 345 to 376
    'scapula': 430,        # [5]
    'antler': 1350,        # [3] 1.35 to 1.40 m along the beam
    'antler_span': 2700,   # [3][4] 2.3 to 3.5 m
    'shoulder_height': 1900,  # skeleton; about 2.0 to 2.1 m in life
}
# C7 T13 L6 S4, tail 10 to 14 in deer; 13 pairs of ribs.
N_CERVICAL, N_THORACIC, N_LUMBAR, N_CAUDAL = 7, 13, 6, 11


def U(mm):
    return mm / 1000.0 / UNIT_M


def P2(p, z):
    return V(p[0], p[1], z)


# ---------------------------------------------------------------------------
# pose: spine as a chain of vertebrae, each with length and heading (degrees,
# negative runs downhill towards the tail)
# ---------------------------------------------------------------------------
CERV = [(0.12, -30), (0.22, -42), (0.185, -55), (0.185, -62), (0.185, -62), (0.185, -55), (0.185, -42)]
THOR = [(0.115, a) for a in (-28, -16, -8, -3, 0, 1, 2, 2, 2, 3, 3, 3, 3)]
LUMB = [(0.14, a) for a in (3, 2, 1, 0, -1, -2)]
SACR = (0.44, -8)
CAUD = [(0.078 - 0.003 * i, -20 - 5.5 * i) for i in range(N_CAUDAL)]
NECK_START = (0.525, -0.075)


def _chain(start, segs):
    out, p = [], np.array(start, dtype=float)
    for L, deg in segs:
        a = math.radians(deg)
        d = np.array([math.cos(a), math.sin(a)])
        out.append(dict(c=tuple(p + d * L * 0.5), ang=a, L=L, s=tuple(p), e=tuple(p + d * L)))
        p = p + d * L
    return out, tuple(p)


C_V, _p = _chain(NECK_START, CERV)
T_V, _p = _chain(_p, THOR)
L_V, _p = _chain(_p, LUMB)
S_V, _p = _chain(_p, [SACR])
K_V, _p = _chain(_p, CAUD)
S_V = S_V[0]

# thoracic spinous processes: height and backward lean per vertebra
T_SPINE = [0.44, 0.58, 0.70, 0.70, 0.64, 0.55, 0.46, 0.38, 0.31, 0.26, 0.22, 0.19, 0.18]
# backward tilt of each spinous process against the vertical, in degrees; the lean inside the
# vertebra frame follows from it, so neighbours stay parallel where the spine bends
T_TILT = [30, 30, 30, 29, 27, 24, 20, 16, 12, 7, 2, -3, -6]
T_LEAN = [math.tan(math.radians(T_TILT[i] + THOR[i][1])) for i in range(N_THORACIC)]

# rib cage: half width, half depth, backward lean and end angle per rib
RIB_A = [0.17, 0.21, 0.26, 0.31, 0.36, 0.41, 0.45, 0.48, 0.50, 0.50, 0.48, 0.45, 0.42]
RIB_B = [0.50, 0.55, 0.59, 0.62, 0.65, 0.66, 0.66, 0.65, 0.63, 0.60, 0.56, 0.52, 0.48]
RIB_LEAN = [0.10 + 0.03 * i for i in range(N_THORACIC)]
RIB_END = [165, 165, 165, 165, 165, 165, 165, 165, 152, 140, 128, 116, 102]
RIB_PTS = [rib_path((T_V[i]['c'][0], T_V[i]['c'][1]), RIB_A[i], RIB_B[i], RIB_LEAN[i], math.radians(RIB_END[i]))
           for i in range(N_THORACIC)]

# ---------------------------------------------------------------------------
# limbs: joint to joint distances are the greatest lengths minus the joint ends
# ---------------------------------------------------------------------------
# share of a bone's greatest length that lies between its joint centres; the rest is joint ends
J = {'scapula': 0.87, 'humerus': 0.80, 'radius': 0.975, 'metacarpal': 0.975, 'femur': 0.79, 'tibia': 0.90,
     'metatarsal': 0.975}


def _dir(deg):
    a = math.radians(deg)
    return np.array([math.cos(a), math.sin(a)])


TOE_P1, TOE_P2, HOOF_TOP, TOE_DROP = 0.155, 0.10, 0.085, 0.045
TOE_SIN = math.sin(math.radians(56.0))

SCAP_TOP = np.array([T_V[3]['c'][0] + 0.03, T_V[3]['c'][1] + 0.34])
SHOULDER = SCAP_TOP + U(MEASURES_MM['scapula']) * J['scapula'] * _dir(180 + 64)
ELBOW = SHOULDER - 0.085 * unit(np.append(SCAP_TOP - SHOULDER, 0))[:2] + U(MEASURES_MM['humerus']) * J['humerus'] * _dir(-60)
WRIST = ELBOW + np.array([0.0, -0.06]) + U(MEASURES_MM['radius']) * J['radius'] * _dir(-92)
CARPUS_H = 0.10
FETLOCK_F = WRIST + np.array([0.0, -CARPUS_H]) + U(MEASURES_MM['metacarpal']) * J['metacarpal'] * _dir(-90)
FLOOR = float(FETLOCK_F[1] - TOE_DROP - TOE_SIN * (TOE_P1 + TOE_P2) - HOOF_TOP)
Z_SHOULDER, Z_SCAP_TOP, Z_ELBOW, Z_WRIST = 0.33, 0.17, 0.365, 0.34

HIP = np.array([S_V['c'][0] + 0.10, S_V['c'][1] - 0.27])
Z_HIP, Z_KNEE, Z_HOCK = 0.27, 0.37, 0.31
KNEE = HIP + U(MEASURES_MM['femur']) * J['femur'] * _dir(180 + 62)
TARSUS_H = 0.12
_mt = U(MEASURES_MM['metatarsal']) * J['metatarsal']
_fet_y = FLOOR + TOE_DROP + TOE_SIN * (TOE_P1 + TOE_P2) + HOOF_TOP
_hock_y = _fet_y + _mt * math.sin(math.radians(86)) + TARSUS_H
_tib = U(MEASURES_MM['tibia']) * J['tibia']
TIBIA_DEG = math.degrees(math.asin((KNEE[1] - 0.10 - _hock_y) / _tib))
HOCK = KNEE + np.array([0.0, -0.10]) + _tib * _dir(-TIBIA_DEG)
FETLOCK_H = HOCK + np.array([-0.01, -TARSUS_H]) + _mt * _dir(180 + 86)


# ---------------------------------------------------------------------------
# skull: one form, split into bones by regions
# ---------------------------------------------------------------------------
TEETH_X = [-0.222, -0.184, -0.146, -0.100, -0.047, 0.008]
TEETH_HX = [0.016, 0.016, 0.016, 0.022, 0.023, 0.024]
PEDICLE_A, PEDICLE_B = V(0.19, 0.12, 0.10), V(0.215, 0.20, 0.14)


def _ripple(q, zc, y0, y1, ph):
    w = 0.003 * np.sin(q[:, 0] * 118.0 + ph) * np.cos((q[:, 2] - zc) * 200.0)
    t = np.clip((q[:, 1] - y0) / (y1 - y0), 0.0, 1.0)
    return w * t * t * (3.0 - 2.0 * t)


def skull_shape(P):
    q = fold_z(P)
    x, y, z = q[:, 0], q[:, 1], q[:, 2]
    d = ell(q, (0.30, 0.02, 0.0), (0.20, 0.13, 0.108))
    d = smin(d, ell(q, (0.08, 0.045, 0.0), (0.18, 0.115, 0.14)), 0.06)
    d = smin(d, ell(q, (0.06, 0.105, 0.115), (0.11, 0.035, 0.085)), 0.04)
    d = smin(d, ell(q, (-0.20, -0.01, 0.0), (0.23, 0.105, 0.092)), 0.07)
    d = smin(d, ell(q, (-0.37, -0.05, 0.0), (0.14, 0.062, 0.062)), 0.05)
    d = smin(d, ell(q, (-0.455, -0.085, 0.028), (0.05, 0.022, 0.034)), 0.03)
    d = smin(d, capsule(q, (-0.25, -0.105, 0.085), (0.03, -0.10, 0.115), 0.032), 0.03)
    # eye socket with a closed bony ring
    d = smin(d, sphere(q, (0.035, 0.03, 0.165), 0.092), 0.03)
    d = smax(d, -ell(q, (0.035, 0.03, 0.215), (0.066, 0.066, 0.085)), 0.012)
    # cheek arch and jaw joint
    qa = scaled_y(q, 0.6)
    arch = tube(qa, [V(-0.03, -0.027, 0.195), V(0.10, -0.024, 0.208), V(0.24, -0.015, 0.198), V(0.31, -0.012, 0.15)],
                [0.014] * 4)
    d = smin(d, arch, 0.025)
    d = smin(d, ell(q, (0.31, -0.03, 0.14), (0.045, 0.022, 0.05)), 0.03)
    # back of the head
    d = smin(d, ell(q, (0.455, 0.085, 0.0), (0.03, 0.06, 0.10)), 0.04)
    d = smin(d, ell(q, (0.455, -0.035, 0.0), (0.05, 0.07, 0.095)), 0.03)
    d = smin(d, ell(q, (0.40, -0.045, 0.095), (0.07, 0.05, 0.04)), 0.03)
    d = smax(d, x - 0.488 - 0.10 * y, 0.012)
    d = smin(d, ell(q, (0.472, -0.08, 0.045), (0.036, 0.03, 0.032)), 0.015)
    d = smin(d, cone(q, (0.44, -0.07, 0.105), (0.45, -0.17, 0.10), 0.022, 0.011), 0.02)
    d = smin(d, ell(q, (0.35, -0.105, 0.085), (0.04, 0.028, 0.032)), 0.02)
    d = smin(d, ell(q, (0.20, -0.085, 0.0), (0.24, 0.03, 0.055)), 0.03)
    # antler pedicle
    d = smin(d, cone(q, PEDICLE_A, PEDICLE_B, 0.06, 0.052), 0.03)
    d = smax(d, (q - PEDICLE_B) @ unit(PEDICLE_B - PEDICLE_A), 0.004)
    # openings
    d = smax(d, -ell(q, (-0.47, 0.0, 0.0), (0.115, 0.04, 0.075)), 0.015)
    d = smax(d, -ell(q, (-0.18, -0.175, 0.0), (0.33, 0.07, 0.055)), 0.025)
    d = smax(d, -ell(q, (-0.27, -0.01, 0.0), (0.19, 0.05, 0.05)), 0.012)
    d = smax(d, -ell(q, (0.29, 0.02, 0.0), (0.15, 0.08, 0.068)), 0.015)
    d = smax(d, -capsule(q, (0.40, -0.045, 0.0), (0.56, -0.05, 0.0), 0.028), 0.008)
    d = smax(d, -ell(q, (-0.085, -0.005, 0.115), (0.045, 0.03, 0.025)), 0.015)
    d = smax(d, -capsule(q, (-0.15, 0.06, 0.07), (-0.08, 0.075, 0.085), 0.014), 0.006)
    # cheek teeth: three premolars, three molars, no upper front teeth
    t = None
    for i, (tx, hx) in enumerate(zip(TEETH_X, TEETH_HX)):
        zc = 0.088 + (tx + 0.222) / 0.23 * 0.024
        b = box(q, (tx, -0.15, zc), (hx - 0.006, 0.014, 0.008 if i < 3 else 0.012), r=0.006)
        t = b if t is None else np.minimum(t, b)
    t = t + _ripple(q, 0.10, -0.155, -0.17, 0.7)
    return np.minimum(d, t)


def skull_region(P, k):
    """Negative inside bone k: 0 occipital, 1 premaxilla, 2 nasal, 3 maxilla, 4 jugal,
    5 sphenoid, 6 temporal, 7 frontal, 8 parietal. Earlier regions win."""
    q = fold_z(P)
    x, y, z = q[:, 0], q[:, 1], q[:, 2]
    R = [
        0.405 - x,
        np.maximum(x + 0.375 + 0.2 * z, y - 0.005),
        np.maximum(np.maximum(x + 0.03, 0.095 + 0.17 * (x + 0.03) - y), z - 0.045),
        np.minimum(x + 0.03, np.maximum(np.maximum(x - 0.07, y + 0.05), 0.055 - z)),
        np.maximum(np.maximum(x - 0.20, 0.165 - z), y),
        np.maximum(y + 0.06, z - 0.07),
        np.maximum(0.20 - x, y - 0.03),
        x - 0.275 - 0.5 * np.maximum(y - 0.10, 0.0),
    ]
    acc = np.full(len(P), -1000.0, dtype=F)
    for i in range(k):
        acc = np.maximum(acc, -R[i])
    return acc if k == 8 else np.maximum(R[k], acc)


_skull_cache = {}


def _skull_cached(P):
    key = (len(P), P[0].tobytes(), P[-1].tobytes())
    if key not in _skull_cache:
        _skull_cache[key] = skull_shape(P)
    return _skull_cache[key]


def skull_bone(k):
    return lambda P: np.maximum(_skull_cached(P), skull_region(P, k))


def mandible(P):
    q = fold_z(P)
    s = lambda p: V(p[0], (p[1] + 0.24) * 0.5 - 0.24, p[2])
    qa = scaled_y(q, 0.5, -0.24)
    d = tube(qa, [s((-0.46, -0.165, 0.018)), s((-0.36, -0.205, 0.05)), s((-0.22, -0.24, 0.09)),
                  s((0.03, -0.245, 0.118)), s((0.20, -0.24, 0.135))], [0.016, 0.014, 0.021, 0.024, 0.024], 0.02)
    d = smin(d, ell(q, (0.27, -0.17, 0.135), (0.085, 0.10, 0.02)), 0.04)
    d = smin(d, ell(q, (0.30, -0.25, 0.135), (0.065, 0.05, 0.02)), 0.03)
    d = smin(d, cone(q, (0.30, -0.12, 0.135), (0.325, -0.085, 0.135), 0.03, 0.022), 0.02)
    d = smin(d, capsule(q, (0.325, -0.078, 0.108), (0.325, -0.078, 0.168), 0.02), 0.015)
    d = smin(d, tube(q, [V(0.235, -0.10, 0.135), V(0.225, -0.01, 0.135), V(0.245, 0.03, 0.135)],
                     [0.024, 0.018, 0.012]), 0.025)
    t = None
    for i, (tx, hx) in enumerate(zip(TEETH_X, TEETH_HX)):
        zc = 0.088 + (tx + 0.222) / 0.23 * 0.028
        b = box(q, (tx + 0.006, -0.188, zc), (hx - 0.006, 0.013, 0.006 if i < 3 else 0.009), r=0.005)
        t = b if t is None else np.minimum(t, b)
    t = t + _ripple(q, 0.10, -0.185, -0.172, 2.1)
    for i in range(4):
        t = np.minimum(t, ell(q, (-0.492 + 0.007 * i, -0.138 - 0.004 * i, 0.010 + 0.017 * i), (0.026, 0.011, 0.0105)))
    return np.minimum(d, t)


# antler: beam, palm with tines on the outer and rear rim, brow tine
ANT_BEAM_DIR = unit((0.12, 0.17, 1.0))
ANT_PALM_DIR = unit((0.18, 0.32, 1.0))
ANT_BASE = PEDICLE_B + 0.012 * unit(PEDICLE_B - PEDICLE_A)
ANT_B1 = ANT_BASE + 0.75 * ANT_BEAM_DIR
ANT_PALM_HALF = 0.74
ANT_PALM_C = ANT_B1 + (ANT_PALM_HALF - 0.08) * ANT_PALM_DIR
ANT_R = frame(ANT_PALM_DIR, hint=(1.0, 0.65, -0.2))  # rows: along the palm, across (rear edge raised), normal
# tines: (position along the palm, position across, length, direction as along/across/normal)
ANT_TINES = [(-0.40, 0.24, 0.40, (0.20, 1.0, 0.12)), (-0.05, 0.29, 0.50, (0.45, 1.0, 0.12)),
             (0.30, 0.27, 0.52, (0.75, 0.9, 0.12)), (0.58, 0.17, 0.50, (1.0, 0.5, 0.10)),
             (0.70, -0.02, 0.44, (1.0, 0.0, 0.10)), (0.52, -0.22, 0.30, (0.8, -0.6, 0.08)),
             (-0.48, -0.12, 0.30, (-0.1, -1.0, 0.05))]


def antler(P):
    burr = V(*ANT_BASE)
    ax = unit(PEDICLE_B - PEDICLE_A)
    d = ell(P, burr + 0.03 * ax, (0.032, 0.082, 0.082), frame(ax, hint=(1.0, 0.0, 0.0)))
    d = smin(d, cone(P, burr, ANT_B1, 0.062, 0.068), 0.03)
    q = to_local(P, ANT_PALM_C, ANT_R)
    palm = ell(q, (0, 0, 0), (ANT_PALM_HALF, 0.30, 0.03))
    palm = smin(palm, ell(q, (-0.35, 0.0, 0.0), (0.42, 0.17, 0.045)), 0.05)
    d = smin(d, palm, 0.08)
    for u, w, L, dr in ANT_TINES:
        a = V(u, w, 0.0)
        d = smin(d, cone(q, a, a + L * unit(dr), 0.05, 0.014), 0.05)
    # brow tine near the burr, pointing forward and forked
    b0 = burr + 0.10 * ANT_BEAM_DIR
    d = smin(d, tube(P, [b0, b0 + V(-0.22, 0.08, 0.06), b0 + V(-0.40, 0.20, 0.10)], [0.045, 0.034, 0.014]), 0.03)
    d = smin(d, cone(P, b0 + V(-0.22, 0.08, 0.06), b0 + V(-0.34, 0.06, 0.20), 0.03, 0.013), 0.02)
    # flat cut where the antler sat on the pedicle
    return np.maximum(d, -((P - burr) @ ax))


# ---------------------------------------------------------------------------
# trunk
# ---------------------------------------------------------------------------
def cervical(i):
    v = C_V[i]
    if i == 0:
        kw = dict(rb=0.078, hs=0.0, tl=0.0, wing=0.12)
    elif i == 1:
        kw = dict(rb=0.074, hs=0.09, sl=0.20, lean=0.25, tl=0.05, tdroop=0.04, tsweep=0.05, tr=0.024, taper=0.1)
    else:
        kw = dict(rb=0.078, hs=[0.05, 0.055, 0.065, 0.09, 0.20][i - 2], sl=0.08, lean=-0.15,
                  tl=0.085, tdroop=0.07, tsweep=-0.03, tr=0.03)
    return lambda P: vertebra(P, v['c'], v['ang'], v['L'], **kw)


def thoracic(i):
    v = T_V[i]
    return lambda P: vertebra(P, v['c'], v['ang'], v['L'], rb=0.058, hs=T_SPINE[i], sl=0.07, lean=T_LEAN[i],
                              tl=0.045, tdroop=-0.02, tr=0.02)


def lumbar(i):
    v = L_V[i]
    return lambda P: vertebra(P, v['c'], v['ang'], v['L'], rb=0.064, hs=0.15, sl=0.10, lean=-0.15, taper=0.15,
                              tl=0.20 + 0.012 * min(i, 3), tdroop=0.03, tsweep=-0.05, tr=0.03, tflat=2.2)


def sacrum(P):
    q = fold_z(P)
    s, e = V(S_V['s'][0], S_V['s'][1], 0), V(S_V['e'][0], S_V['e'][1], 0)
    ax = unit(e - s)
    d = cone(q, s + 0.045 * ax, e - 0.03 * ax, 0.062, 0.036)
    up = V(-ax[1], ax[0], 0.0)
    R = np.stack([ax, up, V(0, 0, 1)])
    d = smin(d, ell(q, s + 0.10 * ax + 0.01 * up, (0.085, 0.04, 0.105), R), 0.03)
    d = smin(d, box(q, s + 0.20 * ax + 0.115 * up, (0.15, 0.05, 0.005), 0.008, R), 0.02)
    for j in range(4):
        d = smin(d, sphere(q, s + (0.07 + 0.10 * j) * ax + 0.03 * up + V(0, 0, 0.055 - 0.008 * j), 0.022), 0.015)
    return d


def caudal(i):
    v = K_V[i]
    s, e = V(v['s'][0], v['s'][1], 0), V(v['e'][0], v['e'][1], 0)
    ax = unit(e - s)
    r = 0.027 - 0.0015 * i
    a, b = s + (r + 0.003) * ax, e - (r + 0.003) * ax
    return lambda P: smin(capsule(P, a, b, r * 0.8), np.minimum(sphere(P, a, r), sphere(P, b, r * 0.95)), 0.01)


def rib_fn(i):
    pts = RIB_PTS[i]
    return lambda P: rib(P, pts, 0.021)


STERN_A = RIB_PTS[0][-1] * V(1, 1, 0) + V(-0.03, -0.05, 0)
STERN_B = RIB_PTS[7][-1] * V(1, 1, 0) + V(0.02, -0.06, 0)


def sternum(P):
    q = fold_z(P)
    d = None
    for j in range(7):
        a = STERN_A + (STERN_B - STERN_A) * (j / 7.0)
        b = STERN_A + (STERN_B - STERN_A) * ((j + 1) / 7.0)
        m = (a + b) * 0.5
        R = frame(b - a, hint=(0, 1, 0))
        s = ell(q, m, (float(np.linalg.norm(b - a)) * 0.56, 0.036 - 0.002 * j, 0.045 + 0.004 * j), R)
        d = s if d is None else smin(d, s, 0.015)
    return d


HIP_TUBER_SACRALE = V(S_V['s'][0] + 0.05, S_V['s'][1] + 0.17, 0.085)
HIP_TUBER_COXAE = V(HIP[0] - 0.52, HIP[1] + 0.36, 0.50)
HIP_TUBER_ISCHII = V(HIP[0] + 0.44, HIP[1] + 0.10, 0.31)


def hip(P):
    """Hip bone: ilium blade fanning out to the crest, socket, ischium and pubis around the hip hole."""
    q = fold_z(P)
    H = V(HIP[0], HIP[1], Z_HIP)
    neck = H + V(-0.07, 0.07, 0.0)
    ts, tc, ti = HIP_TUBER_SACRALE, HIP_TUBER_COXAE, HIP_TUBER_ISCHII
    d = tri_plate(q, neck, ts, tc, 0.015)
    d = smin(d, capsule(q, ts, tc, 0.024), 0.02)
    d = smin(d, sphere(q, tc, 0.042), 0.02)
    d = smin(d, sphere(q, ts, 0.034), 0.02)
    d = smin(d, cone(q, neck + V(-0.10, 0.10, 0.03), H, 0.045, 0.06), 0.04)
    d = smin(d, sphere(q, H, 0.098), 0.03)
    d = smax(d, -sphere(q, H + V(0, 0, 0.05), 0.084), 0.01)
    d = smin(d, cone(q, H + V(0.05, 0.0, 0.0), ti, 0.05, 0.04), 0.03)
    d = smin(d, ell(q, ti, (0.07, 0.055, 0.045)), 0.03)
    sf, sr = V(HIP[0] + 0.05, HIP[1] - 0.17, 0.0), V(HIP[0] + 0.33, HIP[1] - 0.13, 0.0)
    d = smin(d, cone(q, H + V(-0.02, -0.07, -0.03), sf, 0.036, 0.03), 0.03)
    d = smin(d, cone(q, ti + V(-0.03, -0.03, 0), sr, 0.034, 0.03), 0.03)
    return smin(d, capsule(q, sf, sr, 0.03), 0.02)


# ---------------------------------------------------------------------------
# forelimb
# ---------------------------------------------------------------------------
S3 = V(SHOULDER[0], SHOULDER[1], Z_SHOULDER)
ST3 = V(SCAP_TOP[0], SCAP_TOP[1], Z_SCAP_TOP)
SCAP_AX = unit(ST3 - S3)
SCAP_R = frame(SCAP_AX, hint=(1.0, 0.0, 0.0))  # rows: up the blade, to the rear, inward
SCAP_LEN = float(np.linalg.norm(ST3 - S3))
E3 = V(ELBOW[0], ELBOW[1], Z_ELBOW)
W3 = V(WRIST[0], WRIST[1], Z_WRIST)
FF3 = V(FETLOCK_F[0], FETLOCK_F[1], Z_WRIST)
HUM_HEAD = S3 - 0.085 * SCAP_AX + V(0.02, 0.0, 0.0)


def scapula(P):
    q = to_local(P, S3, SCAP_R)
    a, b, c = q[:, 0], q[:, 1], q[:, 2]
    L = SCAP_LEN
    kf, kr = (0.17 - 0.05) / (L - 0.06), (0.25 - 0.05) / (L - 0.06)
    poly = np.maximum(np.maximum(a - L, 0.06 - a),
                      np.maximum((-b - 0.05 - kf * (a - 0.06)) / math.hypot(1, kf),
                                 (b - 0.05 - kr * (a - 0.06)) / math.hypot(1, kr)))
    d = np.maximum(poly, np.abs(c) - 0.008) - 0.007
    rh = 0.075 - 0.063 * np.clip((a - 0.15) / (L - 0.20), 0.0, 1.0)
    ridge = np.maximum(np.maximum(np.abs(b) - 0.004, np.maximum(c, -c - rh)), np.maximum(0.14 - a, a - L + 0.05)) - 0.006
    d = smin(d, ridge, 0.015)
    d = smin(d, capsule(q, (L, -0.17, 0.0), (L, 0.25, 0.0), 0.013), 0.015)
    d = smin(d, cone(q, (0.04, 0.0, 0.0), (0.24, 0.0, 0.0), 0.05, 0.03), 0.04)
    d = smin(d, ell(q, (0.055, 0.0, 0.0), (0.05, 0.06, 0.045)), 0.03)
    return smin(d, cone(q, (0.05, -0.05, 0.0), (0.0, -0.085, 0.015), 0.022, 0.014), 0.015)


def humerus(P):
    top = HUM_HEAD + V(-0.01, 0.0, 0.03)
    d = capsule(P, top, E3 + V(0.0, 0.03, 0.0), 0.05)
    d = smin(d, sphere(P, HUM_HEAD, 0.074), 0.04)
    d = smin(d, ell(P, HUM_HEAD + V(-0.075, 0.015, 0.045), (0.04, 0.06, 0.04)), 0.03)
    d = smin(d, capsule(P, E3 + V(0, 0, -0.062), E3 + V(0, 0, 0.062), 0.05), 0.04)
    return smin(d, sphere(P, E3 + V(0.045, 0.035, 0.0), 0.045), 0.03)


RAD_TOP = E3 + V(-0.012, -0.092, -0.008)
RAD_END = W3 + V(0.0, 0.04, 0.0)


def radius(P):
    d = cone(P, RAD_TOP, RAD_END, 0.04, 0.038)
    d = smin(d, ell(P, RAD_TOP, (0.048, 0.03, 0.07)), 0.035)
    return smin(d, ell(P, RAD_END, (0.05, 0.035, 0.066)), 0.035)


def ulna(P):
    top = E3 + V(0.118, 0.15, 0.0)
    mid = E3 + V(0.108, -0.075, -0.005)
    d = cone(P, top, mid, 0.036, 0.03)
    d = smin(d, sphere(P, top, 0.042), 0.02)
    return smin(d, tube(P, [mid, (RAD_TOP + RAD_END) * 0.5 + V(0.066, 0, 0), RAD_END + V(0.062, 0.02, 0.0)],
                        [0.028, 0.017, 0.016]), 0.02)


def carpus(P):
    c = W3 + V(0.0, -0.055, 0.0)
    d = box(P, c + V(0.0, 0.017, 0.0), (0.04, 0.008, 0.052), 0.011)
    d = smin(d, box(P, c + V(0.0, -0.019, 0.0), (0.036, 0.007, 0.047), 0.011), 0.008)
    return smin(d, sphere(P, c + V(0.06, 0.012, 0.012), 0.024), 0.012)


def cannon(top, fet, r, deep):
    """Fused metapodial: flat proximal end, two pulley-shaped condyles at the fetlock."""
    a = top + V(0.0, -0.03, 0.0)
    b = fet + V(0.0, 0.035, 0.0)

    def fn(P):
        d = cone(P, a, b, r, r * 0.95)
        d = smin(d, ell(P, a, (0.045 * deep, 0.025, 0.055)), 0.03)
        for s in (-1.0, 1.0):
            d = smin(d, sphere(P, fet + V(0.0, 0.022, s * 0.029), 0.031), 0.02)
        groove = capsule(P, top + V(-r - 0.012, -0.08, 0.0), fet + V(-r - 0.012, 0.08, 0.0), 0.012)
        return smax(d, -groove, 0.008)
    return fn


metacarpal = cannon(W3 + V(0, -CARPUS_H, 0), FF3, 0.033, 1.0)


def forefoot(P):
    return cloven_foot(P, FETLOCK_F - np.array([0.0, TOE_DROP - 0.02]), -1.0, Z_WRIST, FLOOR, TOE_P1, TOE_P2,
                       spread=0.028)


# ---------------------------------------------------------------------------
# hindlimb
# ---------------------------------------------------------------------------
H3 = V(HIP[0], HIP[1], Z_HIP + 0.045)
K3 = V(KNEE[0], KNEE[1], Z_KNEE)
A3 = V(HOCK[0], HOCK[1], Z_HOCK)
FH3 = V(FETLOCK_H[0], FETLOCK_H[1], Z_HOCK)


def femur(P):
    top = H3 + V(0.012, 0.02, 0.105)
    d = capsule(P, top, K3 + V(0.0, 0.03, 0.0), 0.052)
    d = smin(d, capsule(P, H3, top, 0.04), 0.03)
    d = smin(d, sphere(P, H3, 0.068), 0.02)
    d = smin(d, ell(P, top + V(0.03, 0.06, 0.02), (0.05, 0.06, 0.045)), 0.03)
    for s in (-1.0, 1.0):
        d = smin(d, sphere(P, K3 + V(0.035, 0.0, s * 0.044), 0.058), 0.03)
    return smin(d, sphere(P, K3 + V(-0.035, 0.03, 0.0), 0.05), 0.03)


def patella(P):
    return ell(P, K3 + V(-0.118, 0.035, 0.0), (0.026, 0.05, 0.04))


TIB_TOP = K3 + V(0.0, -0.108, 0.0)
TIB_END = A3 + V(0.0, 0.045, 0.0)


def tibia(P):
    d = cone(P, TIB_TOP + V(0, -0.01, 0), TIB_END, 0.052, 0.036)
    d = smin(d, ell(P, TIB_TOP, (0.085, 0.036, 0.088)), 0.04)
    d = smin(d, cone(P, TIB_TOP + V(-0.075, -0.01, 0.0), TIB_TOP + (TIB_END - TIB_TOP) * 0.4 + V(-0.035, 0, 0), 0.03, 0.02), 0.03)
    return smin(d, ell(P, TIB_END, (0.05, 0.032, 0.056)), 0.03)


def tarsus(P):
    c = A3 + V(0.0, -0.06, 0.0)
    d = box(P, c + V(0.0, 0.02, 0.0), (0.038, 0.012, 0.04), 0.012)
    d = smin(d, box(P, c + V(0.0, -0.024, 0.0), (0.034, 0.01, 0.038), 0.012), 0.008)
    d = smin(d, cone(P, c + V(0.045, 0.0, 0.012), A3 + V(0.16, 0.14, 0.012), 0.034, 0.03), 0.02)
    return smin(d, sphere(P, A3 + V(0.165, 0.15, 0.012), 0.036), 0.015)


metatarsal = cannon(A3 + V(-0.01, -TARSUS_H, 0), FH3, 0.031, 1.15)


def hindfoot(P):
    return cloven_foot(P, FETLOCK_H - np.array([0.0, TOE_DROP - 0.02]), -1.0, Z_HOCK, FLOOR, TOE_P1, TOE_P2,
                       spread=0.028)


# ---------------------------------------------------------------------------
# bone list
# ---------------------------------------------------------------------------
def _bb(pts, pad):
    a = np.array([np.asarray(p, dtype=float) for p in pts])
    return tuple(a.min(0) - pad), tuple(a.max(0) + pad)


def _vbb(v, up, down, side):
    c = v['c']
    r = v['L'] * 0.5 + 0.05
    return (c[0] - r - up * 0.6, c[1] - down, -side), (c[0] + r + up * 0.6, c[1] + up, side)


def bones():
    B = []

    def add(id, de, en, group, fn, box, h=0.005, pair=False, cut=False, tris=1500, measure=None):
        B.append(dict(id=id, de=de, en=en, group=group, fn=fn, lo=box[0], hi=box[1], h=h, pair=pair, cut=cut,
                      tris=tris, measure=measure))

    SK = ((-0.56, -0.22, -0.30), (0.58, 0.30, 0.30))
    add('occipital', 'Hinterhauptbein', 'Occipital bone', 'skull', skull_bone(0), SK, 0.004, tris=1600)
    add('sphenoid', 'Keilbein', 'Sphenoid bone', 'skull', skull_bone(5), SK, 0.004, tris=1200)
    for k, i, de, en, n in [(1, 'premaxilla', 'Zwischenkieferbein', 'Premaxilla', 700), (2, 'nasal', 'Nasenbein', 'Nasal bone', 700),
                            (3, 'maxilla', 'Oberkieferbein', 'Maxilla', 3000), (4, 'jugal', 'Jochbein', 'Zygomatic bone', 900),
                            (6, 'temporal', 'Schläfenbein', 'Temporal bone', 1500), (7, 'frontal', 'Stirnbein', 'Frontal bone', 2200),
                            (8, 'parietal', 'Scheitelbein', 'Parietal bone', 1200)]:
        add(i, de, en, 'skull', skull_bone(k), SK, 0.004, pair=True, cut=True, tris=n)
    add('mandible', 'Unterkieferhälfte', 'Mandible half', 'skull', mandible, ((-0.54, -0.33, 0), (0.42, 0.08, 0.24)),
        0.004, pair=True, cut=True, tris=3000)
    tips = [ANT_PALM_C + np.array([u, w, 0.0]) @ ANT_R + L * (unit(dr) @ ANT_R) for u, w, L, dr in ANT_TINES]
    add('antler', 'Geweihstange', 'Antler', 'skull', antler,
        _bb(tips + [ANT_BASE, ANT_BASE + V(-0.42, 0.2, 0.1), ANT_PALM_C + V(0.5, 0.3, 0), ANT_PALM_C + V(-0.4, -0.3, 0)], 0.12),
        0.007, pair=True, tris=6000, measure='antler')
    for i in range(N_CERVICAL):
        add('cervical_%d' % (i + 1), '%d. Halswirbel' % (i + 1), 'Cervical vertebra %d' % (i + 1), 'cervical',
            cervical(i), _vbb(C_V[i], 0.42, 0.20, 0.28), tris=1350)
    for i in range(N_THORACIC):
        add('thoracic_%d' % (i + 1), '%d. Brustwirbel' % (i + 1), 'Thoracic vertebra %d' % (i + 1), 'thoracic',
            thoracic(i), _vbb(T_V[i], 0.95, 0.14, 0.16), tris=1350)
    for i in range(N_LUMBAR):
        add('lumbar_%d' % (i + 1), '%d. Lendenwirbel' % (i + 1), 'Lumbar vertebra %d' % (i + 1), 'lumbar',
            lumbar(i), _vbb(L_V[i], 0.40, 0.16, 0.36), tris=1350)
    add('sacrum', 'Kreuzbein', 'Sacrum', 'pelvis', sacrum,
        ((S_V['s'][0] - 0.08, S_V['e'][1] - 0.14, -0.16), (S_V['e'][0] + 0.06, S_V['s'][1] + 0.24, 0.16)), tris=2000)
    add('hip', 'Hüftbein', 'Hip bone', 'pelvis', hip,
        _bb([HIP_TUBER_SACRALE, HIP_TUBER_COXAE, HIP_TUBER_ISCHII, V(HIP[0], HIP[1] - 0.17, 0.0)], 0.12), pair=True, cut=True, tris=3500)
    for i in range(N_CAUDAL):
        v = K_V[i]
        add('caudal_%d' % (i + 1), '%d. Schwanzwirbel' % (i + 1), 'Caudal vertebra %d' % (i + 1), 'tail', caudal(i),
            _bb([v['s'] + (0,), v['e'] + (0,)], 0.05), 0.0025, tris=250)
    for i in range(N_THORACIC):
        add('rib_%d' % (i + 1), '%d. Rippe' % (i + 1), 'Rib %d' % (i + 1), 'ribs', rib_fn(i), _bb(RIB_PTS[i], 0.05),
            0.0045, pair=True, tris=650)
    add('sternum', 'Brustbein', 'Sternum', 'sternum', sternum, _bb([STERN_A, STERN_B, STERN_A + V(0, 0, 0.1), STERN_B - V(0, 0, 0.1)], 0.1),
        tris=1500)
    for i, de, en, fn, bx, h, n, ms in [
        ('scapula', 'Schulterblatt', 'Scapula', scapula, _bb([S3, ST3, ST3 + V(0.3, 0, 0), ST3 - V(0.25, 0, 0)], 0.12), 0.0035, 2500, 'scapula'),
        ('humerus', 'Oberarmknochen', 'Humerus', humerus, _bb([HUM_HEAD, E3], 0.16), 0.005, 2000, 'humerus'),
        ('radius', 'Speiche', 'Radius', radius, _bb([RAD_TOP, RAD_END], 0.11), 0.0045, 1500, 'radius'),
        ('ulna', 'Elle', 'Ulna', ulna, _bb([E3 + V(0.118, 0.15, 0), RAD_END], 0.12), 0.004, 1200, None),
        ('carpus', 'Handwurzel', 'Carpus', carpus, _bb([W3 + V(0, -0.055, 0)], 0.12), 0.003, 600, None),
        ('metacarpal', 'Vorderes Kanonenbein', 'Metacarpal (cannon bone)', metacarpal, _bb([W3 + V(0, -CARPUS_H, 0), FF3], 0.10), 0.004, 1500, 'metacarpal'),
        ('forefoot', 'Vorderzehen', 'Fore toes', forefoot, _bb([FF3, V(FF3[0] - 0.35, FLOOR, FF3[2])], 0.11), 0.0035, 2500, None),
    ]:
        add(i, de, en, 'forelimb', fn, bx, h, pair=True, tris=n, measure=ms)
    for i, de, en, fn, bx, h, n, ms in [
        ('femur', 'Oberschenkelknochen', 'Femur', femur, _bb([H3, K3, H3 + V(0, 0, 0.14)], 0.14), 0.005, 2200, 'femur'),
        ('patella', 'Kniescheibe', 'Patella', patella, _bb([K3 + V(-0.118, 0.035, 0)], 0.08), 0.003, 300, None),
        ('tibia', 'Schienbein', 'Tibia', tibia, _bb([TIB_TOP, TIB_END], 0.13), 0.005, 2000, 'tibia'),
        ('tarsus', 'Fußwurzel', 'Tarsus', tarsus, _bb([A3 + V(0, -0.1, 0), A3 + V(0.165, 0.15, 0)], 0.09), 0.0035, 1000, None),
        ('metatarsal', 'Hinteres Kanonenbein', 'Metatarsal (cannon bone)', metatarsal, _bb([A3 + V(0, -TARSUS_H, 0), FH3], 0.10), 0.004, 1500, 'metatarsal'),
        ('hindfoot', 'Hinterzehen', 'Hind toes', hindfoot, _bb([FH3, V(FH3[0] - 0.35, FLOOR, FH3[2])], 0.11), 0.0035, 2500, None),
    ]:
        add(i, de, en, 'hindlimb', fn, bx, h, pair=True, tris=n, measure=ms)
    return B


FEET = ('forefoot', 'hindfoot')
MEASURE_CHECKS = {
    'skull': lambda m, floor: max(m[k].bounds[1][0] for k in ('occipital',)) - m['premaxilla_l'].bounds[0][0],
    'antler_span': lambda m, floor: 2.0 * m['antler_l'].bounds[1][2],
    'shoulder_height': lambda m, floor: max(m['thoracic_%d' % (i + 1)].bounds[1][1] for i in range(N_THORACIC)) - floor,
}
