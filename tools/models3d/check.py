"""Check a species against art/models3d/MODELLING_RULES.md.

Usage: python3 tools/models3d/check.py <speciesId>
Exit code 1 if any rule fails. Needs numpy, scipy and trimesh.
"""
import importlib
import json
import os
import re
import sys

import numpy as np
import trimesh
from scipy.spatial import ConvexHull

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GROUPS = {'skull', 'cervical', 'thoracic', 'lumbar', 'pelvis', 'tail', 'ribs', 'sternum', 'forelimb', 'hindlimb'}
MAX_TRIS_BONE, MAX_TRIS_TOTAL, MAX_BYTES = 6000, 150000, 5 * 1024 * 1024
MEASURE_TOL, OVERLAP_TOL, FLOOR_TOL = 0.08, 0.02, 0.01
OVERLAP_H = 0.008

errors, notes = [], []


def fail(rule, msg):
    errors.append('[%s] %s' % (rule, msg))


def greatest_length(m):
    p = m.vertices[ConvexHull(m.vertices).vertices]
    d = np.linalg.norm(p[:, None, :] - p[None, :, :], axis=-1)
    return float(d.max())


def overlap(a, b, mod_bones):
    """Shared volume of two bones, from their distance functions on a common grid."""
    lo = np.maximum(a['lo_m'], b['lo_m'])
    hi = np.minimum(a['hi_m'], b['hi_m'])
    if np.any(hi - lo <= 0):
        return 0.0
    n = np.maximum(2, np.ceil((hi - lo) / OVERLAP_H).astype(int))
    if n.prod() > 4_000_000:
        n = np.maximum(2, (n * (4_000_000 / n.prod()) ** (1 / 3)).astype(int))
    ax = [np.linspace(lo[i], hi[i], n[i], dtype=np.float32) for i in range(3)]
    P = np.stack(np.meshgrid(*ax, indexing='ij'), -1).reshape(-1, 3)
    cell = np.prod((hi - lo) / n)

    def inside(e):
        Q = P.copy()
        if e['side'] == 'right':
            Q[:, 2] = -Q[:, 2]
        d = mod_bones[e['base']]['fn'](Q)
        if mod_bones[e['base']]['cut']:
            d = np.maximum(d, -Q[:, 2])
        return d < 0

    return float((inside(a) & inside(b)).sum() * cell)


