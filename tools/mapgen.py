"""Карти справи: база через локальний ComfyUI (Flux3 Klein) + підписи, маршрути й зони через PIL.

Використання (CASE — тека справи, напр. cases/stairs; завдання беруться з CASE/map_jobs.json):
    python tools/mapgen.py base CASE M01 [--seeds 5 17 23 42] [--force]   # кандидати + контактний лист
    python tools/mapgen.py render CASE M01 --seed 17                      # накласти CASE/maps/M01.overlay.json
Результат: CASE/photos/M01.png (оригінал, не комітиться) і M01.jpg (для гри).
Кандидати лежать у CASE/photos/_maps/ (не комітяться): M01_s<seed>.png і контактний лист M01_sheet.png.

Формат завдання (JSON-список):
    {"id": "M01", "style": "google", "layout": "A light blue river winds across the upper part …",
     "size": [1152, 768], "legend_panel": false}
`style` — google | cyberpunk | vintage | free (тоді свій опис стилю в `style_prompt`). `layout` — вільна проза англійською
БЕЗ координат і відсотків: що де лежить словами. Підписи, маршрути, зони, легенду малює tools/map_labels.py.
Докладно — docs/MAPS.md.
"""
import json
import random
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import comfy_gen as cg
import map_labels

DEFAULT_SEEDS = [5, 17, 23, 42]
NO_TEXT = "No text, no letters, no numbers, no labels. "

STYLES = {
    "google": ("Google Maps style digital map of a small city seen from directly above, completely flat 2D vector cartography, "
               "white streets with thin gray outlines, yellow-orange main roads, pale green parks, light blue water, beige city blocks "
               "with flat light gray building footprints, tiny flat colored round point-of-interest icons. "
               "Strictly flat 2D everywhere: no 3D buildings, no perspective, no realistic illustrations, no pictures of buildings, no shadows. "
               "No route lines. " + NO_TEXT),
    "cyberpunk": ("Cyberpunk city district map seen from directly above, very dark navy background, glowing neon cyan and magenta streets, "
                  "faint holographic grid overlay, tiny glowing rooftops, neon signage glow, futuristic data-map aesthetic, "
                  "like a hacker's tactical city map. " + NO_TEXT),
    "vintage": ("Vintage hand-illustrated tourist map of a small European town seen from above, cream aged paper, ink outlines and watercolor wash, "
                "tiny pictograms of houses and trees, compass rose, decorative border. " + NO_TEXT),
}
# Порожня панель під легенду (її вміст малює map_labels): однакова за змістом, різна за виглядом
PANEL = {
    "cyberpunk": "a plain dark empty rectangular panel with a thin neon border",
    "default": "a plain empty rectangular panel in the same style with a thin border",
}
PANEL_SENTENCE = "The map fills the left three quarters of the image; the right quarter is {panel}, left blank for a legend. "

ENCODER = "qwen3vl_4b_int8_convrot.safetensors"  # Flux3 Klein вимагає саме його (інші дають помилку розмірності)


def check_comfy():
    try:
        cg._get("/system_stats")
    except OSError:
        sys.exit("ComfyUI не запущено (http://127.0.0.1:8188)")


def flux_graph(prompt, seed, w, h, steps=8):
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "flux3-image-klein-int8-convrot.safetensors", "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": ENCODER, "type": "flux3", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "flux2-vae.safetensors"}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["2", 0]}},
        "8": {"class_type": "BasicGuider", "inputs": {"model": ["1", 0], "conditioning": ["6", 0]}},
        "9": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "10": {"class_type": "Flux2Scheduler", "inputs": {"steps": steps, "width": w, "height": h}},
        "11": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "12": {"class_type": "EmptyFlux2LatentImage", "inputs": {"width": w, "height": h, "batch_size": 1}},
        "13": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["11", 0], "guider": ["8", 0], "sampler": ["9", 0],
                                                                  "sigmas": ["10", 0], "latent_image": ["12", 0]}},
        "14": {"class_type": "VAEDecode", "inputs": {"samples": ["13", 0], "vae": ["3", 0]}},
        "15": {"class_type": "SaveImage", "inputs": {"images": ["14", 0], "filename_prefix": "maps/base"}},
    }


