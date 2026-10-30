"""Make the land-only "front" cut-outs that sit in front of the clock.

For each wallpaper it finds the skyline (sky above, land below) with a
dynamic-programming path along the strongest bright-to-dark edge, refines
it at full resolution, and writes res/drawable-nodpi/wp_XX_front.webp:
the photo with the sky made transparent.

Usage: python3 depth.py 3 6 9 12 13      (photo numbers to process)

Check every result by eye: errors only matter where they overlap the
clock (centre third of the screen, upper half). Only list a cut-out in
res/values/wallpapers.xml when it covers roughly 5-40% of the digits -
more makes the time unreadable.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

RES = Path(__file__).resolve().parents[2] / "app/src/main/res/drawable-nodpi"


def coarse_skyline(im: Image.Image) -> np.ndarray:
    w, h = 480, 270
    a = np.asarray(im.convert("RGB").resize((w, h), Image.BILINEAR)).astype(np.float32) / 255
    lum = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    g = np.zeros_like(lum)
    g[2:-2] = (lum[:-4] + lum[1:-3]) / 2 - (lum[3:-1] + lum[4:]) / 2  # >0 when darker below
    cost = -(np.clip(g, 0, None) * 3 + np.abs(g) * 0.5)
    yy = np.arange(h)[:, None]
    cost += np.where((yy < 0.06 * h) | (yy > 0.85 * h), 5, 0)
    dp = np.full((h, w), np.inf, dtype=np.float32)
    back = np.zeros((h, w), dtype=np.int16)
    dp[:, 0] = cost[:, 0]
    for x in range(1, w):
        prev = dp[:, x - 1]
        best = np.full(h, np.inf)
        arg = np.zeros(h, dtype=np.int16)
        for d in range(-4, 5):
            sh = np.roll(prev, d)
            if d > 0:
                sh[:d] = np.inf
            if d < 0:
                sh[d:] = np.inf
            pen = sh + 0.002 * abs(d)
            m = pen < best
            best[m] = pen[m]
            arg[m] = (np.arange(h) - d)[m]
        dp[:, x] = best + cost[:, x]
        back[:, x] = arg
    y = np.zeros(w, dtype=int)
    y[-1] = int(np.argmin(dp[:, -1]))
    for x in range(w - 1, 0, -1):
        y[x - 1] = back[y[x], x]
    return np.interp(np.arange(1920), np.linspace(0, 1919, w), y * (1080 / h))


def front(n: int) -> None:
    name = f"wp_{n:02d}"
    im = Image.open(RES / f"{name}.webp").convert("RGB")
    y0 = coarse_skyline(im)
    lum8 = im.convert("L").filter(ImageFilter.GaussianBlur(1))
    lum = np.asarray(lum8, dtype=np.float32) / 255
    gy = np.zeros_like(lum)
    gy[2:-2] = lum[:-4] - lum[4:]
    y = np.zeros(1920)
    for x in range(1920):
        c = int(round(y0[x]))
        lo, hi = max(2, c - 8), min(1077, c + 8)
        y[x] = lo + np.argmax(gy[lo:hi, x])
    y = np.array([np.median(y[max(0, x - 3):x + 4]) for x in range(1920)])
    rows = np.arange(1080)[:, None]
    alpha = np.clip(rows - (y[None, :] - 0.5) + 0.5, 0, 1)
    rgba = np.dstack([np.asarray(im), (alpha * 255).astype(np.uint8)])
    Image.fromarray(rgba, "RGBA").save(RES / f"{name}_front.webp", "WEBP", quality=88, method=6)
    print("wrote", f"{name}_front.webp")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        front(int(arg))