def main():
    species = sys.argv[1]
    mod = importlib.import_module('species.' + species)
    art = os.path.normpath(os.path.join(HERE, '..', '..', 'art', 'models3d', species))
    man = json.load(open(os.path.join(art, 'bones.json')))
    bones = man['bones']
    defs = {b['id']: b for b in mod.bones()}
    unit_m = man.get('unitMeters')
    if not unit_m:
        fail('manifest', 'bones.json has no unitMeters')
        unit_m = mod.UNIT_M

    # files, naming, groups, manifest
    on_disk = sorted(f for f in os.listdir(os.path.join(art, 'bones')) if f.endswith('.glb'))
    if on_disk != sorted(e['file'] for e in bones):
        fail('files', 'bones/ and bones.json list different files')
    expected = sorted(sum(([b['id'] + '_l', b['id'] + '_r'] if b['pair'] else [b['id']] for b in defs.values()), []))
    if expected != sorted(e['id'] for e in bones):
        fail('files', 'bones.json does not match the bone list of the species file')
    meshes = {}
    for e in bones:
        if not re.fullmatch(r'[a-z][a-z0-9_]*', e['id']) or e['file'] != e['id'] + '.glb':
            fail('naming', e['id'])
        if e['group'] not in GROUPS:
            fail('groups', '%s uses unknown group %s' % (e['id'], e['group']))
        if (e['side'] == 'left') != e['id'].endswith('_l') or (e['side'] == 'right') != e['id'].endswith('_r'):
            fail('naming', '%s: side and suffix disagree' % e['id'])
        for k in ('name', 'min', 'max', 'triangles'):
            if k not in e:
                fail('manifest', '%s lacks %s' % (e['id'], k))
        sc = trimesh.load(os.path.join(art, 'bones', e['file']))
        for node in sc.graph.nodes_geometry:
            if not np.allclose(sc.graph[node][0], np.eye(4), atol=1e-9):
                fail('node_transform', e['id'])
        m = trimesh.load(os.path.join(art, 'bones', e['file']), force='mesh')
        meshes[e['id']] = m
        e['base'] = e['id'][:-2] if e['side'] != 'center' else e['id']
        e['lo_m'], e['hi_m'] = m.bounds[0], m.bounds[1]
        if not m.is_watertight:
            fail('watertight', e['id'])
        if m.volume <= 0 or not m.is_winding_consistent:
            fail('winding', e['id'])
        if len(m.split(only_watertight=False)) != 1:
            fail('single_body', e['id'])
        if (m.area_faces < 1e-12).any():
            fail('degenerate', e['id'])
        if len(m.faces) != e['triangles'] or not np.allclose(m.bounds, [e['min'], e['max']], atol=2e-4):
            fail('manifest', '%s: triangles or bounds differ from the mesh' % e['id'])
        if len(m.faces) > MAX_TRIS_BONE:
            fail('budget', '%s has %d triangles' % (e['id'], len(m.faces)))
    total = sum(len(m.faces) for m in meshes.values())
    if total > MAX_TRIS_TOTAL:
        fail('budget', 'species has %d triangles, limit %d' % (total, MAX_TRIS_TOTAL))
    notes.append('%d bones, %d triangles' % (len(bones), total))

    # mirror
    for e in bones:
        if e['side'] == 'left':
            l, r = meshes[e['id']], meshes[e['base'] + '_r']
            if len(l.vertices) != len(r.vertices) or not np.allclose(l.vertices * [1, 1, -1], r.vertices, atol=1e-6):
                fail('mirror', e['base'])

    # combined file
    allp = os.path.join(art, species + '_all.glb')
    if not os.path.exists(allp):
        fail('files', species + '_all.glb missing')
    else:
        size = os.path.getsize(allp)
        notes.append('%s_all.glb %.2f MB' % (species, size / 1048576))
        if size > MAX_BYTES:
            fail('size', '%s_all.glb is %.1f MB' % (species, size / 1048576))
        sc = trimesh.load(allp)
        if sorted(sc.graph.nodes_geometry) != sorted(e['id'] for e in bones):
            fail('files', 'node names in %s_all.glb do not match the bone ids' % species)
        for node in sc.graph.nodes_geometry:
            if not np.allclose(sc.graph[node][0], np.eye(4), atol=1e-9):
                fail('node_transform', '%s in %s_all.glb' % (node, species))

    # floor: all four feet stand on the same height, nothing below
    floor = min(m.bounds[0][1] for m in meshes.values())
    feet = [e['id'] for e in bones if e['base'] in getattr(mod, 'FEET', ('forefoot', 'hindfoot', 'forepaw', 'hindpaw'))]
    for f in feet:
        if abs(meshes[f].bounds[0][1] - floor) > FLOOR_TOL:
            fail('floor', '%s ends %.3f above the floor' % (f, meshes[f].bounds[0][1] - floor))
    if len(feet) != 4:
        fail('floor', 'expected 4 feet, found %d' % len(feet))

    # measures
    meas = dict(mod.MEASURES_MM)
    rows = []
    for b in defs.values():
        if b.get('measure'):
            got = greatest_length(meshes[b['id'] + ('_l' if b['pair'] else '')]) * unit_m * 1000
            rows.append((b['measure'], meas.pop(b['measure']), got))
    for key, fn in getattr(mod, 'MEASURE_CHECKS', {}).items():
        rows.append((key, meas.pop(key), fn(meshes, floor) * unit_m * 1000))
    for key, want, got in rows:
        dev = got / want - 1.0
        notes.append('%-16s target %5d mm, model %5d mm (%+.1f %%)' % (key, want, got, dev * 100))
        if abs(dev) > MEASURE_TOL:
            fail('measures', '%s is off by %+.1f %%' % (key, dev * 100))
    for key in meas:
        fail('measures', '%s is listed in MEASURES_MM but never checked' % key)

    # overlap between neighbours
    worst = []
    for i, a in enumerate(bones):
        for b in bones[i + 1:]:
            if np.any(np.minimum(a['hi_m'], b['hi_m']) - np.maximum(a['lo_m'], b['lo_m']) <= 0):
                continue
            v = overlap(a, b, defs)
            if v <= 0:
                continue
            share = v / min(meshes[a['id']].volume, meshes[b['id']].volume)
            worst.append((share, a['id'], b['id']))
            if share > OVERLAP_TOL:
                fail('overlap', '%s and %s share %.1f %% of the smaller bone' % (a['id'], b['id'], share * 100))
    worst.sort(reverse=True)
    notes.append('largest overlaps: ' + (', '.join('%s/%s %.1f %%' % (a, b, s * 100) for s, a, b in worst[:5]) or 'none'))

    for n in notes:
        print(n)
    for e in errors:
        print('FAIL', e)
    print('%d errors' % len(errors))
    sys.exit(1 if errors else 0)


if __name__ == '__main__':
    main()
