#!/usr/bin/env python3
"""Generates the art, fonts, models, item definitions, sounds and layout tables of the IdCards resource pack.

    python3 tools/gen_pack.py              # everything (sounds need ffmpeg on PATH)
    python3 tools/gen_pack.py --no-sounds

Pure standard library: PNGs are written by hand, sounds are synthesised as WAV and encoded with ffmpeg.
Everything written here is placeholder art that can be replaced file by file (same name, same size).
Hand-written and NOT touched: pack.mcmeta, shaders/core/text.*, shaders/include/idcards_text_*.glsl,
shaders/include/idcards_config.glsl.

THE CARD HUD (fi.alavesa.idcards.ui, shaders/include/idcards_text_*.glsl). The card is the NAME of a boss bar,
written in the font idcards:ui and moved by the text shader:
- the font is monospace by construction (every cell has an alpha-1 pixel in its last column where the glyph leaves
  it empty, so every character advances CELL_W + 1 px) and has signed spaces of +-2^n px;
- every card text ends with the pen back at 0, so its width is 0 and vanilla's centring puts pen 0 at the screen
  centre; a glyph's distance from the centre is its x on the card;
- the text colour is a marker (red 252, green 0xB8-0xBF) that carries slot, palette colour and mode; the slot gives
  the row and the scale (SLOTS below).
The geometry is written to shaders/include/idcards_layout.glsl (shader) and src/main/resources/layout.properties
(plugin), so the three always agree.
"""
import json, math, os, random, struct, subprocess, sys, wave, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', 'resourcepack'))
NS = os.path.join(ROOT, 'assets', 'idcards')
RES = os.path.normpath(os.path.join(HERE, '..', 'src', 'main', 'resources'))

# ---- card geometry, card pixels (1 card pixel = 1 GUI pixel at scale 1) -------------------------------------------------
CELL_W, CELL_H = 5, 8                # glyph cell; advance = CELL_W + 1
CARD_W, CARD_H = 224, 140
PHOTO = (12, 36, 48)                 # x, y, size of the face (an 8 px head glyph at scale 6)
# slot: name -> (id, x, y, scale, glyph height, anchor 0 = card / 1 = screen corner, max characters)
SLOTS = {
    'art':          (0, 0, 0, 1, CARD_H, 0, 1),
    'photo':        (1, PHOTO[0], PHOTO[1], 6, 8, 0, 1),
    'header':       (2, 10, 8, 1, CELL_H, 0, 34),
    'label_name':   (3, 70, 36, 1, CELL_H, 0, 24),
    'name_big':     (4, 70, 46, 2, CELL_H, 0, 12),
    'name_small':   (5, 70, 50, 1, CELL_H, 0, 24),
    'label_title':  (6, 70, 68, 1, CELL_H, 0, 24),
    'prefix':       (7, 72, 80, 1, CELL_H, 0, 23),
    'label_serial': (8, 12, 94, 1, CELL_H, 0, 12),
    'serial':       (9, 12, 104, 1, CELL_H, 0, 14),
    'barcode':      (10, 105, 100, 3, CELL_H, 0, 9),
    'footer':       (11, 12, 128, 1, CELL_H, 0, 33),
    'hint':         (12, 0, 0, 1, CELL_H, 1, 40),
}
PREFIX_STRIP = (68, 77, 216, 90)     # dark strip under the prefix, x0 y0 x1 y1 (inclusive)
BAR_W = 3                            # a barcode glyph: 3 columns of bars, advance 4

# palette of the text shader: 0-15 the legacy chat colours, 16+ card inks and extra hues for hex prefixes
PALETTE = [0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
           0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
           0x1C2126, 0x6B7480, 0xE8ECEF, 0xC8372D, 0xF4F6F8, 0xFF7A1A, 0xFF8FC8, 0xA6E22E,
           0x7EC8FF, 0x9B6BFF, 0x8B5A2B, 0x1ABC9C, 0xC0C6CC, 0x2E7D32, 0xB71C1C, 0xFFD54F]
INK, LABEL, PAPER_TEXT, ACCENT, HINT = 16, 17, 18, 19, 20

SPACE_POS, SPACE_NEG, SPACE_BITS = 0xF000, 0xF100, 10
CHAR_CARD = 0xE010
CHAR_BARS = 0xE020                   # 8 glyphs: the 3-bit bar patterns
GFX_DOT, GFX_ARROW, GFX_BLOCK = 0xE100, 0xE101, 0xE102

# ---- block geometry, model pixels (front = north) --------------------------------------------------------------------------
# scanner: (dy, dz) moves the reader head; wall = on the back plate, floor = on a post
SCANNER = {'wall': (0.0, 0.0), 'floor': (4.0, -5.0)}
SCANNER_STATES = ['idle', 'scanning', 'granted', 'denied']
SLOT_X = 10.5                        # centre of the card slot
COMPUTER_SCREEN = (3.0, 4.0, 13.0, 12.5, 5.0)   # x0, y0, x1, y1 and the z of the glass (north face of the monitor)


