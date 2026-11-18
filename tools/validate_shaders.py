"""Validate Minecraft 26.3-style shaders with glslang: inlines #include <ns:path> from the given asset roots,
maps .fsh/.vsh to frag/vert. Usage: validate_shaders.py GLSLANG ASSET_ROOT[,ASSET_ROOT...] FILE... [-D...]"""
import os, re, subprocess, sys, tempfile
glslang, roots, rest = sys.argv[1], sys.argv[2].split(','), sys.argv[3:]
defines = [a for a in rest if a.startswith('-D')]
files = [a for a in rest if not a.startswith('-D')]
# 26.3 uses #include, 26.2 #moj_import; 26.2 includes start with their own #version line, which is dropped
INC = re.compile(r'^\s*#(?:include|moj_import)\s+<([a-z0-9_.-]+):([a-z0-9_./-]+)>\s*$')
def resolve(ns, path):
    for r in roots:
        p = os.path.join(r, ns, 'shaders', 'include', path)
        if os.path.exists(p): return p
    raise SystemExit(f'include not found: {ns}:{path}')
def inline(path, seen, top=True):
    out = []
    for line in open(path):
        if not top and line.lstrip().startswith('#version'):
            continue
        m = INC.match(line)
        if m:
            p = resolve(*m.groups())
            # always inline: the includes carry #ifndef guards, and a first copy may sit in a disabled #ifdef
            out.append(inline(p, seen, False))
        else: out.append(line)
    return ''.join(out)
bad = 0
for f in files:
    stage = 'frag' if f.endswith('.fsh') else 'vert'
    src = inline(f, set())
    with tempfile.NamedTemporaryFile('w', suffix='.' + stage, delete=False) as t:
        t.write(src)
    r = subprocess.run([glslang, '-V', '-R', '--amb', '--aml', '--target-env', 'vulkan1.0', '-o', os.devnull, *defines, t.name],
                       capture_output=True, text=True)
    ok = r.returncode == 0
    bad += not ok
    print(('OK   ' if ok else 'FAIL ') + f + ' ' + ' '.join(defines))
    if not ok: print(r.stdout + r.stderr)
sys.exit(1 if bad else 0)
