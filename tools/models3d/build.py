"""Build the bone meshes of one species from its distance functions.

Usage: python3 tools/models3d/build.py <speciesId> [bone id prefix ...]
Writes art/models3d/<speciesId>/bones/*.glb, bones.json and <speciesId>_all.glb.
Needs numpy, scikit-image, trimesh and fast-simplification.
"""
import importlib
import json
import os
import sys

import fast_simplification
import numpy as np
import trimesh
from skimage import measure

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
CHUNK = 1_500_000
BONE_COLOR = [0.82, 0.76, 0.62, 1.0]
CRUMB_SHARE = 0.01  # loose pieces below this share of the bone's volume are dropped


def art_dir(species):
    return os.path.normpath(os.path.join(HERE, '..', '..', 'art', 'models3d', species))


def sample(fn, lo, hi, h, cut):
    lo = np.array(lo, dtype=float)
    hi = np.array(hi, dtype=float)
    if cut:
        lo[2] = max(lo[2], -3 * h)
    # shift the grid off round numbers so cut planes never fall exactly on grid nodes
    lo = lo - 0.317 * h
    n = np.maximum(4, np.ceil((hi - lo) / h).astype(int) + 1)
    ax = [(lo[i] + h * np.arange(n[i])).astype(np.float32) for i in range(3)]
    vol = np.empty(n, dtype=np.float32)
    per = max(1, CHUNK // int(n[1] * n[2]))
    yy, zz = np.meshgrid(ax[1], ax[2], indexing='ij')
    for i0 in range(0, n[0], per):
        xs = ax[0][i0:i0 + per]
        P = np.empty((len(xs), n[1], n[2], 3), dtype=np.float32)
        P[..., 0] = xs[:, None, None]
        P[..., 1] = yy
        P[..., 2] = zz
        d = fn(P.reshape(-1, 3))
        if cut:
            d = np.maximum(d, -P.reshape(-1, 3)[:, 2])
        vol[i0:i0 + per] = d.reshape(len(xs), n[1], n[2])
    # keep the surface off the grid nodes, otherwise marching cubes emits zero-area slivers
    eps = 2e-3 * h
    near = np.abs(vol) < eps
    vol[near] = np.where(vol[near] < 0, -eps, eps)
    return vol, lo


def mesh_bone(b):
    vol, lo = sample(b['fn'], b['lo'], b['hi'], b['h'], b['cut'])
    edge = min(vol[0].min(), vol[-1].min(), vol[:, 0].min(), vol[:, -1].min(), vol[:, :, 0].min(), vol[:, :, -1].min())
    if edge <= 0:
        raise SystemExit('%s: shape touches its sampling box, enlarge the box' % b['id'])
    v, f, _, _ = measure.marching_cubes(vol, level=0.0, spacing=(b['h'],) * 3)
    m = trimesh.Trimesh(v + lo, f, process=True)
    parts = sorted(m.split(only_watertight=False), key=lambda c: -len(c.faces))
    keep = [c for c in parts if len(c.faces) > 60 and c.volume > 0]
    # crumbs left over where a region boundary grazes the surface are not bones
    biggest = max((c.volume for c in keep), default=0.0)
    keep = [c for c in keep if c.volume >= CRUMB_SHARE * biggest]
    if not keep:
        raise SystemExit('%s: empty' % b['id'])
    raw_parts = len(keep)
    m = trimesh.util.concatenate(keep) if len(keep) > 1 else keep[0]
    if m.volume < 0:
        m.invert()
    target = int(min(b['tris'], len(m.faces)))
    if len(m.faces) > target:
        m = decimate(m, target, b['id'])
    if m.volume < 0:
        m.invert()
    return m, raw_parts


def good(s):
    """Closed, one body, no zero-area triangles."""
    return s.is_watertight and (s.area_faces > 1e-12).all() and len(s.split(only_watertight=False)) == 1


FOLD_ANGLE = 2.8  # radians between neighbouring face normals, about 160 degrees


def folds(s):
    """Number of edges where the surface folds back on itself."""
    return int((s.face_adjacency_angles > FOLD_ANGLE).sum())


def decimate(m, target, name):
    """Quadric decimation that only accepts closed, manifold results within the budget.

    Gentle settings come first: a high aggressiveness reaches the target just as well but
    leaves facets and folded triangles on thin plates. Results without folds are preferred.
    """
    fallback = None
    for agg in (3, 4, 5, 7):
        for mult in (1.0, 0.94, 0.88, 0.8):
            v, f = fast_simplification.simplify(m.vertices, m.faces, target_count=int(target * mult), agg=agg)
            s = trimesh.Trimesh(v, f, process=True)
            if good(s) and len(s.faces) <= target:
                if folds(s) == 0:
                    return s
                fallback = fallback or s
    if fallback is not None:
        return fallback
    # last resort: halve step by step, which avoids most collapses that pinch thin parts
    for agg in (5, 3, 2):
        s, ok = m, True
        while ok and len(s.faces) > target:
            t = max(int(target * 0.97), int(len(s.faces) * 0.6))
            v, f = fast_simplification.simplify(s.vertices, s.faces, target_count=t, agg=agg)
            n = trimesh.Trimesh(v, f, process=True)
            ok = n.is_watertight and len(n.faces) < len(s.faces)
            s = n
        if ok and good(s):
            return s
    raise SystemExit('%s: no closed mesh within %d triangles' % (name, target))


def export(m, path):
    m = m.copy()
    m.visual = trimesh.visual.TextureVisuals(material=trimesh.visual.material.PBRMaterial(
        baseColorFactor=BONE_COLOR, roughnessFactor=0.85, metallicFactor=0.0, name='bone'))
    _ = m.vertex_normals
    m.export(path, include_normals=True)


def main():
    species = sys.argv[1]
    only = sys.argv[2:]
    mod = importlib.import_module('species.' + species)
    art = art_dir(species)
    out = os.path.join(art, 'bones')
    os.makedirs(out, exist_ok=True)
    manifest = []
    for b in mod.bones():
        ids = [(b['id'] + '_l', 'left'), (b['id'] + '_r', 'right')] if b['pair'] else [(b['id'], 'center')]
        if only and not any(b['id'].startswith(o) for o in only):
            meshes = [trimesh.load(os.path.join(out, i + '.glb'), force='mesh') for i, _ in ids]
        else:
            m, nparts = mesh_bone(b)
            meshes = [m]
            if b['pair']:
                r = m.copy()
                r.vertices = r.vertices * [1, 1, -1]
                r.invert()
                meshes.append(r)
            for (i, _), mm in zip(ids, meshes):
                export(mm, os.path.join(out, i + '.glb'))
            print('%-14s %5d tris  parts %d  %s' % (b['id'], len(m.faces), nparts, np.round(m.bounds, 3).tolist()), flush=True)
        for (i, side), mm in zip(ids, meshes):
            manifest.append(dict(
                id=i, file=i + '.glb', group=b['group'], side=side,
                name=dict(de=b['de'] + {'left': ' links', 'right': ' rechts'}.get(side, ''),
                          en={'left': 'Left ', 'right': 'Right '}.get(side, '') + b['en']),
                min=[round(float(x), 4) for x in mm.bounds[0]], max=[round(float(x), 4) for x in mm.bounds[1]],
                triangles=int(len(mm.faces))))
    json.dump(dict(species=species, unit='1 unit = skull length, about %.2f m' % mod.UNIT_M, unitMeters=mod.UNIT_M,
                   axes="x to the rear, y up, z to the animal's left", bones=manifest),
              open(os.path.join(art, 'bones.json'), 'w'), ensure_ascii=False, indent=1)
    scene = trimesh.Scene()
    for e in manifest:
        g = trimesh.load(os.path.join(out, e['file']), force='mesh')
        g.visual = trimesh.visual.TextureVisuals(material=trimesh.visual.material.PBRMaterial(
            baseColorFactor=BONE_COLOR, roughnessFactor=0.85, metallicFactor=0.0, name='bone'))
        scene.add_geometry(g, node_name=e['id'], geom_name=e['id'])
    scene.export(os.path.join(art, species + '_all.glb'))
    print('done', len(manifest), 'bones', sum(e['triangles'] for e in manifest), 'triangles')


if __name__ == '__main__':
    main()
