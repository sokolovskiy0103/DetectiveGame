"""Перекодовує всі PNG-оригінали справи в JPG ≤170 КБ (ліміт relay — 180 КБ).

    python tools/shrink_photos.py cases/stairs
Корисно, якщо фото зроблено не через comfy_gen.py (він це вміє сам).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from comfy_gen import save_web_jpg  # noqa: E402

for png in sorted((Path(sys.argv[1]) / "photos").glob("*.png")):
    save_web_jpg(png)
    print(png.stem, png.with_suffix(".jpg").stat().st_size // 1024, "КБ")
