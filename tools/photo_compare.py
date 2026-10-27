"""Один промпт — кілька моделей: кандидати + контактний лист, щоб агент обрав найкращий.

Різні моделі краще вдаються різні кадри (Qwen — композиція й текст, Flux — фактура, Z-Image — «аматорське» фото),
тож для важливого кадру ганяємо той самий промпт через усі й дивимось контактний лист.

Використання:
    python tools/photo_compare.py run CASE P01 [--models qwen flux zimage] [--seeds 7 42] [--force]
    python tools/photo_compare.py run CASE P01 --prompt "..."        # спробувати інший промпт (у photo_jobs.json не пише)
    python tools/photo_compare.py run CASE P01 --set qwen.cfg=2.5 mp=1.0 --tag _lo  # інші налаштування поруч
    python tools/photo_compare.py pick CASE P01 zimage_s42            # обраний кандидат -> CASE/photos/P01.png + .jpg
Завдання — з CASE/photo_jobs.json (формат як у comfy_gen.py) або лише --prompt. Поле "prompts": {"flux": "..."}
у завданні перевизначає промпт для окремої моделі. Режим з референсом (ref) уміє лише Qwen — для таких кадрів comfy_gen.py.
Кандидати: CASE/photos/_cmp/<ID>/<model>_s<seed>.png, лист <ID>_sheet.jpg, промпти й час — _log.json (не комітяться).
"""
import argparse
import json
import random
import shutil
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import comfy_gen as cg

# Налаштування за замовчуванням — на слідування промпту й текст, а не на швидкість.
# Змінити на один запуск: --set qwen.cfg=2.5 flux.steps=8 mp=1.0
NEGATIVE = cg.NEGATIVE
SETTINGS = {
    "mp": 1.6,                                                  # більше пікселів на номер і вивіски
    "qwen": {"steps": 50, "cfg": 4.0, "negative": NEGATIVE},    # cfg 1.0 вимикає guidance і негатив
    "flux": {"steps": 20},                                      # дистильована: cfg не працює, лише кроки
    "zimage": {"steps": 16, "shift": 3.0},                      # дистильована турбо: те саме
}


def size_for(aspect: str, mp, multiple=32):
    w, h = (float(x) for x in aspect.split(" ")[0].split(":"))
    scale = (mp * 1e6 / (w * h)) ** 0.5
    return round(w * scale / multiple) * multiple, round(h * scale / multiple) * multiple


def save(images_src, prefix):
    return {"99": {"class_type": "SaveImage", "inputs": {"images": images_src, "filename_prefix": f"cmp/{prefix}"}}}


def qwen_graph(prompt, seed, w, h, prefix, o):
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_2.1_int8_convrot.safetensors", "weight_dtype": "default"}},
        "2": {"class_type": "QwenImage21Cache", "inputs": {"model": ["1", 0], "device": "auto", "dtype": "default"}},
        "3": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen3vl_8b_int8_convrot.safetensors", "type": "qwen_image", "device": "default"}},
        "4": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_2.1_vae_bf16.safetensors"}},
        "5": {"class_type": "TextEncodeQwenImage21", "inputs": {"clip": ["3", 0], "prompt": prompt, "negative_prompt": o["negative"], "resolution": 1024}},
        "6": {"class_type": "EmptyLatentImage", "inputs": {"width": w, "height": h, "batch_size": 1}},
        "7": {"class_type": "KSampler", "inputs": {"model": ["2", 0], "seed": seed, "steps": o["steps"], "cfg": o["cfg"], "sampler_name": "euler",
                                                    "scheduler": "simple", "positive": ["5", 0], "negative": ["5", 1],
                                                    "latent_image": ["6", 0], "denoise": 1.0}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["4", 0]}},
        **save(["8", 0], prefix),
    }


def flux_graph(prompt, seed, w, h, prefix, o):
    g = __import__("mapgen").flux_graph(prompt, seed, w, h, steps=o["steps"])
    g.pop("15")
    return {**g, **save(["14", 0], prefix)}


def zimage_graph(prompt, seed, w, h, prefix, o):
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "z_image_turbo_bf16.safetensors", "weight_dtype": "default"}},
        "2": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["1", 0], "shift": o["shift"]}},
        "3": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_3_4b.safetensors", "type": "lumina2", "device": "default"}},
        "4": {"class_type": "VAELoader", "inputs": {"vae_name": "ae.safetensors"}},
        "5": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["3", 0]}},
        "6": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["5", 0]}},
        "7": {"class_type": "EmptySD3LatentImage", "inputs": {"width": w, "height": h, "batch_size": 1}},
        "8": {"class_type": "KSampler", "inputs": {"model": ["2", 0], "seed": seed, "steps": o["steps"], "cfg": 1.0, "sampler_name": "res_multistep",
                                                    "scheduler": "simple", "positive": ["5", 0], "negative": ["6", 0],
                                                    "latent_image": ["7", 0], "denoise": 1.0}},
        "9": {"class_type": "VAEDecode", "inputs": {"samples": ["8", 0], "vae": ["4", 0]}},
        **save(["9", 0], prefix),
    }


MODELS = {"qwen": qwen_graph, "flux": flux_graph, "zimage": zimage_graph}


