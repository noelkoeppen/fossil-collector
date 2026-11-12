import os
HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.normpath(os.path.join(HERE, '..', '..', 'art', 'models3d', 'cave_bear'))
import base64, json, os, sys
import numpy as np
import trimesh
from skimage import measure
from playwright.sync_api import sync_playwright

OUT = os.path.join(ART, 'bones')
os.makedirs(OUT, exist_ok=True)

SK = ((-0.6, -0.32, -0.34), (0.6, 0.36, 0.34))
REG = {
    'skull': SK, 'jaw': ((-0.6, -0.32, -0.34), (0.45, 0.12, 0.34)),
    'neck': ((0.4, -0.25, -0.2), (1.45, 0.6, 0.2)),
    'chest': ((1.1, 0.1, -0.15), (2.8, 0.9, 0.15)),
    'loin': ((2.5, 0.1, -0.25), (3.4, 0.6, 0.25)),
    'ribs': ((1.1, -0.65, -0.02), (3.1, 0.5, 0.5)),
    'sternum': ((1.3, -0.6, -0.08), (2.5, 0.0, 0.08)),
    'sacrum': ((3.1, 0.1, -0.12), (3.75, 0.5, 0.12)),
    'hip': ((3.0, -0.25, -0.02), (4.0, 0.55, 0.4)),
    'tail': ((3.5, -0.05, -0.06), (4.0, 0.35, 0.06)),
    'fore': ((0.95, -1.7, 0.05), (1.9, 0.7, 0.55)),
    'hind': ((2.7, -1.7, 0.1), (3.8, 0.2, 0.55)),
}

B = []
def add(id, de, en, group, reg, kind, cell=-1, part=0, cut=0, pair=False, h=0.004):
    B.append(dict(id=id, de=de, en=en, group=group, reg=reg, kind=kind, cell=cell, part=part, cut=cut, pair=pair, h=h))

add('occipital', 'Hinterhauptbein', 'Occipital bone', 'skull', 'skull', 0, cell=0)
add('sphenoid', 'Keilbein', 'Sphenoid bone', 'skull', 'skull', 0, cell=5)
for k, i, de, en in [(1, 'premaxilla', 'Zwischenkieferbein', 'Premaxilla'), (2, 'nasal', 'Nasenbein', 'Nasal bone'),
                     (3, 'maxilla', 'Oberkieferbein', 'Maxilla'), (4, 'jugal', 'Jochbein', 'Zygomatic bone'),
                     (6, 'temporal', 'Schläfenbein', 'Temporal bone'), (7, 'frontal', 'Stirnbein', 'Frontal bone'),
                     (8, 'parietal', 'Scheitelbein', 'Parietal bone')]:
    add(i, de, en, 'skull', 'skull', 0, cell=k, cut=1, pair=True)
add('mandible', 'Unterkieferhälfte', 'Mandible half', 'skull', 'jaw', 1, cut=1, pair=True)
for i in range(7):
    add('cervical_%d' % (i + 1), '%d. Halswirbel' % (i + 1), 'Cervical vertebra %d' % (i + 1), 'cervical', 'neck', 2, cell=i)
for i in range(14):
    add('thoracic_%d' % (i + 1), '%d. Brustwirbel' % (i + 1), 'Thoracic vertebra %d' % (i + 1), 'thoracic', 'chest', 3, cell=i)
for i in range(6):
    add('lumbar_%d' % (i + 1), '%d. Lendenwirbel' % (i + 1), 'Lumbar vertebra %d' % (i + 1), 'lumbar', 'loin', 4, cell=i)
add('sacrum', 'Kreuzbein', 'Sacrum', 'pelvis', 'sacrum', 7, part=1)
add('hip', 'Hüftbein', 'Hip bone', 'pelvis', 'hip', 7, part=2, cut=1, pair=True, h=0.005)
for i in range(8):
    add('caudal_%d' % (i + 1), '%d. Schwanzwirbel' % (i + 1), 'Caudal vertebra %d' % (i + 1), 'tail', 'tail', 8, cell=i, h=0.0025)
for i in range(14):
    add('rib_%d' % (i + 1), '%d. Rippe' % (i + 1), 'Rib %d' % (i + 1), 'ribs', 'ribs', 5, cell=i, cut=1, pair=True, h=0.0045)
add('sternum', 'Brustbein', 'Sternum', 'sternum', 'sternum', 6)
for p, i, de, en, h in [(1, 'scapula', 'Schulterblatt', 'Scapula', 0.0035), (2, 'humerus', 'Oberarmknochen', 'Humerus', 0.005),
                        (3, 'radius', 'Speiche', 'Radius', 0.0045), (4, 'ulna', 'Elle', 'Ulna', 0.0045),
                        (5, 'forepaw', 'Vorderpfote', 'Forepaw', 0.0035)]:
    add(i, de, en, 'forelimb', 'fore', 9, part=p, pair=True, h=h)
