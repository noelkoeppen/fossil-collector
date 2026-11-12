import os
HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.normpath(os.path.join(HERE, '..', '..', 'art', 'models3d', 'cave_bear'))
import re
sk = open(os.path.join(ART, 'skeleton.html')).read()
su = open(os.path.join(ART, 'skull.html')).read()
a = sk.index('precision highp float;'); b = sk.index('void groupParams(float k,')
glsl = sk[a:b]
a = su.index('// The nine bones as regions'); b = su.index('float qOf(float k) {')
bone_at = su[a:b]
glsl = glsl.replace('// lower jaw: two branches', bone_at + '// lower jaw: two branches', 1)
glsl = glsl.replace('precision highp float;', 'precision highp float;\nuniform float uCell;\nuniform float uPart;\nuniform float uKind;\nuniform float uCut;\nuniform vec3 uMin;\nuniform vec3 uSize;\nuniform float uZ;\nuniform vec2 uGrid;', 1)

def rep(x, y):
    global glsl
    assert glsl.count(x) == 1, (glsl.count(x), x)
    glsl = glsl.replace(x, y)

# single vertebrae and ribs
rep('float i = clamp(floor(f.x / s), 0.0, 6.0);', 'float i = uCell >= 0.0 ? uCell : clamp(floor(f.x / s), 0.0, 6.0);')
rep('float i = clamp(floor(f.x / s), 0.0, 13.0);', 'float i = uCell >= 0.0 ? uCell : clamp(floor(f.x / s), 0.0, 13.0);')
rep('float j = clamp(floor(us / s), 0.0, 13.0);', 'float j = uCell >= 0.0 ? uCell : clamp(floor(us / s), 0.0, 13.0);')
rep('float i = clamp(floor(f.x / s), 0.0, 5.0);', 'float i = uCell >= 0.0 ? uCell : clamp(floor(f.x / s), 0.0, 5.0);')
rep('float j = clamp(floor(us / s), 0.0, 5.0);', 'float j = uCell >= 0.0 ? uCell : clamp(floor(us / s), 0.0, 5.0);')
rep('  float i = clamp(floor(ur / s), 0.0, 13.0);', '  if (uCell >= 0.0) return rib(uCell, ur, f.y, f.z, s);\n  float i = clamp(floor(ur / s), 0.0, 13.0);')
# tail beads
rep('  return length(pa - ba * h) - mix(0.03, 0.01, h) - 0.006 * cos(h * 50.3) * (1.0 - 0.5 * h);',
    '  float dt = length(pa - ba * h) - mix(0.03, 0.01, h) - 0.006 * cos(h * 50.3) * (1.0 - 0.5 * h);\n'
    '  if (uCell >= 0.0) { float tt = dot(pa, ba) / dot(ba, ba); dt = max(dt, (abs(tt - (uCell + 0.5) / 8.0) - 0.0625) * length(ba)); }\n'
    '  return dt;')
# pelvis: 1 sacrum, 2 hip bone
rep('  vec3 l = q - vec3(3.28, 0.31, 0.17);', '  if (uPart > 0.5 && uPart < 1.5) return d;\n  if (uPart > 1.5) d = 1000.0;\n  vec3 l = q - vec3(3.28, 0.31, 0.17);')
# foreleg: 1 scapula 2 humerus 3 radius 4 ulna 5 paw
rep('  vec3 E = vec3(1.61, -0.88, 0.33);', '  if (uPart > 0.5 && uPart < 1.5) return d;\n  vec3 E = vec3(1.61, -0.88, 0.33);')
rep('  d = min(d, hu);', '  if (uPart > 1.5 && uPart < 2.5) return hu;\n  d = min(d, hu);')
m = re.search(r'  d = min\(d, (longBone\(q, E \+ vec3\(-0\.04.*\))\);\n', glsl); assert m
glsl = glsl.replace(m.group(0), '  float rad = %s;\n  if (uPart > 2.5 && uPart < 3.5) return rad;\n  d = min(d, rad);\n' % m.group(1))
m = re.search(r'  d = min\(d, (longBone\(q, vec3\(1\.71.*\))\);\n', glsl); assert m
glsl = glsl.replace(m.group(0), '  float uln = %s;\n  if (uPart > 3.5 && uPart < 4.5) return uln;\n  d = min(d, uln);\n' % m.group(1))
rep('  return min(d, pw);', '  if (uPart > 4.5) return pw;\n  return min(d, pw);')
# hindleg: 1 femur 2 patella 3 tibia 4 fibula 5 foot
m = re.search(r'  d = min\(d, (sdEll\(q - \(K \+ vec3\(-0\.09.*\))\);\n', glsl); assert m
glsl = glsl.replace(m.group(0), '  if (uPart > 0.5 && uPart < 1.5) return d;\n  float pat = %s;\n  if (uPart > 1.5 && uPart < 2.5) return pat;\n  d = min(d, pat);\n' % m.group(1))
m = re.search(r'  d = min\(d, (longBone\(q, K \+ vec3\(0\.01.*\))\);\n', glsl); assert m
glsl = glsl.replace(m.group(0), '  float tib = %s;\n  if (uPart > 2.5 && uPart < 3.5) return tib;\n  d = min(d, tib);\n' % m.group(1))
m = re.search(r'  d = min\(d, (sdCap\(q, K \+ vec3\(0\.05.*\))\);\n', glsl); assert m
glsl = glsl.replace(m.group(0), '  float fib = %s;\n  if (uPart > 3.5 && uPart < 4.5) return fib;\n  d = min(d, fib);\n' % m.group(1))
rep('  return min(d, ft);', '  if (uPart > 4.5) return ft;\n  return min(d, ft);')