def contact_sheet(paths, dest, tile_h=640):
    tiles = []
    for p in paths:
        im = Image.open(p).convert("RGB")
        im = im.resize((round(im.width * tile_h / im.height), tile_h))
        d = ImageDraw.Draw(im)
        font = ImageFont.load_default(size=30)
        d.rectangle(d.textbbox((10, 8), p.stem, font=font), fill=(0, 0, 0))
        d.text((10, 8), p.stem, fill=(255, 230, 0), font=font)
        tiles.append(im)
    cols = min(3, len(tiles))
    rows = (len(tiles) + cols - 1) // cols
    tw = max(t.width for t in tiles)
    sheet = Image.new("RGB", (tw * cols, tile_h * rows), (40, 40, 40))
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % cols) * tw, (i // cols) * tile_h))
    sheet.save(dest, "JPEG", quality=85)


def load_job(case: Path, job_id, prompt):
    jobs_path = case / "photo_jobs.json"
    jobs = json.loads(jobs_path.read_text(encoding="utf-8")) if jobs_path.exists() else []
    job = next((j for j in jobs if j["id"] == job_id), {"id": job_id})
    if prompt:
        job = {**job, "prompt": prompt, "prompts": {}}
    if not job.get("prompt"):
        sys.exit(f"{job_id}: немає ні завдання в photo_jobs.json, ні --prompt")
    return job


def cmd_run(a):
    try:
        cg._get("/system_stats")
    except OSError:
        sys.exit("ComfyUI не запущено (http://127.0.0.1:8188)")
    case = Path(a.case)
    job = load_job(case, a.id, a.prompt)
    out = case / "photos" / "_cmp" / a.id
    out.mkdir(parents=True, exist_ok=True)
    log_path = out / "_log.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else {}
    settings = json.loads(json.dumps(SETTINGS))
    for kv in a.set or []:
        key, val = kv.split("=", 1)
        val = float(val) if val.replace(".", "", 1).isdigit() else val
        *path, last = key.split(".")
        node = settings
        for k in path:
            node = node[k]
        node[last] = int(val) if last == "steps" else val
    w, h = size_for(job.get("aspect", "3:2"), settings["mp"])
    seeds = a.seeds or [job.get("seed", random.randint(0, 2**31))]
    paths = []
    # Порядок: модель за моделлю — ComfyUI не перевантажує ваги між сідами однієї моделі.
    for model in a.models:
        prompt = job.get("prompts", {}).get(model, job["prompt"])
        for seed in seeds:
            name = f"{model}{a.tag}_s{seed}"
            opts = settings[model]
            dest = out / f"{name}.png"
            paths.append(dest)
            if dest.exists() and not a.force and log.get(name, {}).get("prompt") == prompt and log[name].get("settings") == opts:
                print(f"[skip] {name}")
                continue
            t0 = time.time()
            pid = cg._post("/prompt", {"prompt": MODELS[model](prompt, seed, w, h, f"{a.id}_{name}", opts)})["prompt_id"]
            print(f"[run ] {name} {w}x{h} {opts}", flush=True)
            try:
                outputs = cg.fetch_outputs(pid)
            except RuntimeError as e:
                print(f"[FAIL] {name}: {e}", flush=True)
                paths.pop()
                continue
            cg.download(outputs["99"]["images"][0], dest)
            log[name] = {"model": model, "seed": seed, "prompt": prompt, "settings": opts, "size": [w, h], "seconds": round(time.time() - t0)}
            log_path.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"[done] {name} {round(time.time() - t0)}s", flush=True)
    if paths:
        sheet = out.parent / f"{a.id}_sheet.jpg"
        contact_sheet(paths, sheet)
        print(f"[sheet] {sheet}")
        print("Кандидати:", *[str(p) for p in paths], sep="\n  ")


def cmd_pick(a):
    case = Path(a.case)
    src = case / "photos" / "_cmp" / a.id / f"{a.candidate}.png"
    if not src.exists():
        sys.exit(f"немає {src}")
    dest = case / "photos" / f"{a.id}.png"
    shutil.copyfile(src, dest)
    cg.save_web_jpg(dest)
    entry = json.loads((src.parent / "_log.json").read_text(encoding="utf-8")).get(a.candidate, {})
    log_path = case / "photos" / "_log.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else {}
    log[a.id] = {**entry, "picked_from": a.candidate}
    log_path.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[pick] {a.candidate} -> {dest} (+ .jpg)")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("case")
    r.add_argument("id")
    r.add_argument("--prompt")
    r.add_argument("--models", nargs="+", default=list(MODELS), choices=list(MODELS))
    r.add_argument("--seeds", nargs="+", type=int)
    r.add_argument("--force", action="store_true")
    r.add_argument("--set", nargs="+", metavar="KEY=VAL", help="перевизначити SETTINGS: qwen.cfg=2.5 flux.steps=8 mp=1.0")
    r.add_argument("--tag", default="", help="суфікс імені кандидата, щоб порівняти налаштування поруч: --tag _fast")
    k = sub.add_parser("pick")
    k.add_argument("case")
    k.add_argument("id")
    k.add_argument("candidate")
    a = p.parse_args()
    {"run": cmd_run, "pick": cmd_pick}[a.cmd](a)
