"""Накладання підписів на згенеровану карту (PIL): текст завжди точний, стиль один на всю карту.

Використання:
    python tools/map_labels.py map.png labels.json out.png

labels.json (координати — пікселі картинки, центр підпису; автор їх знімає з готового зображення):
    {"style": {"font": "georgiab.ttf", "size": 22, "fill": [60, 40, 28], "halo": [245, 236, 215], "halo_width": 4,
               "spacing": 0},
     "labels": [
       {"text": "Вокзал", "x": 620, "y": 700},
       {"text": "Озеро Тихе", "x": 915, "y": 568, "size": 24, "angle": 5},
       {"text": "Річка Липа", "path": [[330, 258], [400, 232], [470, 222], [540, 232]], "spacing": 4}
     ]}
`angle` — градуси проти годинникової; `path` — ламана, уздовж якої розставляються літери (центрується по довжині).
Додатково: "markers": [{"n": 1, "x": .., "y": ..}] — круглі номери на карті, "legend": {"x": .., "y": .., "title": "..",
"items": [{"n": 1, "text": ".."}, {"color": [0, 220, 255], "text": "Канал"}], "row": 34} — список «номер — назва» або
«колір лінії — назва». Кольори маркерів: style.marker_fill/outline/text.
Маршрути й зони (малюються під підписами): "routes": [{"points": [[x, y], ...], "color": [26, 115, 232], "width": 8,
"dash": [16, 10], "start": "dot", "end": "pin"}], "zones": [{"points": [...], "color": [217, 48, 37], "fill_alpha": 40,
"dash": [14, 9], "width": 4}] — лінія проходить через точки згладженою кривою; вимкнути піни: "start": null / "end": null.
"""
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONT_DIRS = [Path(r"C:\Windows\Fonts"), Path("/usr/share/fonts/truetype/dejavu")]
FALLBACKS = ["georgiab.ttf", "timesbd.ttf", "DejaVuSerif-Bold.ttf"]
DEFAULT = {"font": "georgiab.ttf", "size": 22, "fill": [60, 40, 28], "halo": [245, 236, 215], "halo_width": 4, "spacing": 0,
           "marker_r": 14, "marker_fill": [60, 40, 28], "marker_outline": [245, 236, 215], "marker_text": [245, 236, 215]}


def load_font(name, size):
    for n in [name] + FALLBACKS:
        for d in FONT_DIRS:
            if (d / n).exists():
                return ImageFont.truetype(str(d / n), size)
    raise FileNotFoundError(f"немає шрифту {name}")


def glyph(ch, font, st, angle):
    """Літера з ореолом, повернута на angle градусів, як RGBA-накладка."""
    hw = st["halo_width"]
    w, h = int(font.getlength(ch)) + 4 * hw + 4, font.size * 2 + 4 * hw
    patch = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(patch)
    d.text((w / 2, h / 2), ch, font=font, anchor="mm", fill=tuple(st["fill"]) + (255,),
           stroke_width=hw, stroke_fill=tuple(st["halo"]) + (235,))
    return patch.rotate(angle, resample=Image.BICUBIC, expand=True)


def paste_center(layer, patch, x, y):
    layer.alpha_composite(patch, (round(x - patch.width / 2), round(y - patch.height / 2)))


def draw_label(layer, lab, st):
    st = {**st, **{k: lab[k] for k in ("size", "fill", "halo", "halo_width", "spacing", "font") if k in lab}}
    font = load_font(st["font"], st["size"])
    text = lab["text"]
    if "path" not in lab:
        paste_center(layer, glyph(text, font, st, lab.get("angle", 0)), lab["x"], lab["y"])
        return
    pts = [tuple(p) for p in lab["path"]]
    if pts[0][0] > pts[-1][0]:
        pts.reverse()  # текст читається зліва направо
    seg = [math.dist(a, b) for a, b in zip(pts, pts[1:])]
    advance = [font.getlength(c) + st["spacing"] for c in text]
    start = (sum(seg) - sum(advance)) / 2
    if start < 0:
        print(f"[warn] «{text}» довший за лінію на {-2 * start:.0f} px")
    pos = start
    for ch, adv in zip(text, advance):
        mid = pos + adv / 2
        pos += adv
        d, i = mid, 0
        while i < len(seg) - 1 and d > seg[i]:
            d -= seg[i]
            i += 1
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        t = max(0.0, min(1.0, d / seg[i]))
        angle = -math.degrees(math.atan2(y1 - y0, x1 - x0))
        if ch != " ":
            paste_center(layer, glyph(ch, font, st, angle), x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)


