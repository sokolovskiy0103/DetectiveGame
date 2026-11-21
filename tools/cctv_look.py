"""Вигляд кадру дешевого реєстратора 2000-х: PNG лишається чистим оригіналом (референс), JPG для гри — деградований.

Модель малює занадто чисто, тож 352×288, шум, стиск і вицвілі кольори накладаємо детерміновано — усі кадри
одного реєстратора виглядають однаково. Запускати після comfy_gen.py, бо той перезаписує JPG.

    python tools/cctv_look.py cases/extra-hour P17 P18 P19
"""
import io
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

SIZE = (352, 288)   # CIF
OUT_W = 1056        # ×3, щоб на екрані лишались великі «пікселі», а не мило


def cctv(im: Image.Image, seed: int) -> Image.Image:
    im = ImageEnhance.Color(im.convert("RGB")).enhance(0.7)
    # Дешева камера витягує тіні: підіймаємо їх, інакше після стиску кадр стає чорним
    im = im.point(lambda v: round(255 * (v / 255) ** 0.72))
    im = ImageEnhance.Contrast(im).enhance(0.9)

    small = im.resize(SIZE, Image.BILINEAR).filter(ImageFilter.GaussianBlur(0.25))
    a = np.asarray(small).astype(np.float32)
    rng = np.random.default_rng(seed)
    # Шум сильніший у тінях, як на дешевій матриці
    luma = a.mean(axis=2, keepdims=True) / 255
    a += rng.normal(0, 1, a.shape[:2] + (1,)) * (7 - 3 * luma)
    a += rng.normal(0, 2, a.shape)
    # Парні/непарні поля дають рядкове тремтіння яскравості
    a[::2] *= 0.965
    small = Image.fromarray(a.clip(0, 255).astype(np.uint8))

    buf = io.BytesIO()
    small.save(buf, "JPEG", quality=62, subsampling=2)
    small = Image.open(buf).convert("RGB")
    h = round(OUT_W * im.height / im.width)
    return small.resize((OUT_W, h), Image.LANCZOS)


def main():
    case, ids = Path(sys.argv[1]), sys.argv[2:]
    for id_ in ids:
        src = case / "photos" / f"{id_}.png"
        random.seed(id_)
        cctv(Image.open(src), random.randrange(2**31)).save(src.with_suffix(".jpg"), "JPEG", quality=82, optimize=True)
        print(f"[cctv] {id_}")


if __name__ == "__main__":
    main()
