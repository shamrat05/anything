#!/usr/bin/env python3
"""Create the feature graphic from a current app screenshot."""
from pathlib import Path
import base64
import subprocess
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

root = Path(__file__).resolve().parents[2]
font = TTFont(root / 'app/src/main/res/font/aeonik_regular.ttf')
glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
scale = 76 / font['head'].unitsPerEm
cursor = 52.0
paths = []
for char in 'Anything':
    name = cmap[ord(char)]
    pen = SVGPathPen(glyphs)
    glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, cursor, 294)))
    paths.append('<path d="' + pen.getCommands() + '"/>')
    cursor += font['hmtx'][name][0] * scale
assert cursor < 590, 'Title must remain clear of the app screen'
picture = base64.b64encode((root / 'store/source/screenshots/01-files.png').read_bytes()).decode()
icon = base64.b64encode((root / 'store/source/icon.svg').read_bytes()).decode()
svg = '''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
<rect width="1024" height="500" fill="#F0F1FA"/>
<defs><clipPath id="screen"><rect x="642" y="32" width="330" height="436" rx="12"/></clipPath></defs>
<image x="52" y="70" width="88" height="88" href="data:image/svg+xml;base64,''' + icon + '''"/>
<g fill="#202020">''' + ''.join(paths) + '''</g>
<g clip-path="url(#screen)"><image x="642" y="32" width="330" height="688" href="data:image/png;base64,''' + picture + '''"/></g>
</svg>'''
source = root / 'store/source/feature.svg'
source.write_text(svg)
subprocess.run(['rsvg-convert', '-o', str(root / 'store/assets/feature-1024x500.png'), str(source)], check=True)
