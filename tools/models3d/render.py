"""Render orthographic preview images of a species from its bone meshes.

Usage: python3 tools/models3d/render.py <speciesId> [--tint] [--group <group>]
Writes art/models3d/<speciesId>/preview/*.png. Needs numpy, trimesh and Pillow.
"""
import json
import math
import os
import sys

import numpy as np
import trimesh
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
GROUP_TINT = {'skull': (0.90, 0.80, 0.55), 'cervical': (0.60, 0.80, 0.90), 'thoracic': (0.55, 0.70, 0.95),
              'lumbar': (0.70, 0.60, 0.95), 'pelvis': (0.95, 0.60, 0.70), 'tail': (0.95, 0.70, 0.50),
              'ribs': (0.70, 0.90, 0.65), 'sternum': (0.50, 0.85, 0.75), 'forelimb': (0.95, 0.85, 0.50),
              'hindlimb': (0.95, 0.65, 0.45)}
BONE = (0.85, 0.79, 0.64)
BG = (0.07, 0.06, 0.05)


def load(species, tint, group=None):
    art = os.path.normpath(os.path.join(HERE, '..', '..', 'art', 'models3d', species))
    man = json.load(open(os.path.join(art, 'bones.json')))
    P, N, C = [], [], []
    rng = np.random.default_rng(1)
    for k, e in enumerate(man['bones']):
        if group and e['group'] != group:
            continue
        m = trimesh.load(os.path.join(art, 'bones', e['file']), force='mesh')
        n = int(max(2000, m.area * (2600000 if group else 260000)))
        tri = m.triangles
        w = m.area_faces / m.area_faces.sum()
        idx = rng.choice(len(tri), n, p=w)
        r1 = np.sqrt(rng.random(n))[:, None]
        r2 = rng.random(n)[:, None]
        t = tri[idx]
        P.append((1 - r1) * t[:, 0] + r1 * (1 - r2) * t[:, 1] + r1 * r2 * t[:, 2])
        N.append(m.face_normals[idx])
        col = np.array(GROUP_TINT[e['group']] if tint else BONE)
        if tint:
            col = col * (0.82 if k % 2 else 1.0)
        C.append(np.tile(col, (n, 1)))
    return art, np.concatenate(P), np.concatenate(N), np.concatenate(C)


def view(P, N, C, yaw, pitch, path, ppu=230, pad=0.15):
    cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
    right = np.array([cy, 0.0, -sy])
    fwd = np.array([-cp * sy, -sp, -cp * cy])
    up = np.cross(right, fwd)
    up = -up if up[1] < 0 else up
    X, Y, Z = P @ right, P @ up, P @ fwd
    x0, y1 = X.min() - pad, Y.max() + pad
    W = int((X.max() + pad - x0) * ppu)
    H = int((y1 - Y.min() + pad) * ppu)
    px = np.clip(((X - x0) * ppu).astype(int), 0, W - 1)
    py = np.clip(((y1 - Y) * ppu).astype(int), 0, H - 1)
    key = np.array([-0.45, 0.75, 0.0]) @ np.stack([right, up, fwd]) - 0.55 * fwd
    key = key / np.linalg.norm(key)
    lam = np.clip(N @ key, 0.0, 1.0) * 0.75 + np.clip(-(N @ fwd), 0.0, 1.0) * 0.25 + 0.18
    depth = (Z - Z.min()) / (Z.max() - Z.min() + 1e-9)
    col = C * (lam * (1.0 - 0.35 * depth))[:, None]
    order = np.argsort(-Z)
    img = np.empty((H, W, 3))
    img[:] = BG
    img[py[order], px[order]] = col[order]
    Image.fromarray((np.clip(img, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8)).save(path)


def main():
    species = sys.argv[1]
    tint = '--tint' in sys.argv
    group = sys.argv[sys.argv.index('--group') + 1] if '--group' in sys.argv else None
    art, P, N, C = load(species, tint, group)
    out = os.path.join(art, 'preview')
    os.makedirs(out, exist_ok=True)
    suf = '_tint' if tint else ''
    if group:
        for name, yaw, pitch in [('side', 0.0, 0.0), ('quarter', -0.85, 0.22)]:
            view(P, N, C, yaw, pitch, os.path.join(out, '%s_%s%s.png' % (group, name, suf)), ppu=420 if group != 'skull' else 300)
        print('wrote', out)
        return
    for name, yaw, pitch in [('side', 0.0, 0.0), ('front', -math.pi / 2, 0.0), ('top', 0.0, math.pi / 2 - 1e-3),
                             ('quarter', -0.85, 0.22), ('rear_quarter', 2.3, 0.25)]:
        view(P, N, C, yaw, pitch, os.path.join(out, name + suf + '.png'))
    print('wrote', out)


if __name__ == '__main__':
    main()
