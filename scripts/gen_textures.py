#!/usr/bin/env python3
"""Generates all Life Essentials textures as Minecraft-style pixel art.

Pure stdlib (zlib + struct) PNG writer — no Pillow needed.
Run from the project root:  python3 scripts/gen_textures.py
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "lifeessentials")


# ---------------------------------------------------------------- PNG writer

def write_png(path, pixels):
    """pixels: list of rows; each row a list of (r, g, b, a) tuples."""
    h = len(pixels)
    w = len(pixels[0])
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("4B", *px) for px in row) for row in pixels
    )

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", os.path.relpath(path, ROOT), f"({w}x{h})")


def hx(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


CLEAR = (0, 0, 0, 0)


def canvas(w, h, fill=CLEAR):
    return [[fill for _ in range(w)] for _ in range(h)]


def rect(px, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if 0 <= y < len(px) and 0 <= x < len(px[0]):
                px[y][x] = color


def grid_art(rows, palette):
    return [[palette[ch] for ch in row] for row in rows]


def upscale(px, n):
    out = []
    for row in px:
        big = []
        for p in row:
            big.extend([p] * n)
        out.extend([big] * n)
    return out


def lerp(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


# ---------------------------------------------------------------- item: phone

def phone_item():
    pal = {
        ".": CLEAR,
        "B": hx("101015"),      # outline
        "e": hx("34343e"),      # frame
        "E": hx("4a4a57"),      # frame highlight
        "c": hx("9ad9ff"),      # camera
        "h": hx("6d6d7c"),      # home bar
        "1": hx("1b3c8c"),
        "2": hx("2f6fd0"),
        "3": hx("3fa9e8"),
        "4": hx("8fe0f7"),
        "w": hx("d8f6ff"),
    }
    rows = [
        "................",
        "...BBBBBBBBBB...",
        "..BEeeeccceeeB..",
        "..Be11111111eB..",
        "..Be11111112eB..",
        "..Be22222222eB..",
        "..Be2222222weB..",
        "..Be23333333eB..",
        "..Be33333333eB..",
        "..Be3334w333eB..",
        "..Be34444444eB..",
        "..Be44444444eB..",
        "..BEeeeeeeeeeB..",
        "..BeeehhhheeeB..",
        "...BBBBBBBBBB...",
        "................",
    ]
    return grid_art(rows, pal)


# ---------------------------------------------------------------- item: airpods

def airpods_item():
    pal = {
        ".": CLEAR,
        "B": hx("3c3c46"),   # soft outline
        "w": hx("f5f5f7"),   # white
        "s": hx("dcdce3"),   # shade
        "d": hx("c4c4cd"),   # deeper shade
        "t": hx("aeb4bf"),   # metal tip
        "k": hx("23232b"),   # speaker grille dot
    }
    rows = [
        "................",
        "..BBB......BBB..",
        ".BwwwB....BwwwB.",
        ".BwksB....BskwB.",
        ".BwwsB....BswwB.",
        ".BBwsB....BswBB.",
        "..BwsB....BswB..",
        "..BwsB....BswB..",
        "..BwdB....BdwB..",
        "..BtdB....BdtB..",
        "...BB......BB...",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]
    # nudge art down 2px so it sits centered
    rows = ["................"] * 2 + rows[:-2]
    return grid_art(rows, pal)


# ---------------------------------------------------------------- item: circuit board

def circuit_board_item():
    base = hx("2a8340")
    dark = hx("1c5e2d")
    edge = hx("144721")
    gold = hx("e9c654")
    goldd = hx("b9973a")
    red = hx("d8402f")
    chip = hx("d3d8e0")
    chipd = hx("8a919e")
    pin = hx("30343c")

    px = canvas(16, 16, base)
    # border + subtle texture
    rect(px, 0, 0, 15, 0, edge)
    rect(px, 0, 15, 15, 15, edge)
    rect(px, 0, 0, 0, 15, edge)
    rect(px, 15, 0, 15, 15, edge)
    for y in range(1, 15):
        for x in range(1, 15):
            if (x * 7 + y * 13) % 11 == 0:
                px[y][x] = dark
    # gold traces
    rect(px, 2, 3, 13, 3, gold)
    rect(px, 2, 12, 13, 12, gold)
    rect(px, 3, 4, 3, 11, gold)
    rect(px, 12, 4, 12, 11, gold)
    px[3][2] = goldd
    px[12][13] = goldd
    px[12][2] = goldd
    px[3][13] = goldd
    # chip
    rect(px, 6, 6, 9, 9, chip)
    rect(px, 6, 9, 9, 9, chipd)
    for x in (6, 8):
        px[5][x + 1] = pin
        px[10][x] = pin
    # redstone dots
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13), (5, 12), (10, 3)):
        px[y][x] = red
    return px


# ------------------------------------------------------- entity: airpods (worn)

def airpods_entity():
    px = canvas(16, 16, hx("f2f3f5"))
    for y in range(16):
        for x in range(16):
            m = (x * 5 + y * 11) % 13
            if m == 0:
                px[y][x] = hx("e1e3e8")
            elif m == 7:
                px[y][x] = hx("d2d5dc")
    return px


# ---------------------------------------------------- gui: phone front (held)

def phone_front():
    W, H = 64, 128
    px = canvas(W, H, CLEAR)
    body = hx("111116")
    frame = hx("2c2c35")

    # body with rounded corners (radius ~4)
    for y in range(H):
        for x in range(W):
            dx = min(x, W - 1 - x)
            dy = min(y, H - 1 - y)
            if dx + dy >= 4 or (dx >= 2 and dy >= 2):
                px[y][x] = body
    # frame line
    for y in range(1, H - 1):
        for x in range(1, W - 1):
            dx = min(x, W - 1 - x)
            dy = min(y, H - 1 - y)
            if dx == 1 or dy == 1:
                if dx + dy >= 4:
                    px[y][x] = frame

    # screen: vertical navy -> teal gradient, x 4..59, y 4..123
    top = hx("142a63")
    bottom = hx("2fb2c4")
    for y in range(4, 124):
        t = (y - 4) / 119.0
        c = lerp(top, bottom, t)
        for x in range(4, 60):
            px[y][x] = c
    # diagonal sheen
    sheen = hx("ffffff", 26)
    for y in range(4, 124):
        for x in range(4, 60):
            if 0 <= (x + y) % 48 < 7 and x > 8:
                r, g, b, a = px[y][x]
                px[y][x] = (min(255, r + 18), min(255, g + 18), min(255, b + 20), 255)

    # notch
    rect(px, 24, 4, 39, 7, body)
    rect(px, 25, 8, 38, 8, hx("111116", 160))

    # status bar: time blob + battery + signal
    white = hx("f4f7fb")
    rect(px, 7, 6, 15, 7, white)                       # "time"
    rect(px, 50, 5, 56, 8, white)                      # battery outline
    rect(px, 51, 6, 54, 7, hx("39d05c"))               # battery fill
    px[6][57] = white
    px[7][57] = white
    for i, hgt in enumerate((1, 2, 3)):                # signal bars
        for k in range(hgt):
            px[8 - k][44 + i * 2] = white

    # app grid: 4 cols x 5 rows, 10x10 tiles, start (6,20), gap 4
    tile_colors = [
        "e8554d", "ef8f3c", "efc93f", "48b95f",
        "35c4b5", "3f8fe8", "6f63e0", "c45fd6",
        "e05f8e", "8a6cf0", "3fb5ef", "48c98a",
        "efb03c", "e8734d", "4a6cf0", "35b0c4",
        "5fd648", "d64f7a", "7a8ff0", "efd23c",
    ]
    gi = 0
    for row in range(5):
        for col in range(4):
            x0 = 6 + col * 14
            y0 = 20 + row * 14
            c = hx(tile_colors[gi]); gi += 1
            dark_c = tuple(max(0, v - 45) for v in c[:3]) + (255,)
            lite_c = tuple(min(255, v + 45) for v in c[:3]) + (255,)
            rect(px, x0, y0, x0 + 9, y0 + 9, c)
            # rounded corners: knock out corner pixels back to screen gradient
            for (cx, cy) in ((x0, y0), (x0 + 9, y0), (x0, y0 + 9), (x0 + 9, y0 + 9)):
                t = (cy - 4) / 119.0
                px[cy][cx] = lerp(top, bottom, t)
            rect(px, x0 + 1, y0 + 8, x0 + 8, y0 + 8, dark_c)   # bottom shade
            rect(px, x0 + 1, y0 + 1, x0 + 8, y0 + 1, lite_c)   # top light
            rect(px, x0 + 3, y0 + 3, x0 + 5, y0 + 5, hx("ffffff", 235))  # glyph

    # dock: translucent strip y 105..119
    for y in range(105, 120):
        for x in range(5, 59):
            r, g, b, a = px[y][x]
            px[y][x] = (min(255, r + 28), min(255, g + 28), min(255, b + 32), 255)
    dock_colors = ["48b95f", "3f8fe8", "e8554d", "efc93f"]
    for col in range(4):
        x0 = 8 + col * 13
        c = hx(dock_colors[col])
        rect(px, x0, 107, x0 + 9, 116, c)
        rect(px, x0 + 3, 110, x0 + 5, 112, hx("ffffff", 235))

    # home indicator
    rect(px, 24, 122, 39, 122, hx("f4f7fb", 220))
    return px


# ------------------------------------------------------------- gui app icons

def rounded_mask(px, size, r=4, bg=None):
    """Clears pixels outside a rounded-square of given corner radius."""
    for y in range(size):
        for x in range(size):
            dx = min(x, size - 1 - x)
            dy = min(y, size - 1 - y)
            if dx + dy < r and (dx < 2 or dy < 2) and dx + dy < r - 1:
                px[y][x] = CLEAR
    # simple corner rounding: cut 3 stair pixels
    for (cx, cy) in ((0, 0), (size - 1, 0), (0, size - 1), (size - 1, size - 1)):
        px[cy][cx] = CLEAR
    for (cx, cy) in ((1, 0), (0, 1), (size - 2, 0), (size - 1, 1),
                     (1, size - 1), (0, size - 2), (size - 2, size - 1), (size - 1, size - 2)):
        px[cy][cx] = CLEAR


def icon_spotify():
    s = 20
    px = canvas(s, s, hx("1ed760"))
    rounded_mask(px, s)
    dark = hx("10231a")
    # three curved sound waves
    for (y, x0, x1) in ((6, 5, 14), (9, 6, 13), (12, 7, 12)):
        rect(px, x0, y, x1, y, dark)
        px[y + 1][x0] = dark
        px[y + 1][x1] = dark
    return px


def icon_apple_music():
    s = 20
    px = canvas(s, s, hx("fafafc"))
    rounded_mask(px, s)
    red = hx("fa2d48")
    # double eighth-note
    rect(px, 6, 5, 13, 6, red)      # beam
    rect(px, 6, 7, 7, 12, red)      # left stem
    rect(px, 12, 7, 13, 12, red)    # right stem
    rect(px, 4, 12, 7, 14, red)     # left head
    rect(px, 10, 12, 13, 14, red)   # right head
    return px


def icon_messages():
    s = 20
    px = canvas(s, s, hx("34c759"))
    rounded_mask(px, s)
    w = hx("ffffff")
    rect(px, 4, 5, 15, 12, w)
    px[5][4] = hx("34c759"); px[5][15] = hx("34c759")  # round bubble corners
    px[12][4] = hx("34c759"); px[12][15] = hx("34c759")
    rect(px, 5, 13, 7, 13, w)  # tail
    px[14][5] = w
    # dots
    dot = hx("34c759")
    for x in (7, 10, 13):
        px[9][x] = dot
        px[9][x - 1] = dot
    return px


def icon_settings():
    s = 20
    px = canvas(s, s, hx("d5d8de"))
    rounded_mask(px, s)
    g = hx("53565e")
    # gear: ring + teeth + hole
    for (x0, y0, x1, y1) in ((7, 5, 12, 14), (5, 7, 14, 12)):
        rect(px, x0, y0, x1, y1, g)
    for (x, y) in ((9, 3), (10, 3), (9, 16), (10, 16), (3, 9), (3, 10), (16, 9), (16, 10),
                   (5, 5), (14, 5), (5, 14), (14, 14)):
        px[y][x] = g
    rect(px, 8, 8, 11, 11, hx("d5d8de"))
    return px


def icon_speaker():
    s = 20
    px = canvas(s, s, CLEAR)
    g = hx("f0f2f6")
    rect(px, 4, 8, 7, 12, g)          # box
    for i in range(4):                 # cone
        rect(px, 8 + i, 8 - i, 8 + i, 12 + i, g)
    for (x, y0, y1) in ((14, 7, 13), (16, 5, 15)):   # waves
        rect(px, x, y0, x, y1, g)
    return px


def icon_airpods_small():
    s = 20
    px = canvas(s, s, CLEAR)
    w = hx("f5f5f7")
    d = hx("c9c9d2")
    for x0 in (4, 12):
        rect(px, x0, 4, x0 + 3, 8, w)
        rect(px, x0 + 1, 9, x0 + 2, 15, w)
        px[15][x0 + 1] = d
        px[15][x0 + 2] = d
    return px


# ---------------------------------------------------------------- main

def main():
    tex = os.path.join(ASSETS, "textures")
    write_png(os.path.join(tex, "item", "phone.png"), phone_item())
    write_png(os.path.join(tex, "item", "airpods.png"), airpods_item())
    write_png(os.path.join(tex, "item", "circuit_board.png"), circuit_board_item())
    write_png(os.path.join(tex, "entity", "airpods.png"), airpods_entity())
    write_png(os.path.join(tex, "gui", "phone_front.png"), phone_front())
    write_png(os.path.join(tex, "gui", "icon_spotify.png"), icon_spotify())
    write_png(os.path.join(tex, "gui", "icon_apple_music.png"), icon_apple_music())
    write_png(os.path.join(tex, "gui", "icon_messages.png"), icon_messages())
    write_png(os.path.join(tex, "gui", "icon_settings.png"), icon_settings())
    write_png(os.path.join(tex, "gui", "icon_speaker.png"), icon_speaker())
    write_png(os.path.join(tex, "gui", "icon_airpods.png"), icon_airpods_small())
    write_png(os.path.join(ASSETS, "icon.png"), upscale(phone_item(), 8))
    print("done.")


if __name__ == "__main__":
    main()