# ---- tiny PNG canvas -------------------------------------------------------------------------------------------------
class Canvas:
    def __init__(self, w, h, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = [[fill if len(fill) == 4 else (*fill, 255)] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c if len(c) == 4 else (*c, 255)

    def rect(self, x0, y0, x1, y1, c):
        """Filled, both corners inclusive."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def frame(self, x0, y0, x1, y1, c):
        for x in range(x0, x1 + 1):
            self.set(x, y0, c); self.set(x, y1, c)
        for y in range(y0, y1 + 1):
            self.set(x0, y, c); self.set(x1, y, c)

    def glyph(self, x, y, rows, c, scale=1):
        for dy, row in enumerate(rows):
            for dx, bit in enumerate(row):
                if bit == '#': self.rect(x + dx * scale, y + dy * scale, x + dx * scale + scale - 1, y + dy * scale + scale - 1, c)

    def text(self, x, y, s, c, scale=1):
        for i, ch in enumerate(s):
            self.glyph(x + i * (CELL_W + 1) * scale, y, G.get(ch, G['?']), c, scale)

    def blit(self, other, x0, y0):
        for y in range(other.h):
            for x in range(other.w):
                if other.px[y][x][3]: self.set(x0 + x, y0 + y, other.px[y][x])

    def scaled(self, w, h):
        """Box-filtered copy (for the small card faces)."""
        out = Canvas(w, h)
        for y in range(h):
            for x in range(w):
                xs = range(x * self.w // w, max(x * self.w // w + 1, (x + 1) * self.w // w))
                ys = range(y * self.h // h, max(y * self.h // h + 1, (y + 1) * self.h // h))
                acc = [0, 0, 0, 0]
                for sy in ys:
                    for sx in xs:
                        for i in range(4): acc[i] += self.px[sy][sx][i]
                n = len(xs) * len(ys)
                out.px[y][x] = tuple(v // n for v in acc)
        return out

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in self.px)
        def chunk(tag, data):
            return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
        with open(path, 'wb') as f:
            f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 6, 0, 0, 0))
                    + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


# ---- the card font: 5x7 capitals --------------------------------------------------------------------------------------
G = {}
def g(ch, *rows):
    G[ch] = rows
g(' ', *['.....'] * 7)
g('0', '.###.', '#...#', '#..##', '#.#.#', '##..#', '#...#', '.###.')
g('1', '..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '.###.')
g('2', '.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####')
g('3', '#####', '...#.', '..#..', '...#.', '....#', '#...#', '.###.')
g('4', '...#.', '..##.', '.#.#.', '#..#.', '#####', '...#.', '...#.')
g('5', '#####', '#....', '####.', '....#', '....#', '#...#', '.###.')
g('6', '..##.', '.#...', '#....', '####.', '#...#', '#...#', '.###.')
g('7', '#####', '....#', '...#.', '..#..', '.#...', '.#...', '.#...')
g('8', '.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.')
g('9', '.###.', '#...#', '#...#', '.####', '....#', '...#.', '.##..')
g('A', '.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#')
g('B', '####.', '#...#', '#...#', '####.', '#...#', '#...#', '####.')
g('C', '.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.')
g('D', '###..', '#..#.', '#...#', '#...#', '#...#', '#..#.', '###..')
g('E', '#####', '#....', '#....', '####.', '#....', '#....', '#####')
g('F', '#####', '#....', '#....', '####.', '#....', '#....', '#....')
g('G', '.###.', '#...#', '#....', '#.###', '#...#', '#...#', '.####')
g('H', '#...#', '#...#', '#...#', '#####', '#...#', '#...#', '#...#')
g('I', '.###.', '..#..', '..#..', '..#..', '..#..', '..#..', '.###.')
g('J', '..###', '...#.', '...#.', '...#.', '...#.', '#..#.', '.##..')
g('K', '#...#', '#..#.', '#.#..', '##...', '#.#..', '#..#.', '#...#')
g('L', '#....', '#....', '#....', '#....', '#....', '#....', '#####')
g('M', '#...#', '##.##', '#.#.#', '#.#.#', '#...#', '#...#', '#...#')
g('N', '#...#', '#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#')
g('O', '.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.')
g('P', '####.', '#...#', '#...#', '####.', '#....', '#....', '#....')
g('Q', '.###.', '#...#', '#...#', '#...#', '#.#.#', '#..#.', '.##.#')
g('R', '####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#')
g('S', '.####', '#....', '#....', '.###.', '....#', '....#', '####.')
g('T', '#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..')
g('U', '#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.')
g('V', '#...#', '#...#', '#...#', '#...#', '#...#', '.#.#.', '..#..')
g('W', '#...#', '#...#', '#...#', '#.#.#', '#.#.#', '#.#.#', '.#.#.')
g('X', '#...#', '#...#', '.#.#.', '..#..', '.#.#.', '#...#', '#...#')
g('Y', '#...#', '#...#', '.#.#.', '..#..', '..#..', '..#..', '..#..')
g('Z', '#####', '....#', '...#.', '..#..', '.#...', '#....', '#####')
g('!', '..#..', '..#..', '..#..', '..#..', '..#..', '.....', '..#..')
g('"', '.#.#.', '.#.#.', '.....', '.....', '.....', '.....', '.....')
g('#', '.#.#.', '.#.#.', '#####', '.#.#.', '#####', '.#.#.', '.#.#.')
g('$', '..#..', '.####', '#.#..', '.###.', '..#.#', '####.', '..#..')
g('%', '##...', '##..#', '...#.', '..#..', '.#...', '#..##', '...##')
g('&', '.##..', '#..#.', '#.#..', '.#...', '#.#.#', '#..#.', '.##.#')
g("'", '..#..', '..#..', '.#...', '.....', '.....', '.....', '.....')
g('(', '...#.', '..#..', '.#...', '.#...', '.#...', '..#..', '...#.')
g(')', '.#...', '..#..', '...#.', '...#.', '...#.', '..#..', '.#...')
g('*', '.....', '..#..', '#.#.#', '.###.', '#.#.#', '..#..', '.....')
g('+', '.....', '..#..', '..#..', '#####', '..#..', '..#..', '.....')
g(',', '.....', '.....', '.....', '.....', '.##..', '..#..', '.#...')
g('-', '.....', '.....', '.....', '.###.', '.....', '.....', '.....')
g('.', '.....', '.....', '.....', '.....', '.....', '.##..', '.##..')
g('/', '....#', '....#', '...#.', '..#..', '.#...', '#....', '#....')
g(':', '.....', '.##..', '.##..', '.....', '.##..', '.##..', '.....')
g(';', '.....', '.##..', '.##..', '.....', '.##..', '..#..', '.#...')
g('<', '...#.', '..#..', '.#...', '#....', '.#...', '..#..', '...#.')
g('=', '.....', '.....', '#####', '.....', '#####', '.....', '.....')
g('>', '.#...', '..#..', '...#.', '....#', '...#.', '..#..', '.#...')
g('?', '.###.', '#...#', '....#', '...#.', '..#..', '.....', '..#..')
g('@', '.###.', '#...#', '#.###', '#.#.#', '#.###', '#....', '.###.')
g('[', '.###.', '.#...', '.#...', '.#...', '.#...', '.#...', '.###.')
g('\\', '#....', '#....', '.#...', '..#..', '...#.', '....#', '....#')
g(']', '.###.', '...#.', '...#.', '...#.', '...#.', '...#.', '.###.')
g('^', '..#..', '.#.#.', '#...#', '.....', '.....', '.....', '.....')
g('_', '.....', '.....', '.....', '.....', '.....', '.....', '#####')
g('|', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..')
g('Ä', '#...#', '.###.', '#...#', '#...#', '#####', '#...#', '#...#')
g('Ö', '#...#', '.###.', '#...#', '#...#', '#...#', '#...#', '.###.')
g('Å', '..#..', '.#.#.', '..#..', '.###.', '#...#', '#####', '#...#')
g('Ü', '#...#', '.....', '#...#', '#...#', '#...#', '#...#', '.###.')
g('É', '...#.', '#####', '#....', '####.', '#....', '#....', '#####')
g(chr(GFX_DOT), '.....', '.###.', '#####', '#####', '#####', '.###.', '.....')
g(chr(GFX_ARROW), '#....', '##...', '###..', '####.', '###..', '##...', '#....')
g(chr(GFX_BLOCK), *['#####'] * 7)

SHEET = [chr(c) for c in range(0x20, 0x60)] + list('ÄÖÅÜÉ') + [chr(GFX_DOT), chr(GFX_ARROW), chr(GFX_BLOCK)]
SHEET_COLS = 16


def rgb(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write('\n')


def font_sheet():
    rows = (len(SHEET) + SHEET_COLS - 1) // SHEET_COLS
    c = Canvas(SHEET_COLS * CELL_W, rows * CELL_H)
    for i, ch in enumerate(SHEET):
        cx, cy = (i % SHEET_COLS) * CELL_W, (i // SHEET_COLS) * CELL_H
        c.glyph(cx, cy, G[ch], (255, 255, 255))
        # width keeper: one alpha-1 pixel in the cell's last column (invisible: vanilla discards alpha < 0.1)
        if all(c.px[cy + y][cx + CELL_W - 1][3] == 0 for y in range(CELL_H)):
            c.set(cx + CELL_W - 1, cy + CELL_H - 1, (0, 0, 0, 1))
    chars = [''.join(SHEET[r * SHEET_COLS:(r + 1) * SHEET_COLS]) for r in range(rows)]
    last = chars[-1]
    chars[-1] = last + ''.join(chr(0xE1F0 + i) for i in range(SHEET_COLS - len(last)))   # filler codepoints, never sent
    return c, chars


def bars_sheet():
    """8 barcode glyphs: bit n of the index = a bar in column n. The 4th column only keeps the advance at 4."""
    c = Canvas(8 * BAR_W, CELL_H)
    for i in range(8):
        for col in range(BAR_W):
            if i & (1 << col): c.rect(i * BAR_W + col, 0, i * BAR_W + col, CELL_H - 1, (255, 255, 255))
        if not i & (1 << (BAR_W - 1)): c.set(i * BAR_W + BAR_W - 1, CELL_H - 1, (0, 0, 0, 1))
    return c


# ---- the card ---------------------------------------------------------------------------------------------------------
PAPER, PAPER_DARK, PAPER_LIGHT = (222, 226, 230), (176, 182, 189), (240, 243, 245)
BAND, EDGE, STRIPE = (36, 41, 47), (20, 23, 27), rgb(PALETTE[ACCENT])


def emblem(c, cx, cy, col):
    """An original mark: an octagon with a slit, like a shuttered lens. Not any existing logo."""
    for y in range(-7, 8):
        for x in range(-7, 8):
            d = max(abs(x), abs(y), (abs(x) + abs(y)) * 0.72)
            if 5.2 <= d <= 7.2 or (abs(y) <= 1 and abs(x) <= 3): c.set(cx + x, cy + y, col)


def card_art(sample=False, labels=False):
    """The card background. sample=True also paints example contents (for the item and model textures);
    labels=True paints only the fixed captions (for the card held out in the world, whose details are text displays)."""
    c = Canvas(CARD_W, CARD_H, PAPER)
    rnd = random.Random(11)
    for y in range(CARD_H):                                   # faint security pattern
        for x in range(CARD_W):
            if (x + y * 2) % 14 == 0 or (x * 3 - y) % 23 == 0: c.set(x, y, PAPER_LIGHT)
    c.rect(0, 0, CARD_W - 1, 21, BAND)
    c.rect(0, 22, CARD_W - 1, 25, STRIPE)
    emblem(c, CARD_W - 16, 11, (200, 206, 212))
    px, py, ps = PHOTO
    c.rect(px - 2, py - 2, px + ps + 1, py + ps + 1, EDGE)    # photo frame
    c.rect(px, py, px + ps - 1, py + ps - 1, (120, 128, 138))
    for i in range(0, ps, 6):                                 # height marks behind the face
        c.rect(px, py + i, px + ps - 1, py + i, (104, 112, 122))
    x0, y0, x1, y1 = PREFIX_STRIP
    c.rect(x0, y0, x1, y1, BAND)
    c.rect(x0, y0, x0 + 1, y1, STRIPE)
    c.rect(70, 63, 214, 63, PAPER_DARK)                       # rule under the name
    for bx, bw in ((12, 40), (56, 22)):                       # redacted lines
        c.rect(bx, 116, bx + bw, 121, EDGE)
    c.rect(102, 98, 216, 125, PAPER_LIGHT)                     # barcode field
    c.frame(102, 98, 216, 125, PAPER_DARK)
    for x in range(12, 212, 4): c.set(x, 126, PAPER_DARK)     # perforation rule
    for (ax, ay, dx, dy) in ((3, 29, 1, 1), (CARD_W - 4, 29, -1, 1), (3, CARD_H - 4, 1, -1), (CARD_W - 4, CARD_H - 4, -1, -1)):
        for i in range(5):                                    # registration corners
            c.set(ax + dx * i, ay, PAPER_DARK); c.set(ax, ay + dy * i, PAPER_DARK)
    c.frame(0, 0, CARD_W - 1, CARD_H - 1, EDGE)               # the right-most column is opaque: advance = CARD_W + 1
    for p in ((0, 0), (CARD_W - 1, 0), (0, CARD_H - 1), (CARD_W - 1, CARD_H - 1)): c.set(*p, (0, 0, 0, 0))
    if labels:
        c.text(10, 8, 'SECURE FACILITY - PERSONNEL ID', rgb(PALETTE[PAPER_TEXT]))
        c.text(70, 36, 'NAME', rgb(PALETTE[LABEL])); c.text(70, 68, 'TITLE', rgb(PALETTE[LABEL])); c.text(12, 94, 'SERIAL', rgb(PALETTE[LABEL]))
        c.text(12, 128, 'PROPERTY OF SITE ADMINISTRATION', rgb(PALETTE[LABEL]))
    if sample:
        c.text(10, 8, 'SECURE FACILITY - PERSONNEL ID', rgb(PALETTE[PAPER_TEXT]))
        c.rect(px + 12, py + 10, px + 35, py + 37, (216, 162, 122)); c.rect(px + 12, py + 6, px + 35, py + 14, (74, 50, 32))
        c.rect(px + 6, py + 38, px + 41, py + 47, (60, 70, 84))
        c.text(70, 36, 'NAME', rgb(PALETTE[LABEL])); c.text(70, 46, 'PERSONNEL', rgb(PALETTE[INK]), 2)
        c.text(70, 68, 'TITLE', rgb(PALETTE[LABEL])); c.text(72, 80, 'RESEARCHER', rgb(PALETTE[14]))
        c.text(12, 94, 'SERIAL', rgb(PALETTE[LABEL])); c.text(12, 104, '0000-0000-0000', rgb(PALETTE[INK]))
        for i in range(9):
            v = rnd.randrange(8)
            for col in range(3):
                if v & (1 << col): c.rect(105 + (i * 4 + col) * 3, 100, 105 + (i * 4 + col) * 3 + 2, 123, EDGE)
    return c


def card_back():
    c = Canvas(CARD_W, CARD_H, BAND)
    for y in range(CARD_H):
        for x in range(CARD_W):
            if (x + y) % 9 == 0: c.set(x, y, (44, 50, 57))
    c.rect(0, 24, CARD_W - 1, 46, EDGE)                       # magnetic stripe
    c.rect(0, CARD_H - 10, CARD_W - 1, CARD_H - 7, STRIPE)
    c.text(12, 70, 'THIS CARD REMAINS THE PROPERTY', PAPER_DARK); c.text(12, 82, 'OF SITE ADMINISTRATION', PAPER_DARK)
    c.frame(0, 0, CARD_W - 1, CARD_H - 1, EDGE)
    return c


# ---- item icons (16x16) -----------------------------------------------------------------------------------------------
def icon(rows, palette):
    c = Canvas(16, 16)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in palette: c.set(x, y, palette[ch])
    return c


TOOL_ICON = ['................', '..........ggg...', '.........gcccg..', '.........gcccg..', '..........ggg...', '.........dd.....',
             '........dd......', '.......dd.......', '......dd........', '.....dd.........', '..bbbdd.........', '.bbbbb..........',
             '.bbrbb..........', '.bbbbb..........', '..bbb...........', '................']
TOOL_PAL = {'g': (70, 78, 88), 'c': (126, 200, 255), 'd': (150, 158, 168), 'b': BAND, 'r': STRIPE}


# ---- models -----------------------------------------------------------------------------------------------------------
def box(frm, to, uv, faces=None, light=0):
    """An element whose faces all show the texture region uv (u0,v0,u1,v1); faces overrides single faces."""
    el = {'from': [round(v, 3) for v in frm], 'to': [round(v, 3) for v in to], 'faces': {}}
    if light: el['light_emission'] = light
    for d in ('north', 'south', 'east', 'west', 'up', 'down'):
        el['faces'][d] = {'uv': list((faces or {}).get(d, uv)), 'texture': '#0'}
    return el


BLOCK_DISPLAY = {
    'gui': {'rotation': [30, 225, 0], 'translation': [0, 0, 0], 'scale': [0.625, 0.625, 0.625]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.25, 0.25, 0.25]},
    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375, 0.375, 0.375]},
    'thirdperson_lefthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375, 0.375, 0.375]},
    'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [0.4, 0.4, 0.4]},
    'firstperson_lefthand': {'rotation': [0, 225, 0], 'translation': [0, 0, 0], 'scale': [0.4, 0.4, 0.4]},
}   # no "fixed": item displays use that context and must show the model exactly in its cell


def shade(c, x0, y0, x1, y1, col, seed=0, grain=6, bevel=True):
    """A swatch with a little grain and a light top-left / dark bottom-right edge, so flat faces read as material."""
    rnd = random.Random(seed)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            d = rnd.randint(-grain, grain)
            c.set(x, y, tuple(max(0, min(255, v + d)) for v in col))
    if bevel:
        hi, lo = tuple(min(255, v + 22) for v in col), tuple(max(0, v - 24) for v in col)
        for x in range(x0, x1 + 1): c.set(x, y0, hi); c.set(x, y1, lo)
        for y in range(y0, y1 + 1): c.set(x0, y, hi); c.set(x1, y, lo)


LED_COLOURS = ((150, 104, 24), (60, 140, 255), (60, 230, 90), (240, 50, 40))   # idle, scanning, granted, denied


def scanner_texture():
    """64x64, 4 texture pixels per model pixel. Swatches in the top rows, the front panel below."""
    c = Canvas(64, 64)
    for i, col in enumerate(((46, 51, 58), (150, 157, 166), (84, 90, 98), (26, 29, 34), (196, 201, 207), (12, 14, 17))):
        shade(c, i * 8, 0, i * 8 + 7, 7, col, seed=i)          # body, rail, post, plate, key, slot
    for i, col in enumerate(LED_COLOURS):                      # light per state: a lens with a hot spot
        c.rect(i * 8, 8, i * 8 + 7, 15, tuple(v // 3 for v in col))
        c.rect(i * 8 + 1, 9, i * 8 + 6, 14, col)
        c.rect(i * 8 + 2, 10, i * 8 + 3, 11, tuple(min(255, v + 90) for v in col))
    # front panel: 8 x 10 model px = 32 x 40 texture px at (0,16)
    shade(c, 0, 16, 31, 55, (46, 51, 58), seed=9, grain=4)
    c.frame(1, 17, 30, 54, (30, 34, 39))
    shade(c, 3, 19, 19, 25, (20, 23, 27), seed=3, grain=2)     # recess around the light and the status window
    c.rect(3, 28, 19, 30, STRIPE)                              # accent stripe
    for y in (33, 35, 37):                                     # speaker grille
        for x in range(4, 19, 2): c.set(x, y, (18, 20, 24))
    shade(c, 3, 40, 19, 52, (24, 27, 32), seed=4, grain=2)     # keypad bed
    for sx, sy in ((2, 18), (29, 18), (2, 53), (29, 53)):      # screws
        c.set(sx, sy, (120, 126, 134)); c.set(sx + 1, sy, (70, 75, 82))
    c.rect(22, 18, 30, 53, (18, 20, 24))                       # shadow behind the slot rails
    for y in range(20, 52, 4): c.set(26, y, (60, 66, 74))      # guide marks in the slot
    return c


def scanner_model(mount, state):
    dy, dz = SCANNER[mount]
    T = lambda i: (i * 2, 0, i * 2 + 2, 2)                     # swatch i in uv units
    body, rail, post, plate, key, slot = (T(i) for i in range(6))
    s = SCANNER_STATES.index(state)
    led = (s * 2 + 0.25, 2.25, s * 2 + 1.75, 3.75)
    B = lambda a, b, uv, faces=None, light=0: box((a[0], a[1] + dy, a[2] + dz), (b[0], b[1] + dy, b[2] + dz), uv, faces, light)
    els = [
        B((3, 2, 15), (13, 14, 16), plate),                                         # wall plate
        B((4, 3, 12.5), (12, 13, 15), body, {'north': (0, 4, 8, 14)}),              # housing
        B((3.6, 13, 12.2), (12.4, 13.7, 15), plate),                                # top cap
        B((3.6, 2.3, 12.2), (12.4, 3, 15), plate),                                  # bottom cap
        B((8.9, 3, 11.4), (9.9, 13, 12.5), rail),                                   # slot rails
        B((11.1, 3, 11.4), (12.1, 13, 12.5), rail),
        B((8.7, 12.2, 11.2), (9.9, 13.2, 12.5), rail),                              # flared slot mouth
        B((11.1, 12.2, 11.2), (12.3, 13.2, 12.5), rail),
        B((9.9, 3, 12.3), (11.1, 13, 12.5), slot),                                  # slot floor
        B((4.6, 10.6, 12.2), (6.6, 12.2, 12.5), plate),                             # light housing
        B((4.9, 10.9, 12.05), (6.3, 11.9, 12.2), led, light=15),                    # the light
        B((6.9, 10.9, 12.3), (8.4, 11.9, 12.5), led, light=10),                     # status window in the same colour
    ]
    for kx in range(3):                                                             # 3 x 3 keypad
        for ky in range(3):
            els.append(B((4.8 + kx * 1.15, 3.6 + ky * 1.0, 12.25), (5.7 + kx * 1.15, 4.3 + ky * 1.0, 12.5), key))
    if mount == 'wall':
        els.append(box((7.4, 0, 15.3), (8.6, 2.3, 16), post))                       # cable conduit
    else:
        els += [box((6.8, 1.4, 8.2), (9.8, 7, 10.8), post), box((6.3, 0.8, 7.7), (10.3, 1.4, 11.3), rail),
                box((4, 0, 5), (12, 0.8, 13), plate), box((7.6, 2, 10.8), (9, 6.4, 11.2), plate)]
    return {'textures': {'0': 'idcards:block/scanner', 'particle': 'idcards:block/scanner'}, 'elements': els, 'display': BLOCK_DISPLAY}


CASE, CASE_SHADE, PLASTIC, KEYCAP = (198, 196, 184), (164, 162, 150), (38, 41, 47), (176, 174, 164)


def computer_texture():
    """128x128, 8 texture pixels per model pixel."""
    c = Canvas(128, 128)
    for i, col in enumerate((CASE, CASE_SHADE, PLASTIC, KEYCAP, (100, 102, 96), (60, 230, 90))):
        shade(c, i * 16, 0, i * 16 + 15, 15, col, seed=20 + i, grain=4, bevel=i != 5)   # case, shade, plastic, key, vent, light
    # the glass: 10 x 8.5 model px = 80 x 68 at (0,16)
    for y in range(68):
        for x in range(80):
            edge = min(x, 79 - x, y, 67 - y)
            g = 16 + (y % 2) * 5 - max(0, 8 - edge)           # scanlines, darker towards the bezel
            c.set(x, 16 + y, (4 + g // 4, max(4, g), 6 + g // 2))
    for i in range(18):                                        # a soft reflection in the top-left corner
        for t in range(3): c.set(6 + i + t, 22 + (18 - i) // 2, (20, 44, 30))
    c.frame(0, 16, 79, 83, (2, 6, 4))
    # keyboard top: 10 x 2.4 model px = 80 x 19 at (0,88): three rows of keys
    shade(c, 0, 88, 79, 107, PLASTIC, seed=31, grain=2, bevel=False)
    for r in range(3):
        for k in range(13):
            x0 = 2 + k * 6 + r
            if x0 + 4 > 78: continue
            c.rect(x0, 90 + r * 6, x0 + 4, 93 + r * 6, KEYCAP); c.rect(x0, 94 + r * 6, x0 + 4, 94 + r * 6, (120, 118, 110))
    # base unit front: 14 x 2 model px = 112 x 16 at (0,110): drive slot, vents, badge
    shade(c, 0, 110, 111, 125, CASE, seed=32, grain=3)
    c.rect(8, 116, 52, 118, PLASTIC); c.rect(10, 120, 20, 121, CASE_SHADE)
    for x in range(64, 96, 3): c.rect(x, 114, x, 122, CASE_SHADE)
    c.rect(100, 115, 106, 117, STRIPE)
    # vents for the back of the monitor: 16 x 16 at (96,16)
    shade(c, 96, 16, 111, 31, CASE_SHADE, seed=33, grain=3)
    for y in range(18, 31, 3): c.rect(98, y, 109, y, (96, 94, 86))
    return c


def computer_model():
    T = lambda i: (i * 2, 0, i * 2 + 2, 2)
    case, shadec, plastic, key, vent, light = (T(i) for i in range(6))
    x0, y0, x1, y1, z = COMPUTER_SCREEN
    top, bottom = y1 + 0.7, y0 - 0.7                           # monitor shell around the glass
    els = [
        box((1, 0.3, 3), (15, 2.3, 14.5), case, {'north': (0, 13.75, 14, 15.75), 'down': shadec}),      # base unit
        box((12.4, 0.9, 2.9), (13.2, 1.3, 3), light, light=15),                                         # power light
        box((6.3, 2.3, 7), (9.7, bottom, 10.5), shadec),                                                # neck
        box((x0 - 1, bottom, z), (x1 + 1, top, 11.5), case),                                            # monitor shell
        box((x0 - 1, bottom, z - 0.35), (x0, top, z), case),                                            # bezel: left, right, bottom, top
        box((x1, bottom, z - 0.35), (x1 + 1, top, z), case),
        box((x0, bottom, z - 0.35), (x1, y0, z), case),
        box((x0, y1, z - 0.35), (x1, top, z), case),
        box((x0, y0, z - 0.02), (x1, y1, z), plastic, {'north': (0, 2, 10, 10.5)}, light=15),          # the glass
        box((x0 + 0.5, bottom + 1, 11.5), (x1 - 0.5, top - 1, 14), shadec, {'south': (12, 2, 14, 4), 'up': (12, 2, 14, 4)}),  # tube housing
        box((x1 - 1.6, bottom + 0.15, z - 0.4), (x1 - 1.0, bottom + 0.5, z - 0.35), light, light=15),   # monitor light
        box((2.5, 0, 0.2), (13.5, 0.8, 2.8), plastic),                                                  # keyboard
        box((3, 0.8, 0.4), (13, 1.05, 2.6), plastic, {'up': (0, 11, 10, 13.5)}),                        # key bed
    ]
    for fx, fz in ((1.5, 3.5), (13.5, 3.5), (1.5, 13), (13.5, 13)):                                     # feet
        els.append(box((fx - 0.5, 0, fz - 0.5), (fx + 0.5, 0.3, fz + 0.5), plastic))
    return {'textures': {'0': 'idcards:block/computer', 'particle': 'idcards:block/computer'}, 'elements': els, 'display': BLOCK_DISPLAY}


def card_models():
    """card_3d: a 12 x 7.5 px slab, front on its north side, with example contents (the prop in the scanner slot).
    card_blank: the same slab with the bare card on both faces; presenting writes the real details on it."""
    edge = (0, 0, 0.5, 0.5)
    el = box((2, 4.25, 7.875), (14, 11.75, 8.125), edge, {'north': (0, 0, 16, 8), 'south': (0, 8, 16, 16)})
    write_json(os.path.join(NS, 'models', 'item', 'card_3d.json'),
               {'textures': {'0': 'idcards:item/card_face', 'particle': 'idcards:item/card_face'}, 'elements': [el]})
    face = (0, 0, CARD_W / 16.0, CARD_H / 16.0)                # the art sits in the top-left of a 256x256 texture
    el = box((2, 4.25, 7.875), (14, 11.75, 8.125), (15, 15, 15.5, 15.5), {'north': face, 'south': face})
    write_json(os.path.join(NS, 'models', 'item', 'card_blank.json'),
               {'textures': {'0': 'idcards:item/card_blank', 'particle': 'idcards:item/card_blank'}, 'elements': [el]})


def card_icon():
    """The inventory icon, 64x64: the card with example contents, a soft shadow under it."""
    c = Canvas(64, 64)
    small = card_art(sample=True).scaled(60, 38)
    c.rect(4, 16, 63, 53, (0, 0, 0, 70))
    c.blit(small, 2, 13)
    c.frame(2, 13, 61, 50, EDGE)
    for p in ((2, 13), (61, 13), (2, 50), (61, 50)): c.set(*p, (0, 0, 0, 0))
    return c


def item_def(name, model):
    write_json(os.path.join(NS, 'items', name + '.json'), {'model': {'type': 'minecraft:model', 'model': 'idcards:' + model}})


def art():
    sheet, chars = font_sheet()
    sheet.save(os.path.join(NS, 'textures', 'font', 'ui.png'))
    bars_sheet().save(os.path.join(NS, 'textures', 'font', 'bars.png'))
    card_art().save(os.path.join(NS, 'textures', 'font', 'card.png'))
    advances = {}
    for n in range(SPACE_BITS):
        advances[chr(SPACE_POS + n)] = float(1 << n)
        advances[chr(SPACE_NEG + n)] = -float(1 << n)
    write_json(os.path.join(NS, 'font', 'ui.json'), {'providers': [
        {'type': 'space', 'advances': advances},
        {'type': 'bitmap', 'file': 'idcards:font/ui.png', 'height': CELL_H, 'ascent': 7, 'chars': chars},
        {'type': 'bitmap', 'file': 'idcards:font/bars.png', 'height': CELL_H, 'ascent': 7, 'chars': [''.join(chr(CHAR_BARS + i) for i in range(8))]},
        {'type': 'bitmap', 'file': 'idcards:font/card.png', 'height': CARD_H, 'ascent': 7, 'chars': [chr(CHAR_CARD)]},
    ]})

    # the HUD rides on a BLUE boss bar whose bar graphic is blanked (the same sprites the Radio plugin blanks)
    for name in ('blue_background', 'blue_progress'):
        Canvas(182, 5).save(os.path.join(ROOT, 'assets', 'minecraft', 'textures', 'gui', 'sprites', 'boss_bar', name + '.png'))

    # items
    card_icon().save(os.path.join(NS, 'textures', 'item', 'id_card.png'))
    icon(TOOL_ICON, TOOL_PAL).save(os.path.join(NS, 'textures', 'item', 'link_tool.png'))
    face = Canvas(64, 80)
    face.blit(card_art(sample=True).scaled(64, 40), 0, 0)
    face.blit(card_back().scaled(64, 40), 0, 40)
    face.save(os.path.join(NS, 'textures', 'item', 'card_face.png'))
    for name in ('id_card', 'link_tool'):
        write_json(os.path.join(NS, 'models', 'item', name + '.json'), {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'idcards:item/' + name}})
        item_def(name, 'item/' + name)
    blank = Canvas(256, 256, EDGE)
    blank.blit(card_art(labels=True), 0, 0)
    blank.save(os.path.join(NS, 'textures', 'item', 'card_blank.png'))
    card_models()
    item_def('card_blank', 'item/card_blank')
    # what a presenting player's hand is said to hold: nothing visible (the card is a group of display entities)
    write_json(os.path.join(NS, 'items', 'empty.json'), {'model': {'type': 'minecraft:empty'}})
    item_def('card_prop', 'item/card_3d')

    # blocks
    scanner_texture().save(os.path.join(NS, 'textures', 'block', 'scanner.png'))
    computer_texture().save(os.path.join(NS, 'textures', 'block', 'computer.png'))
    for mount in SCANNER:
        for state in SCANNER_STATES:
            name = f'scanner_{mount}_{state}'
            write_json(os.path.join(NS, 'models', 'block', name + '.json'), scanner_model(mount, state))
            item_def(name, 'block/' + name)
    item_def('scanner', 'block/scanner_wall_idle')
    write_json(os.path.join(NS, 'models', 'block', 'computer.json'), computer_model())
    item_def('computer', 'block/computer')

    layout()


def layout():
    order = sorted(SLOTS.items(), key=lambda kv: kv[1][0])
    os.makedirs(os.path.join(NS, 'shaders', 'include'), exist_ok=True)
    with open(os.path.join(NS, 'shaders', 'include', 'idcards_layout.glsl'), 'w') as f:
        f.write('// Generated by tools/gen_pack.py: the card geometry in card pixels (the plugin reads the same numbers from\n'
                '// layout.properties). One row per slot: top y, scale, glyph height, anchor (0 = on the card, 1 = screen corner).\n'
                f'const vec2 IDC_CARD = vec2({CARD_W}.0, {CARD_H}.0);\n'
                f'const int IDC_SLOTS = {len(order)};\n'
                f'const vec4 IDC_SLOT[{len(order)}] = vec4[{len(order)}](\n'
                + ',\n'.join(f'    vec4({v[2]}.0, {v[3]}.0, {v[4]}.0, {v[5]}.0)   // {v[0]} {k}' for k, v in order)
                + '\n);\n'
                f'const int IDC_SLOT_ART = 0;\nconst int IDC_SLOT_PHOTO = 1;\n'
                f'const vec3 IDC_PALETTE[{len(PALETTE)}] = vec3[{len(PALETTE)}](\n'
                + ',\n'.join('    vec3(%.4f, %.4f, %.4f)' % tuple(v / 255 for v in rgb(p)) for p in PALETTE) + '\n);\n')
    # GLSL does not allow a comment between an array element and its comma: move the commas in front of the comments
    p = os.path.join(NS, 'shaders', 'include', 'idcards_layout.glsl')
    s = open(p).read()
    import re
    s = re.sub(r'\)(   // [0-9]+ [a-z_]+),\n', r'),\1\n', s)
    open(p, 'w').write(s)

    lines = ['# Generated by tools/gen_pack.py. Geometry shared by the resource pack and the plugin; do not edit by hand.',
             f'card.width={CARD_W}', f'card.height={CARD_H}', f'font.advance={CELL_W + 1}', f'bars.advance={BAR_W + 1}',
             f'photo={PHOTO[0]},{PHOTO[1]},{PHOTO[2]}',
             'palette=' + ','.join('%06X' % p for p in PALETTE),
             f'palette.ink={INK}', f'palette.label={LABEL}', f'palette.paper={PAPER_TEXT}', f'palette.accent={ACCENT}', f'palette.hint={HINT}',
             f'sheet.columns={SHEET_COLS}',
             f'cell={CELL_W},{CELL_H}']
    for k, v in order:
        lines.append(f'slot.{k}={v[0]},{v[1]},{v[2]},{v[3]},{v[6]}')
    for mount, (dy, dz) in SCANNER.items():
        hit = (3, 2, 11, 13, 14, 16) if mount == 'wall' else (3, 0, 5, 13, 18, 13)
        lines.append(f'scanner.{mount}.hitbox=' + ','.join(str(v) for v in hit))
        lines.append(f'scanner.{mount}.swipe={SLOT_X},{17.5 + dy},{4.5 + dy},{round(11.3 + dz, 3)}')   # x, y from, y to, z of the card centre
    lines.append('computer.hitbox=-0.1,-0.05,-0.1,16.1,16.1,16.1')
    lines.append('computer.screen=' + ','.join(str(v) for v in COMPUTER_SCREEN))
    lines.append(f'photo.scale={SLOTS["photo"][3]}')
    with open(os.path.join(RES, 'layout.properties'), 'w') as f:
        f.write('\n'.join(lines) + '\n')


# ---- sounds ----------------------------------------------------------------------------------------------------------
RATE = 44100


def env(t, attack, hold, release):
    if t < 0: return 0.0
    if t < attack: return t / attack
    if t < attack + hold: return 1.0
    return max(0.0, 1.0 - (t - attack - hold) / release)


def sounds():
    rnd = random.Random(5)
    noise = lambda: rnd.uniform(-1, 1)
    tone = lambda f, t: math.sin(2 * math.pi * f * t)
    square = lambda f, t: 1.0 if math.sin(2 * math.pi * f * t) >= 0 else -1.0
    def synth(seconds, fn):
        return [fn(i / RATE) for i in range(int(seconds * RATE))]
    clips = {
        # a card dragged through a plastic slot: filtered-sounding noise that rises, with a click at each end
        'swipe': synth(0.42, lambda t: 0.5 * noise() * env(t - 0.03, 0.05, 0.2, 0.1) * (0.5 + 0.5 * tone(90 + 260 * t, t))
                       + 0.8 * tone(210, t) * env(t, 0.001, 0.005, 0.02) + 0.7 * tone(170, t) * env(t - 0.36, 0.001, 0.005, 0.03)),
        'granted': synth(0.34, lambda t: 0.6 * tone(1175, t) * env(t, 0.004, 0.09, 0.02) + 0.6 * tone(1568, t) * env(t - 0.13, 0.004, 0.14, 0.05)),
        'denied': synth(0.46, lambda t: 0.45 * square(155, t) * env(t, 0.004, 0.16, 0.02) + 0.45 * square(155, t) * env(t - 0.23, 0.004, 0.16, 0.04)),
        'card_open': synth(0.16, lambda t: 0.5 * noise() * env(t, 0.002, 0.02, 0.08) + 0.5 * tone(880, t) * env(t - 0.02, 0.003, 0.03, 0.08)),
        'card_close': synth(0.14, lambda t: 0.5 * noise() * env(t, 0.002, 0.02, 0.06) + 0.5 * tone(620, t) * env(t - 0.01, 0.003, 0.03, 0.07)),
        'link': synth(0.18, lambda t: 0.5 * tone(1320, t) * env(t, 0.003, 0.04, 0.02) + 0.5 * tone(1760, t) * env(t - 0.07, 0.003, 0.05, 0.04)),
    }
    out = os.path.join(NS, 'sounds')
    os.makedirs(out, exist_ok=True)
    for name, samples in clips.items():
        wav = os.path.join(out, name + '.wav')
        with wave.open(wav, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(b''.join(struct.pack('<h', int(max(-1.0, min(1.0, s)) * 26000)) for s in samples))
        # Minecraft only loads Ogg Vorbis. Mono, so the sounds are positioned in the world. libvorbis when this ffmpeg
        # has it; otherwise the built-in encoder, which only writes stereo (stereo sounds play unpositioned).
        mono = ['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'libvorbis', '-ac', '1', '-q:a', '4', '-map_metadata', '-1', '-bitexact', os.path.join(out, name + '.ogg')]
        if subprocess.run(mono, capture_output=True).returncode != 0:
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'vorbis', '-strict', '-2', '-ac', '2', '-q:a', '5',
                            '-map_metadata', '-1', '-bitexact', os.path.join(out, name + '.ogg')], check=True)
        os.remove(wav)
    subs = {'swipe': 'Card swiped', 'granted': 'Scanner accepts', 'denied': 'Scanner refuses', 'card_open': 'ID card shown',
            'card_close': 'ID card put away', 'link': 'Link tool beeps'}
    write_json(os.path.join(NS, 'sounds.json'), {
        name: {'sounds': [{'name': f'idcards:{name}'}], 'subtitle': f'subtitles.idcards.{name}'} for name in clips})
    write_json(os.path.join(NS, 'lang', 'en_us.json'), {f'subtitles.idcards.{k}': v for k, v in subs.items()})


if __name__ == '__main__':
    art()
    if '--no-sounds' not in sys.argv: sounds()
    print('generated into', ROOT)
