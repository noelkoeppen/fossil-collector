"""Signed distance building blocks for the bone models (numpy, vectorised).

Every function takes points P with shape (N, 3) and returns N distances:
negative inside, positive outside. See art/models3d/MODELLING_RULES.md, chapter 4.
"""
import numpy as np

F = np.float32


def V(*a):
    return np.array(a, dtype=F)


def length(v):
    return np.sqrt((v * v).sum(-1))


def unit(v):
    v = np.asarray(v, dtype=F)
    return v / np.sqrt((v * v).sum())


def frame(a, hint=(0.0, 1.0, 0.0)):
    """Rotation whose rows are the local axes: a, then b near hint, then c = a x b."""
    a = unit(a)
    h = np.asarray(hint, dtype=F)
    b = unit(h - a * float(h @ a))
    c = np.cross(a, b).astype(F)
    return np.stack([a, b, c])


def to_local(P, c, R=None):
    q = P - np.asarray(c, dtype=F)
    return q if R is None else q @ R.T


# ---------- exact primitives ----------
def sphere(P, c, r):
    return length(P - np.asarray(c, dtype=F)) - r


def cone(P, a, b, r1, r2):
    """Capsule whose radius runs from r1 at a to r2 at b."""
    a = np.asarray(a, dtype=F)
    ba = np.asarray(b, dtype=F) - a
    pa = P - a
    h = np.clip((pa @ ba) / float(ba @ ba), 0.0, 1.0)
    return length(pa - h[:, None] * ba) - (r1 + (r2 - r1) * h)


def capsule(P, a, b, r):
    return cone(P, a, b, r, r)


def box(P, c, half, r=0.0, R=None):
    q = np.abs(to_local(P, c, R)) - np.asarray(half, dtype=F)
    return length(np.maximum(q, 0.0)) + np.minimum(q.max(-1), 0.0) - r


def cylinder(P, c, axis, half, r, rnd=0.0):
    """Rounded cylinder around c along the unit vector axis."""
    q = P - np.asarray(c, dtype=F)
    ax = unit(axis)
    u = q @ ax
    dr = length(q - u[:, None] * ax) - (r - rnd)
    du = np.abs(u) - (half - rnd)
    return np.minimum(np.maximum(dr, du), 0.0) + np.sqrt(np.maximum(dr, 0.0) ** 2 + np.maximum(du, 0.0) ** 2) - rnd


def tri_plate(P, a, b, c, th):
    """Triangle a, b, c thickened to a plate of half thickness th."""
    a, b, c = (np.asarray(x, dtype=F) for x in (a, b, c))
    ba, cb, ac = b - a, c - b, a - c
    pa, pb, pc = P - a, P - b, P - c
    nor = np.cross(ba, ac).astype(F)

    def edge(e, p):
        t = np.clip((p @ e) / float(e @ e), 0.0, 1.0)
        v = t[:, None] * e - p
        return (v * v).sum(-1)

    inside = (np.sign(pa @ np.cross(ba, nor)) + np.sign(pb @ np.cross(cb, nor)) + np.sign(pc @ np.cross(ac, nor))) >= 2.0
    d2 = np.where(inside, (pa @ nor) ** 2 / float(nor @ nor), np.minimum(np.minimum(edge(ba, pa), edge(cb, pb)), edge(ac, pc)))
    return (np.sqrt(d2) - th).astype(F)


# ---------- bound primitives ----------
def ell(P, c, r, R=None):
    """Ellipsoid (lower bound of the distance)."""
    q = to_local(P, c, R)
    r = np.asarray(r, dtype=F)
    k0 = length(q / r)
    k1 = length(q / (r * r))
    d = k0 * (k0 - 1.0) / np.maximum(k1, 1e-9)
    return np.where(k1 < 1e-6, -float(r.min()), d).astype(F)


def flat_cone(P, a, b, r1, r2, s1, s2, axis=2):
    """Cone from a to b that is flattened along one local axis (bound).

    The cross-section is an ellipse: radius r in the plane, r / s along the axis.
    s runs from s1 at a to s2 at b, so a tine can leave a plate flat and end round.
    """
    a = np.asarray(a, dtype=F)
    ba = np.asarray(b, dtype=F) - a
    pa = P - a
    h = np.clip((pa @ ba) / float(ba @ ba), 0.0, 1.0)
    perp = pa - h[:, None] * ba
    s = s1 + (s2 - s1) * h
    n = perp[:, axis] * s
    inpl = np.delete(perp, axis, axis=1)
    return (np.sqrt((inpl * inpl).sum(-1) + n * n) - (r1 + (r2 - r1) * h)) / max(s1, s2, 1.0)


# ---------- operators ----------
def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0.0, 1.0)
    return b + (a - b) * h - k * h * (1.0 - h)


def smax(a, b, k):
    return -smin(-a, -b, k)


def union(*ds):
    d = ds[0]
    for x in ds[1:]:
        d = np.minimum(d, x)
    return d


def tube(P, pts, radii, k=0.0):
    """Chain of cones through pts. k > 0 blends the joints."""
    d = None
    for i in range(len(pts) - 1):
        c = cone(P, pts[i], pts[i + 1], radii[i], radii[i + 1])
        d = c if d is None else (smin(d, c, k) if k > 0 else np.minimum(d, c))
    return d


def fold_z(P):
    Q = P.copy()
    Q[:, 2] = np.abs(Q[:, 2])
    return Q


def scaled_y(P, s, y0=0.0):
    """Squash space in y around y0 so shapes come out 1/s times taller (bound)."""
    Q = P.copy()
    Q[:, 1] = (Q[:, 1] - y0) * s + y0
    return Q
