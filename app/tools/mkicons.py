#!/usr/bin/env python3
"""Generate the app's PNG resources with no third-party dependencies.

Writes:
  res/drawable/ic_launcher.png   192x192  launcher icon
  res/drawable/banner.png        320x180  leanback launcher banner
  res/drawable/ic_stat.png        48x48   notification small icon (white on transparent)

Pure stdlib: struct + zlib is all a PNG needs.
"""
import math
import os
import struct
import sys
import zlib

BG = (0x10, 0x10, 0x14, 255)
PANEL = (0x1B, 0x1B, 0x22, 255)
ACCENT = (0x4F, 0xC3, 0xF7, 255)
WARM = (0xFF, 0xB3, 0x00, 255)
WHITE = (0xFF, 0xFF, 0xFF, 255)


def write_png(path, width, height, pixels):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        row = pixels[y]
        for x in range(width):
            r, g, b, a = row[x]
            raw += bytes((r, g, b, a))
    comp = zlib.compress(bytes(raw), 9)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", comp)
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


def blank(w, h, colour):
    return [[colour for _ in range(w)] for _ in range(h)]


def over(dst, src):
    """src over dst, both straight RGBA."""
    sa = src[3] / 255.0
    if sa <= 0:
        return dst
    if sa >= 1:
        return src
    da = dst[3] / 255.0
    out_a = sa + da * (1 - sa)
    if out_a <= 0:
        return (0, 0, 0, 0)
    out = []
    for i in range(3):
        out.append(int(round((src[i] * sa + dst[i] * da * (1 - sa)) / out_a)))
    out.append(int(round(out_a * 255)))
    return tuple(out)


def fan(px, w, h, cx, cy, radius, colour, blades=5, supersample=3):
    """Draw a stylised impeller, antialiased by supersampling."""
    for y in range(h):
        for x in range(w):
            hits = 0
            for sy in range(supersample):
                for sx in range(supersample):
                    fx = x + (sx + 0.5) / supersample - cx
                    fy = y + (sy + 0.5) / supersample - cy
                    r = math.hypot(fx, fy)
                    if r > radius or r < 1e-6:
                        continue
                    theta = math.atan2(fy, fx)
                    # blades sweep back as the radius grows
                    swirl = theta + 2.1 * (r / radius)
                    if r < radius * 0.20:
                        hits += 1
                    elif math.sin(blades * swirl) > 0.35 and r < radius * 0.97:
                        hits += 1
            if hits:
                a = int(round(255 * hits / (supersample * supersample)))
                px[y][x] = over(px[y][x], (colour[0], colour[1], colour[2], a))


def rounded_rect(px, w, h, x0, y0, x1, y1, radius, colour, supersample=3):
    for y in range(h):
        for x in range(w):
            hits = 0
            for sy in range(supersample):
                for sx in range(supersample):
                    fx = x + (sx + 0.5) / supersample
                    fy = y + (sy + 0.5) / supersample
                    if fx < x0 or fx > x1 or fy < y0 or fy > y1:
                        continue
                    dx = max(x0 + radius - fx, fx - (x1 - radius), 0)
                    dy = max(y0 + radius - fy, fy - (y1 - radius), 0)
                    if dx * dx + dy * dy <= radius * radius:
                        hits += 1
            if hits:
                a = int(round(255 * hits / (supersample * supersample)))
                px[y][x] = over(px[y][x], (colour[0], colour[1], colour[2], a))


def bar(px, w, h, x0, y0, x1, y1, colour):
    for y in range(max(0, int(y0)), min(h, int(y1))):
        for x in range(max(0, int(x0)), min(w, int(x1))):
            px[y][x] = over(px[y][x], colour)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "res/drawable"
    os.makedirs(out, exist_ok=True)

    # --- launcher icon ---
    n = 192
    px = blank(n, n, (0, 0, 0, 0))
    rounded_rect(px, n, n, 6, 6, n - 6, n - 6, 34, BG)
    rounded_rect(px, n, n, 6, 6, n - 6, n - 6, 34, (0x1B, 0x1B, 0x22, 255))
    fan(px, n, n, n / 2.0, n / 2.0 - 6, n * 0.36, ACCENT)
    # a temperature bar along the bottom, cool to warm
    for x in range(28, n - 28):
        f = (x - 28) / float(n - 56)
        col = (int(0x4F + f * (0xFF - 0x4F)),
               int(0xC3 + f * (0xB3 - 0xC3)),
               int(0xF7 + f * (0x00 - 0xF7)), 255)
        bar(px, n, n, x, n - 44, x + 1, n - 34, col)
    print("ic_launcher.png", write_png(os.path.join(out, "ic_launcher.png"), n, n, px), "B")

    # --- banner (leanback launchers want 320x180) ---
    w, hgt = 320, 180
    px = blank(w, hgt, BG)
    fan(px, w, hgt, 62, hgt / 2.0, 52, ACCENT)
    # a stylised rising curve to the right of the impeller
    prev = None
    for i in range(0, 190):
        x = 128 + i
        f = i / 189.0
        y = hgt - 40 - int(78 * (f ** 1.7))
        for t in range(-2, 3):
            yy = y + t
            if 0 <= yy < hgt and 0 <= x < w:
                px[yy][x] = over(px[yy][x], WARM if f > 0.62 else ACCENT)
        if prev is not None and abs(y - prev) > 1:
            step = 1 if y > prev else -1
            for yy in range(prev, y, step):
                for t in range(-2, 3):
                    if 0 <= yy + t < hgt:
                        px[yy + t][x] = over(px[yy + t][x],
                                             WARM if f > 0.62 else ACCENT)
        prev = y
    bar(px, w, hgt, 126, hgt - 38, w - 4, hgt - 36, (0x3A, 0x3A, 0x46, 255))
    print("banner.png", write_png(os.path.join(out, "banner.png"), w, hgt, px), "B")

    # --- notification small icon: white silhouette on transparent ---
    n = 48
    px = blank(n, n, (0, 0, 0, 0))
    fan(px, n, n, n / 2.0, n / 2.0, n * 0.44, WHITE, blades=5, supersample=4)
    print("ic_stat.png", write_png(os.path.join(out, "ic_stat.png"), n, n, px), "B")


if __name__ == "__main__":
    main()