def draw_badge(d, n, x, y, st):
    """Круглий номерний маркер із центром у (x, y)."""
    r = st["marker_r"]
    d.ellipse((x - r, y - r, x + r, y + r), fill=tuple(st["marker_fill"]), outline=tuple(st["marker_outline"]), width=2)
    d.text((x, y), str(n), font=load_font(st["font"], round(r * 1.2)), anchor="mm", fill=tuple(st["marker_text"]))


def draw_legend(d, lg, st):
    """Легенда: заголовок і рядки «номер — назва»; (x, y) — верхній лівий кут, row — крок рядків."""
    font = load_font(st["font"], lg.get("size", st["size"]))
    x, y, row = lg["x"], lg["y"], lg.get("row", st["marker_r"] * 2 + 12)
    if "panel" in lg:  # біла картка під легендою: {"w": .., "h": .., "pad": 14, "fill": [r, g, b, a]}
        p, pad = lg["panel"], lg["panel"].get("pad", 14)
        d.rounded_rectangle((x - pad + 2, y - pad + 3, x - pad + p["w"] + 2, y - pad + p["h"] + 3), radius=10, fill=(0, 0, 0, 50))
        d.rounded_rectangle((x - pad, y - pad, x - pad + p["w"], y - pad + p["h"]), radius=10, fill=tuple(p.get("fill", [255, 255, 255, 240])))
    if "title" in lg:
        d.text((x, y), lg["title"], font=load_font(st["font"], lg.get("title_size", st["size"] + 4)), anchor="la", fill=tuple(st["fill"]))
        y += row + 6
    for i, item in enumerate(lg["items"]):
        cy = y + i * row + st["marker_r"]
        if "n" in item:
            draw_badge(d, item["n"], x + st["marker_r"], cy, st)
        elif item.get("box"):  # умовний знак зони: пунктирний прямокутник із прозорою заливкою
            c, r = tuple(item["color"]), st["marker_r"]
            box = (x + 1, cy - r * .55, x + r * 2 - 1, cy + r * .55)
            d.rectangle(box, fill=c + (item.get("fill_alpha", 40),))
            for run in dash_runs([(box[0], box[1]), (box[2], box[1]), (box[2], box[3]), (box[0], box[3]), (box[0], box[1])], 6, 4):
                d.line(run, fill=c + (255,), width=2)
        else:  # умовний знак: кольорова лінія, суцільна або пунктирна
            c = tuple(item["color"]) + (255,)
            pts = [(x + 2, cy), (x + st["marker_r"] * 2 - 2, cy)]
            for run in (dash_runs(pts, *item["dash"]) if item.get("dash") else [pts]):
                d.line(run, fill=c, width=item.get("width", 5))
        d.text((x + st["marker_r"] * 2 + 12, cy), item["text"], font=font, anchor="lm", fill=tuple(st["fill"]))


SS = 3  # суперсемплінг векторних шарів


def spline(pts, n=14, closed=False):
    """Згладжена крива Катмулла—Рома через задані точки."""
    p = list(pts)
    p = ([p[-1]] + p + [p[0], p[1]]) if closed else ([p[0]] + p + [p[-1]])
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for j in range(n):
            t = j / n
            out.append(tuple(.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (-a + 3 * b - 3 * c + d) * t ** 3)
                             for a, b, c, d in zip(p0, p1, p2, p3)))
    out.append(tuple(pts[0]) if closed else tuple(pts[-1]))
    return out


