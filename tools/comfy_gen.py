"""Пакетна генерація фото справи через локальний ComfyUI (Qwen-Image 2.1 [+ SeedVR2]).

Використання (CASE — тека справи, напр. cases/stairs; завдання беруться з CASE/photo_jobs.json):
    python tools/comfy_gen.py cases/stairs            # усі завдання, яких ще немає
    python tools/comfy_gen.py cases/stairs P01 S04    # лише вказані ID
    python tools/comfy_gen.py cases/stairs --force P02
Результат: CASE/photos/<ID>.png (оригінал, не комітиться) і <ID>.jpg (для гри).

Формат завдання (JSON-список):
    {"id": "P01", "prompt": "...", "aspect": "3:4 (Portrait Standard)", "seed": 1234,
     "ref": "P01"}   # необов'язково: ID вже згенерованого фото як референс обличчя
"""
import json
import random
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

COMFY = "http://127.0.0.1:8188"
OUT_DIR = BASE_DIR = None  # задаються з аргументу в main()
MP = 1.0                   # розмір кадру в мегапікселях — одразу достатній для екрана телефона


def _post(path, data):
    req = urllib.request.Request(COMFY + path, data=json.dumps(data).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req))


def _get(path):
    return json.load(urllib.request.urlopen(COMFY + path))