for p, i, de, en, h in [(1, 'femur', 'Oberschenkelknochen', 'Femur', 0.005), (2, 'patella', 'Kniescheibe', 'Patella', 0.003),
                        (3, 'tibia', 'Schienbein', 'Tibia', 0.005), (4, 'fibula', 'Wadenbein', 'Fibula', 0.004),
                        (5, 'hindpaw', 'Hinterpfote', 'Hindpaw', 0.0035)]:
    add(i, de, en, 'hindlimb', 'hind', 10, part=p, pair=True, h=h)

def sample(page, b, mn, mx, h):
    mn = np.array(mn, float); mx = np.array(mx, float)
    n = np.maximum(2, np.ceil((mx - mn) / h).astype(int))
    size = n * h
    s = page.evaluate('([k,c,p,cut,mn,size,n]) => window.sample(k,c,p,cut,mn,size,n)',
                      [b['kind'], b['cell'], b['part'], b['cut'], mn.tolist(), size.tolist(), n.tolist()])
    raw = np.frombuffer(base64.b64decode(s), dtype='<u2').reshape(n[2], n[1], n[0])
    d = (raw.astype(np.float32) / 65535.0 - 0.5) * 0.16
    return d, mn, n

def build(page, b):
    mn, mx = REG[b['reg']]
    hc = 0.02
    d, mn0, n = sample(page, b, mn, mx, hc)
    idx = np.argwhere(d < hc * 0.9)
    assert len(idx), 'empty ' + b['id']
    lo = idx.min(0)[::-1]; hi = idx.max(0)[::-1]
    h = b['h']
    bmin = mn0 + lo * hc - 3 * h; bmax = mn0 + (hi + 1) * hc + 3 * h
    if b['cut']: bmin[2] = max(bmin[2], -3 * h)
    d, mn1, n = sample(page, b, bmin, bmax, h)
    d = np.pad(d, 1, constant_values=0.08)
    v, f, _, _ = measure.marching_cubes(d, level=0.0, spacing=(h, h, h))
    v = v[:, ::-1] + (mn1 + 0.5 * h - h)
    m = trimesh.Trimesh(v, f, process=True)
    parts = [c for c in m.split(only_watertight=False) if len(c.faces) > 60]
    m = trimesh.util.concatenate(parts) if len(parts) > 1 else parts[0]
    if m.volume < 0: m.invert()
    target = int(min(7000, max(1200, len(m.faces) * 0.12)))
    if len(m.faces) > target:
        m = m.simplify_quadric_decimation(face_count=target)
    if m.volume < 0: m.invert()
    return m

def export(m, name):
    m = m.copy()
    m.visual = trimesh.visual.TextureVisuals(material=trimesh.visual.material.PBRMaterial(
        baseColorFactor=[0.82, 0.76, 0.62, 1.0], roughnessFactor=0.85, metallicFactor=0.0, name='bone'))
    _ = m.vertex_normals
    m.export(os.path.join(OUT, name + '.glb'), include_normals=True)

only = sys.argv[1:]
manifest = []
with sync_playwright() as pw:
    br = pw.chromium.launch(executable_path='/usr/bin/google-chrome', headless=True,
                            args=['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'])
    page = br.new_page()
    page.goto('file://' + os.path.join(HERE, 'extract.html'))
    page.wait_for_function('window.ready === true', timeout=300000)
    err = page.evaluate('window.err || ""')
    assert not err, err
    for b in B:
        if only and not any(b['id'].startswith(o) for o in only): continue
        m = build(page, b)
        variants = [('_l', 'left', m)] if b['pair'] else [('', 'center', m)]
        if b['pair']:
            r = m.copy(); r.vertices = r.vertices * [1, 1, -1]; r.invert()
            variants.append(('_r', 'right', r))
        for suf, side, mm in variants:
            name = b['id'] + suf
            export(mm, name)
            manifest.append(dict(id=name, file=name + '.glb', group=b['group'], side=side,
                                 name=dict(de=b['de'] + ({'left': ' links', 'right': ' rechts'}.get(side, '')),
                                           en=({'left': 'Left ', 'right': 'Right '}.get(side, '') + b['en'])),
                                 min=[round(float(x), 4) for x in mm.bounds[0]], max=[round(float(x), 4) for x in mm.bounds[1]],
                                 triangles=int(len(mm.faces))))
        print(b['id'], len(m.faces), np.round(m.bounds, 3).tolist(), flush=True)
    br.close()
if not only:
    json.dump(dict(species='cave_bear', unit='1 unit = skull length, about 0.45 m', axes='x to the rear, y up, z to the animal\'s left',
                   bones=manifest), open(os.path.join(ART, 'bones.json'), 'w'), ensure_ascii=False, indent=1)
print('done', len(manifest))