def dash_runs(pts, on, off):
    """Розбиває ламану на штрихи довжиною on із проміжками off."""
    runs, cur, drawing, left = [], [pts[0]], True, on
    for a, b in zip(pts, pts[1:]):
        length, pos = math.dist(a, b), 0.0
        while length - pos > 1e-9:
            step = min(left, length - pos)
            t = (pos + step) / length
            p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            if drawing:
                cur.append(p)
            pos += step
            left -= step
            if left <= 1e-9:
                if drawing:
                    runs.append(cur)
                    cur = []
                else:
                    cur = [p]
                drawing = not drawing
                left = on if drawing else off
    if drawing and len(cur) > 1:
        runs.append(cur)
    return runs


def stroke(d, pts, color, width, dash=None):
    """Лінія з круглими кінцями; dash=[штрих, проміжок] у пікселях картинки."""
    runs = dash_runs(pts, dash[0] * SS, dash[1] * SS) if dash else [pts]
    for run in runs:
        d.line(run, fill=color, width=width, joint="curve")
        for x, y in (run[0], run[-1]):
            d.ellipse((x - width / 2, y - width / 2, x + width / 2, y + width / 2), fill=color)


def draw_pin(d, x, y, kind, color):
    """Старт — кружок із білою серединою; фініш — шпилька в стилі карт, вістря в (x, y)."""
    r = 11 * SS
    if kind == "dot":
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 255), outline=color, width=int(r * .45))
        return
    cy = y - 2.1 * r
    d.polygon([(x, y), (x - r * .9, cy + r * .45), (x + r * .9, cy + r * .45)], fill=color)
    d.ellipse((x - r, cy - r, x + r, cy + r), fill=color)
    d.ellipse((x - r * .42, cy - r * .42, x + r * .42, cy + r * .42), fill=(255, 255, 255, 255))


def vector_layer(size, spec):
    """Зони та маршрути: пунктирні/суцільні лінії поверх карти, малюються у збільшеному розмірі й зменшуються."""
    big = Image.new("RGBA", (size[0] * SS, size[1] * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    sc = lambda pts: [(x * SS, y * SS) for x, y in pts]
    for z in spec.get("zones", []):
        pts = spline(sc(z["points"]), closed=True)
        col = tuple(z.get("color", [217, 48, 37]))
        if z.get("fill_alpha", 0):
            fill = Image.new("RGBA", big.size, (0, 0, 0, 0))
            ImageDraw.Draw(fill).polygon(pts, fill=col + (z["fill_alpha"],))
            big = Image.alpha_composite(big, fill)
            d = ImageDraw.Draw(big)
        stroke(d, pts + [pts[0]], col + (255,), z.get("width", 4) * SS, z.get("dash", [14, 9]))
    for r in spec.get("routes", []):
        pts = spline(sc(r["points"])) if r.get("smooth", True) else sc(r["points"])
        col = tuple(r.get("color", [26, 115, 232]))
        w = r.get("width", 8) * SS
        if r.get("casing", True):
            stroke(d, pts, (255, 255, 255, 255), w + 4 * SS, r.get("dash"))
        stroke(d, pts, col + (255,), w, r.get("dash"))
        if r.get("start", "dot"):
            draw_pin(d, *pts[0], "dot", col + (255,))
        if r.get("end", "pin"):
            draw_pin(d, *pts[-1], "pin", tuple(r.get("end_color", [234, 67, 53])) + (255,))
    return big.resize(size, Image.LANCZOS)


def apply(img, spec):
    st = {**DEFAULT, **spec.get("style", {})}
    base = img.convert("RGBA")
    if spec.get("zones") or spec.get("routes"):
        base = Image.alpha_composite(base, vector_layer(base.size, spec))
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    for lab in spec.get("labels", []):
        draw_label(layer, lab, st)
    d = ImageDraw.Draw(layer)
    for m in spec.get("markers", []):
        draw_badge(d, m["n"], m["x"], m["y"], st)
    if "legend" in spec:
        draw_legend(d, spec["legend"], st)
    return Image.alpha_composite(base, layer).convert("RGB")


if __name__ == "__main__":
    src, spec_path, dest = sys.argv[1:4]
    spec = json.loads(Path(spec_path).read_text(encoding="utf-8"))
    apply(Image.open(src), spec).save(dest)
    print(f"[done] {dest}")
