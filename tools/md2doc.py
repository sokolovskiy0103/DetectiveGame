"""Конвертує протоколи допитів (CASE/story/documents/interrogations/*.md) у HTML-бланки CASE/documents/<ID>.html.

ID документа — частина імені файлу до першого «_» (A09_poiasnennia_hnativ.md → A09.html).
    python tools/md2doc.py cases/stairs
Потрібен пакет markdown (pip install -r tools/requirements.txt). Шапки й портрети нижче — для справи «Сходи»;
для нової справи змініть HEADER і PORTRAITS (або не користуйтесь скриптом і пишіть HTML вручну).
"""
import sys
import re
from pathlib import Path

import markdown

CASE = Path(sys.argv[1] if len(sys.argv) > 1 else "cases/stairs")
SRC = CASE / "story" / "documents" / "interrogations"
DST = CASE / "documents"

HEADER = """<div class="org-header">
  Національна поліція України<br>
  Головне управління Національної поліції у Львівській області<br>
  Галицький відділ поліції
  <div class="org-sub">Слідчий відділ</div>
</div>"""

PATROL_HEADER = """<div class="org-header">
  Національна поліція України<br>
  Управління патрульної поліції у Львівській області
</div>"""

# Фото особи в першому поясненні / допиті (портрети з assets/photos)
PORTRAITS = {"A07": "P05", "A09": "P02", "A11": "P06", "A12": "P03", "B03": "P07", "B10": "P04",
             "C02": "P09", "C05": "P08"}

PHOTO_BLOCK = """<figure class="person-photo"><img src="../photos/{photo}.jpg" alt="Фото особи">
<figcaption>Фото особи, що надає пояснення</figcaption></figure>"""

TEMPLATE = """<!doctype html>
<html lang="uk">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<link rel="stylesheet" href="_base.css">
<style>
  .md h1 {{ text-align: center; text-transform: uppercase; font-size: 14pt; margin: 4mm 0 5mm; }}
  .md h2 {{ font-size: 12.5pt; margin: 6mm 0 2mm; }}
  .md h3 {{ font-size: 12pt; margin: 4mm 0 2mm; }}
  .md hr {{ border: none; border-top: 1px solid #bbb; margin: 5mm 0; }}
  .md blockquote {{ margin: 3mm 0 3mm 6mm; padding-left: 4mm; border-left: 2px solid #bbb; color: #333; }}
  .md ul {{ margin: 0 0 3mm; padding-left: 6mm; }}
  .md em {{ color: #444; }}
  .md p strong:first-child {{ font-weight: bold; }}
  .md .sig-line {{ font-family: var(--font-hand); color: var(--blue-ink); font-size: 14pt; }}
  .person-photo {{ float: right; width: 34mm; margin: 0 0 4mm 6mm; }}
  .person-photo img {{ width: 100%; display: block; border: 1px solid #555; filter: grayscale(.15); }}
  .person-photo figcaption {{ font-size: 8.5pt; color: #555; text-align: center; margin-top: 1mm; }}
</style>
</head>
<body>
<section class="page md">
{header}
{body}
</section>
</body>
</html>
"""


def convert(md_path: Path) -> Path:
    text = md_path.read_text(encoding="utf-8")
    # Кожен рядок «**Поле:** значення» підряд — окремий рядок (markdown склеїв би їх в один абзац).
    text = re.sub(r"(?m)^(\*\*[^*\n]+:\*\*.*)$", r"\1  ", text)
    body = markdown.markdown(text, extensions=["tables", "sane_lists"])
    body = body.replace("/підпис/", '<span class="sig-line">/підпис/</span>')
    title_match = re.search(r"<h1>(.*?)</h1>", body)
    doc_id = md_path.stem.split("_")[0]
    title = f"{doc_id} — {re.sub('<[^>]+>', '', title_match.group(1)) if title_match else md_path.stem}"
    header = PATROL_HEADER if "komendantska" in md_path.stem else HEADER
    if PORTRAITS.get(doc_id):
        body = body.replace("</h1>", "</h1>\n" + PHOTO_BLOCK.format(photo=PORTRAITS[doc_id]), 1)
    out = DST / f"{doc_id}.html"
    out.write_text(TEMPLATE.format(title=title, header=header, body=body), encoding="utf-8")
    return out


if __name__ == "__main__":
    DST.mkdir(parents=True, exist_ok=True)
    for p in sorted(SRC.glob("*.md")):
        print(convert(p).name)