def build_prompt(job):
    style = job.get("style", "google")
    base = job["style_prompt"] + " " + NO_TEXT if style == "free" else STYLES[style]
    panel = ""
    if job.get("legend_panel"):
        panel = PANEL_SENTENCE.format(panel=PANEL.get(style, PANEL["default"]))
    return f"{base}{panel}Layout: {job['layout'].strip()}"


def contact_sheet(paths, dest):
    """Кандидати 2×N з підписаним сідом — щоб агент бачив, який сід який."""
    tiles = []
    for p in paths:
        im = Image.open(p).convert("RGB").resize((768, 512))
        ImageDraw.Draw(im).text((10, 8), p.stem.rsplit("_s", 1)[-1], fill=(255, 40, 40), font=ImageFont.load_default(size=28))
        tiles.append(im)
    rows = (len(tiles) + 1) // 2
    sheet = Image.new("RGB", (1536, 512 * rows))
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % 2) * 768, (i // 2) * 512))
    sheet.save(dest)


def cmd_base(case, job, seeds, force):
    check_comfy()
    out = case / "photos" / "_maps"
    out.mkdir(parents=True, exist_ok=True)
    w, h = job.get("size", [1152, 768])
    prompt = build_prompt(job)
    paths = []
    for seed in seeds:
        dest = out / f"{job['id']}_s{seed}.png"
        paths.append(dest)
        if dest.exists() and not force:
            print(f"[skip] {dest.name}")
            continue
        t0 = time.time()
        pid = cg._post("/prompt", {"prompt": flux_graph(prompt, seed, w, h)})["prompt_id"]
        cg.download(cg.fetch_outputs(pid)["15"]["images"][0], dest)
        print(f"[done] {dest.name} {round(time.time() - t0)}s", flush=True)
    sheet = out / f"{job['id']}_sheet.png"
    contact_sheet(paths, sheet)
    print(f"[sheet] {sheet}")


def cmd_render(case, job, seed, jobs_path, all_jobs):
    base = case / "photos" / "_maps" / f"{job['id']}_s{seed}.png"
    if not base.exists():
        sys.exit(f"немає бази {base} — спершу `base`")
    img = Image.open(base).convert("RGB")
    overlay = case / "maps" / f"{job['id']}.overlay.json"
    if overlay.exists():
        img = map_labels.apply(img, json.loads(overlay.read_text(encoding="utf-8")))
    else:
        print(f"[warn] немає {overlay} — карта без підписів")
    dest = case / "photos" / f"{job['id']}.png"
    img.save(dest)
    img.save(dest.with_suffix(".jpg"), "JPEG", quality=92, optimize=True, progressive=True)
    job["seed"] = seed  # щоб карту можна було відтворити
    jobs_path.write_text(json.dumps(all_jobs, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[done] {dest.with_suffix('.jpg')}")


def main(argv):
    if len(argv) < 3 or argv[0] not in ("base", "render"):
        sys.exit(__doc__)
    cmd, case, map_id, rest = argv[0], Path(argv[1]), argv[2], argv[3:]
    jobs_path = case / "map_jobs.json"
    all_jobs = json.loads(jobs_path.read_text(encoding="utf-8"))
    job = next((j for j in all_jobs if j["id"] == map_id), None)
    if job is None:
        sys.exit(f"немає завдання {map_id} у {jobs_path}")
    if cmd == "base":
        seeds = [int(s) for s in rest[rest.index("--seeds") + 1:] if s.isdigit()] if "--seeds" in rest else DEFAULT_SEEDS
        cmd_base(case, job, seeds or [random.randint(0, 2**31)], "--force" in rest)
    else:
        if "--seed" not in rest:
            sys.exit("render потребує --seed <сід обраного кандидата>")
        cmd_render(case, job, int(rest[rest.index("--seed") + 1]), jobs_path, all_jobs)


if __name__ == "__main__":
    main(sys.argv[1:])