glsl += '''
void main() {
  vec3 p = uMin + uSize * vec3(gl_FragCoord.xy / uGrid, uZ);
  float d;
  if (uKind < 0.5) d = max(shape(p).x, boneAt(p, uCell).y);
  else if (uKind < 1.5) d = jaw(p).x;
  else if (uKind < 2.5) d = neck(p);
  else if (uKind < 3.5) d = chestSpine(p);
  else if (uKind < 4.5) d = loin(p);
  else if (uKind < 5.5) d = ribs(p);
  else if (uKind < 6.5) d = breastbone(p);
  else if (uKind < 7.5) d = pelvis(p);
  else if (uKind < 8.5) d = tail(p);
  else if (uKind < 9.5) d = foreleg(p);
  else d = hindleg(p);
  if (uCut > 0.5) d = max(d, -p.z);
  float e = clamp(d / 0.08 * 0.5 + 0.5, 0.0, 1.0) * 65535.0;
  float hi = floor(e / 256.0);
  gl_FragColor = vec4(hi / 255.0, (e - hi * 256.0) / 255.0, 0.0, 1.0);
}
'''
html = '''<!DOCTYPE html><html><body><canvas id="c"></canvas><script id="fs" type="x-shader/x-fragment">
''' + glsl + '''</script><script>
var canvas = document.getElementById("c");
var gl = canvas.getContext("webgl", { antialias: false, alpha: false, preserveDrawingBuffer: true });
function sh(t, src) { var s = gl.createShader(t); gl.shaderSource(s, src); gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) { window.err = gl.getShaderInfoLog(s); } return s; }
var prog = gl.createProgram();
gl.attachShader(prog, sh(gl.VERTEX_SHADER, "attribute vec2 a; void main(){ gl_Position = vec4(a, 0.0, 1.0); }"));
gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, document.getElementById("fs").textContent));
gl.linkProgram(prog);
if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) window.err = (window.err || "") + gl.getProgramInfoLog(prog);
gl.useProgram(prog);
gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
var loc = gl.getAttribLocation(prog, "a"); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
function U(n) { return gl.getUniformLocation(prog, n); }
gl.uniform1f(U("uEx"), 0);
// returns base64 of uint16 little-endian volume, order z, y, x
window.sample = function (kind, cell, part, cut, mn, size, n) {
  canvas.width = n[0]; canvas.height = n[1]; gl.viewport(0, 0, n[0], n[1]);
  gl.uniform1f(U("uKind"), kind); gl.uniform1f(U("uCell"), cell); gl.uniform1f(U("uPart"), part); gl.uniform1f(U("uCut"), cut);
  gl.uniform3fv(U("uMin"), mn); gl.uniform3fv(U("uSize"), size); gl.uniform2f(U("uGrid"), n[0], n[1]);
  var out = new Uint8Array(n[0] * n[1] * n[2] * 2), px = new Uint8Array(n[0] * n[1] * 4), o = 0;
  for (var k = 0; k < n[2]; k++) {
    gl.uniform1f(U("uZ"), (k + 0.5) / n[2]);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.readPixels(0, 0, n[0], n[1], gl.RGBA, gl.UNSIGNED_BYTE, px);
    for (var i = 0; i < n[0] * n[1]; i++) { out[o++] = px[i * 4 + 1]; out[o++] = px[i * 4]; }
  }
  var s = "", CH = 0x8000;
  for (var i = 0; i < out.length; i += CH) s += String.fromCharCode.apply(null, out.subarray(i, i + CH));
  return btoa(s);
};
window.ready = true;
</script></body></html>'''
open(os.path.join(HERE, 'extract.html'), 'w').write(html)
print('ok', len(glsl))
