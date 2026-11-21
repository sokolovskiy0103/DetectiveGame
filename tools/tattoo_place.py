"""Ставить тату з base/R04_v1.png на кадр: вісь уздовж руки, купол — до точки WRIST.

    python tools/tattoo_place.py SRC DST CX CY WX WY SCALE
CX CY — центр тату на передпліччі, WX WY — зап'ясток.
"""
import math
import sys
import numpy as np
from PIL import Image, ImageFilter

ART = "cases/extra-hour/photos/base/R04_v1.png"
BOX = (160, 772, 385, 968)
LEAN = 17  # малюнок у джерелі нахилений проти годинникової

src, dst = sys.argv[1:3]
cx, cy, wx, wy = map(int, sys.argv[3:7])
scale = float(sys.argv[7])
c = Image.open(ART).convert("L").crop(BOX)
g = np.asarray(c).astype(float)
bg = np.maximum(np.asarray(c.filter(ImageFilter.GaussianBlur(45))).astype(float), np.percentile(g, 85))
t = np.clip((0.80 - g / bg) / 0.40, 0, 1)
alpha = Image.fromarray((t * t * (3 - 2 * t) * 255).astype(np.uint8))
angle = math.degrees(math.atan2(-(wx - cx), -(wy - cy))) - LEAN  # проти годинникової від «вгору»
alpha = alpha.resize((int(alpha.width * scale), int(alpha.height * scale)), Image.LANCZOS)
alpha = alpha.rotate(angle, resample=Image.BICUBIC, expand=True, fillcolor=0)
base = np.asarray(Image.open(src).convert("RGB")).astype(float)
l = (np.asarray(alpha).astype(float) / 255)[..., None] * 0.82
x0, y0 = cx - l.shape[1] // 2, cy - l.shape[0] // 2
reg = base[y0:y0 + l.shape[0], x0:x0 + l.shape[1]]
base[y0:y0 + l.shape[0], x0:x0 + l.shape[1]] = reg * (1 - l) + np.array([46, 44, 48.0]) * l
Image.fromarray(base.astype(np.uint8)).save(dst)
print("angle", round(angle, 1))
