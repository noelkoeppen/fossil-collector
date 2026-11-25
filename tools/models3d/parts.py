"""Reusable, parametrised bone building blocks shared by all species.

Differences between two bones of the same kind are parameters, never copied code
(art/models3d/MODELLING_RULES.md, rule 3.3).
"""
import math
import numpy as np
from sdf import F, V, length, sphere, cone, capsule, box, ell, smin, smax, tube


def spine_local(P, c, ang):
    """Vertebra frame: u along the spine towards the tail, v up, w sideways (folded)."""
    ca, sa = math.cos(ang), math.sin(ang)
    x = P[:, 0] - c[0]
    y = P[:, 1] - c[1]
    return np.stack([ca * x + sa * y, -sa * x + ca * y, np.abs(P[:, 2])], 1).astype(F)


def vertebra(P, c, ang, L, rb, hs=0.0, sl=0.07, lean=0.0, tl=0.0, tdroop=0.0, tsweep=0.0,
             tr=0.02, tflat=1.0, wing=0.0, taper=0.35):
    """One vertebra.

    L length, rb centrum radius, hs/sl/lean spinous process height, length and backward lean,
    tl/tdroop/tsweep/tr transverse process length, droop, sweep along the spine and radius,
    tflat > 1 flattens the transverse process, wing > 0 adds the flat wings of an atlas.
    """
    q = spine_local(P, c, ang)
    u, v, w = q[:, 0], q[:, 1], q[:, 2]
    hl = 0.46 * L
    rnd = rb * 0.15
    dr = np.sqrt(v * v + w * w) - rb * 0.85
    du = np.abs(u) - hl + rnd
    d = np.minimum(np.maximum(dr, du), 0.0) + np.sqrt(np.maximum(dr, 0.0) ** 2 + np.maximum(du, 0.0) ** 2) - rnd
    d = smin(d, ell(q, (0.0, rb * 1.2, 0.0), (hl * 0.85, rb * 0.8, rb * 0.85)), 0.015)
    if hs > 0:
        base = rb * 1.7
        t = np.clip((v - base) / hs, 0.0, 1.0)
        us = (u - lean * (v - base)) / (1.0 - taper * t)
        b = box(np.stack([us, v - base - hs * 0.5, w], 1), (0, 0, 0), (sl * 0.5 - 0.006, hs * 0.5 - 0.006, 0.003), r=0.011)
        d = smin(d, b, 0.02)
    if tl > 0:
        qt = q.copy()
        qt[:, 1] = (qt[:, 1] - rb * 0.9) * tflat
        t = cone(qt, (0.0, 0.0, rb * 0.5), (tsweep, -tdroop * tflat, rb * 0.6 + tl), tr * 1.25, tr)
        d = smin(d, t, 0.02)
    if wing > 0:
        d = smin(d, ell(q, (0.0, rb * 0.5, rb + wing * 0.5), (hl * 1.1, 0.02, wing * 0.75)), 0.02)
    z = length(np.stack([np.abs(u) - hl * 0.8, v - rb * 1.75, w - rb * 0.55], 1)) - 0.02
    d = smin(d, z, 0.012)
    canal = np.sqrt((v - rb * 1.15) ** 2 + w * w) - rb * 0.42
    return smax(d, -canal, 0.006)


def long_bone(P, a, b, r, ra, rb, k=0.04):
    """Shaft from a to b with thickened ends."""
    d = capsule(P, a, b, r)
    d = smin(d, sphere(P, a, ra), k)
    return smin(d, sphere(P, b, rb), k)


def rib_path(head, a, b, lean, th_end, n=16):
    """Centre line of a left rib: head at the vertebra, then an elliptical hoop.

    head (x, y) of the vertebra centre, a half width, b half depth of the hoop,
    lean backward slope per unit of depth, th_end where the rib stops (radians from the top).
    The middle of the rib bows slightly to the rear.
    """
    x0, y0 = head
    ytop = y0 + 0.02
    th0 = math.asin(min(0.95, 0.15 / a))
    pts = [V(x0, y0 - 0.015, 0.075), V(x0, y0 - 0.012, 0.115)]
    for i in range(n + 1):
        th = th0 + (th_end - th0) * i / n
        y = ytop - b * (1.0 - math.cos(th))
        bow = 0.06 * b * math.sin(math.pi * i / n)
        pts.append(V(x0 + lean * (ytop - y) + bow, y, a * math.sin(th)))
    return pts


def rib(P, pts, r=0.02):
    radii = [r * 1.15, r] + [r * (1.0 - 0.2 * i / (len(pts) - 3)) for i in range(len(pts) - 2)]
    radii[-1] = r * 0.95
    return tube(P, pts, radii)


def cloven_foot(P, F0, fwd, z0, floor, p1=0.155, p2=0.10, hoof=0.15, r=0.03, spread=0.034, dew=True):
    """Two-toed foot of an even-toed ungulate, as one part.

    F0 fetlock joint (x, y), fwd -1 if the toes point towards the head, z0 sideways position,
    floor ground height. Returns the distance for both main toes and the dewclaws.
    """
    ang = math.radians(56.0)
    d = None
    for s in (-1.0, 1.0):
        z = z0 + s * spread
        a = V(F0[0], F0[1] - 0.02, z)
        b = a + V(fwd * math.cos(ang) * p1, -math.sin(ang) * p1, s * 0.006)
        c = b + V(fwd * math.cos(ang) * p2, -math.sin(ang) * p2, s * 0.004)
        top = floor + 0.085
        c[1] = max(c[1], top)
        t = long_bone(P, a, b, r * 0.8, r, r * 0.92, 0.02)
        t = np.minimum(t, long_bone(P, b + V(fwd * 0.004, -0.004, 0), c, r * 0.78, r * 0.9, r * 0.9, 0.02))
        tip = V(c[0] + fwd * hoof, floor + 0.012, z + s * 0.004)
        h = cone(P, c + V(fwd * 0.01, -0.02, 0.0), tip, r * 1.05, 0.012)
        h = smin(h, ell(P, (c[0] + fwd * hoof * 0.35, floor + 0.04, z), (hoof * 0.5, 0.04, r * 0.95)), 0.02)
        h = np.maximum(h, floor - P[:, 1])
        t = np.minimum(t, h)
        if dew:
            da = a + V(-fwd * 0.04, -0.025, s * 0.012)
            t = np.minimum(t, cone(P, a + V(-fwd * 0.02, -0.01, s * 0.008), da, 0.016, 0.013))
            t = np.minimum(t, cone(P, da, da + V(-fwd * 0.008, -0.032, 0.0), 0.013, 0.007))
        d = t if d is None else smin(d, t, 0.008)
    return d