def upload_image(path: Path) -> str:
    boundary = "----comfy" + str(random.randint(0, 10**9))
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{path.name}\"\r\n"
        f"Content-Type: image/png\r\n\r\n"
    ).encode() + path.read_bytes() + (
        f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n--{boundary}--\r\n"
    ).encode()
    req = urllib.request.Request(COMFY + "/upload/image", data=body, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    return json.load(urllib.request.urlopen(req))["name"]


def make_canvas(aspect: str) -> Path:
    """Сіре полотно ~1 МП заданих пропорцій ("16:9") — задає розмір кадру в режимі з референсом."""
    from PIL import Image
    w, h = (int(x) for x in aspect.split(":"))
    scale = (MP * 1e6 / (w * h)) ** 0.5
    path = OUT_DIR / "_tmp" / f"canvas_{w}x{h}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        Image.new("RGB", (round(w * scale / 32) * 32, round(h * scale / 32) * 32), (128, 128, 128)).save(path)
    return path


def save_web_jpg(png: Path):
    """JPG для гри з PNG-оригіналу. PNG лишається оригіналом і референсом."""
    from PIL import Image
    Image.open(png).convert("RGB").save(png.with_suffix(".jpg"), "JPEG", quality=85, optimize=True, progressive=True)


def upscale_chain(image_src, prefix):
    """SeedVR2 ×2, 1 крок, корекція кольору lab — як у перевіреному workflow."""
    return {
        "12": {"class_type": "UNETLoader", "inputs": {"unet_name": "seedvr2_7b_int8_convrot.safetensors", "weight_dtype": "default"}},
        "13": {"class_type": "VAELoader", "inputs": {"vae_name": "seedvr2_ema_vae_fp16.safetensors"}},
        "14": {"class_type": "ResizeImageMaskNode", "inputs": {"input": image_src, "resize_type": "scale by multiplier",
                                                                "resize_type.multiplier": 2.0, "scale_method": "lanczos"}},
        "15": {"class_type": "SeedVR2Preprocess", "inputs": {"resized_images": ["14", 0]}},
        "16": {"class_type": "VAEEncodeTiled", "inputs": {"pixels": ["15", 0], "vae": ["13", 0], "tile_size": 512, "overlap": 128,
                                                           "temporal_size": 4096, "temporal_overlap": 8}},
        "17": {"class_type": "SeedVR2Conditioning", "inputs": {"model": ["12", 0], "vae_conditioning": ["16", 0]}},
        "18": {"class_type": "KSampler", "inputs": {"model": ["12", 0], "seed": 42, "steps": 1, "cfg": 1.0, "sampler_name": "euler",
                                                     "scheduler": "simple", "positive": ["17", 0], "negative": ["17", 1],
                                                     "latent_image": ["16", 0], "denoise": 1.0}},
        "19": {"class_type": "VAEDecodeTiled", "inputs": {"samples": ["18", 0], "vae": ["13", 0], "tile_size": 512, "overlap": 128,
                                                           "temporal_size": 4096, "temporal_overlap": 8}},
        "20": {"class_type": "SeedVR2PostProcessing", "inputs": {"images": ["19", 0], "original_resized_images": ["14", 0],
                                                                  "color_correction_method": "lab"}},
        "21": {"class_type": "SaveImage", "inputs": {"images": ["20", 0], "filename_prefix": f"cases/{prefix}_up"}},
    }


def build_graph(job, ref_names=None):
    seed = job.get("seed", random.randint(0, 2**31))
    g = {
        "3": {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_2.1_int8_convrot.safetensors", "weight_dtype": "default"}},
        "4": {"class_type": "QwenImage21Cache", "inputs": {"model": ["3", 0], "device": "auto", "dtype": "default"}},
        "5": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen3vl_8b_int8_convrot.safetensors", "type": "qwen_image", "device": "default"}},
        "6": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_2.1_vae_bf16.safetensors"}},
        "9": {"class_type": "KSampler", "inputs": {"model": ["4", 0], "seed": seed, "steps": 50, "cfg": 1.0, "sampler_name": "euler",
                                                    "scheduler": "simple", "positive": ["7", 0], "negative": ["7", 1],
                                                    "latent_image": None, "denoise": 1.0}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["6", 0]}},
        "11": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": f"{OUT_DIR.parent.name}/{job['id']}_base"}},
    }
    if ref_names:
        enc = {"clip": ["5", 0], "prompt": job["prompt"], "negative_prompt": "", "resolution": job.get("resolution", 1024), "vae": ["6", 0]}
        for i, name in enumerate(ref_names, 1):
            g[f"ref{i}"] = {"class_type": "LoadImage", "inputs": {"image": name}}
            enc[f"images.image_{i}"] = [f"ref{i}", 0]
        g["7"] = {"class_type": "TextEncodeQwenImage21", "inputs": enc}
        g["9"]["inputs"]["latent_image"] = ["7", 2]
    else:
        g["2"] = {"class_type": "ResolutionSelector", "inputs": {"aspect_ratio": job.get("aspect", "3:2 (Photo)"), "megapixels": job.get("megapixels", MP), "multiple": 32}}
        g["7"] = {"class_type": "TextEncodeQwenImage21", "inputs": {"clip": ["5", 0], "prompt": job["prompt"], "negative_prompt": "", "resolution": 1024}}
        g["8"] = {"class_type": "EmptyLatentImage", "inputs": {"width": ["2", 0], "height": ["2", 1], "batch_size": 1}}
        g["9"]["inputs"]["latent_image"] = ["8", 0]
    # Апскейл SeedVR2 вимкнено за замовчуванням: база 1 МП достатня для екрана телефона.
    if job.get("upscale", False):
        g.update(upscale_chain(["10", 0], job["id"]))
    return g, seed


def fetch_outputs(prompt_id):
    while True:
        hist = _get(f"/history/{prompt_id}")
        if prompt_id in hist:
            h = hist[prompt_id]
            if h.get("status", {}).get("status_str") == "error":
                raise RuntimeError(json.dumps(h["status"], ensure_ascii=False)[:2000])
            return h["outputs"]
        time.sleep(5)


def download(img, dest: Path):
    q = urllib.parse.urlencode({"filename": img["filename"], "subfolder": img["subfolder"], "type": img["type"]})
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(urllib.request.urlopen(f"{COMFY}/view?{q}").read())


def run(jobs, force=False):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    log_path = OUT_DIR / "_log.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else {}
    for job in jobs:
        dest = OUT_DIR / f"{job['id']}.png"
        if dest.exists() and not force:
            print(f"[skip] {job['id']}")
            continue
        refs = job.get("ref") or []
        refs = [refs] if isinstance(refs, str) else refs
        ref_paths = [BASE_DIR / f"{r}.png" if (BASE_DIR / f"{r}.png").exists() else OUT_DIR / f"{r}.png" for r in refs]
        missing = [str(p.stem) for p in ref_paths if not p.exists()]
        if missing:
            print(f"[wait] {job['id']}: немає референсу {missing}")
            continue
        # Розмір кадру з референсом береться з першого зображення; "canvas": "16:9" додає першим
        # порожнє сіре полотно потрібних пропорцій (тоді персонаж у промпті — <image2>).
        if job.get("canvas"):
            ref_paths = [make_canvas(job["canvas"])] + ref_paths
        graph, seed = build_graph(job, [upload_image(p) for p in ref_paths])
        t0 = time.time()
        pid = _post("/prompt", {"prompt": graph})["prompt_id"]
        print(f"[run ] {job['id']} seed={seed} prompt_id={pid}", flush=True)
        try:
            outputs = fetch_outputs(pid)
        except RuntimeError as e:
            print(f"[FAIL] {job['id']}: {e}", flush=True)
            continue
        if "21" in outputs:
            download(outputs["11"]["images"][0], BASE_DIR / f"{job['id']}.png")
        download(outputs.get("21", outputs.get("11"))["images"][0], dest)
        save_web_jpg(dest)
        log[job["id"]] = {"seed": seed, "prompt": job["prompt"], "ref": job.get("ref"), "seconds": round(time.time() - t0)}
        log_path.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[done] {job['id']} {round(time.time() - t0)}s -> {dest}", flush=True)


if __name__ == "__main__":
    args = sys.argv[1:]
    force = "--force" in args
    args = [a for a in args if a != "--force"]
    case = Path(args[0])
    OUT_DIR = case / "photos"
    BASE_DIR = OUT_DIR / "base"
    all_jobs = json.loads((case / "photo_jobs.json").read_text(encoding="utf-8"))
    ids = set(args[1:])
    run([j for j in all_jobs if not ids or j["id"] in ids], force=force)
