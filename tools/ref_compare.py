"""Порівняння моделей у режимі правки з референсами: Qwen-Image 2.1, Flux3 Klein, JoyAI Image Edit.

    python tools/ref_compare.py CASE P07 --ref P07_v3 REF_uaz_mil --prompt "..." [--models qwen flux joy] [--seeds 1 2] [--tag _x]
    python tools/photo_compare.py pick CASE P07 flux_x_s1     # обраний кандидат -> photos/P07.png + .jpg

Перший ref задає розмір кадру (як у comfy_gen.py без canvas). Кандидати й лист — у CASE/photos/_cmp/<ID>/.
"""
import argparse
import json
import sys
import time
from pathlib import Path

import comfy_gen as cg
from photo_compare import contact_sheet


def loads(names):
    g, out = {}, []
    for i, name in enumerate(names):
        g[f"l{i}"] = {"class_type": "LoadImage", "inputs": {"image": name}}
        g[f"s{i}"] = {"class_type": "ImageScaleToTotalPixels", "inputs": {"image": [f"l{i}", 0], "upscale_method": "lanczos",
                                                                         "megapixels": 1.0, "resolution_steps": 16}}
        out.append([f"s{i}", 0])
    return g, out


def qwen(prompt, names, seed):
    g, _ = cg.build_graph({"id": "cmp", "prompt": prompt, "seed": seed}, names)
    g["11"]["inputs"]["filename_prefix"] = "cmp/ref_qwen"
    return g, "11"


def flux(prompt, names, seed, loras=()):
    """Flux3 Klein: кожен референс — окремий ReferenceLatent поверх тексту; розмір кадру — з першого."""
    g, images = loads(names)
    g.update({
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "flux3-image-klein-int8-convrot.safetensors", "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen3vl_4b_int8_convrot.safetensors", "type": "flux3", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "flux2-vae.safetensors"}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["2", 0]}},
        "sz": {"class_type": "GetImageSize", "inputs": {"image": images[0]}},
    })
    model = ["1", 0]
    for i, spec in enumerate(loras):
        name, _, weight = spec.partition(":")
        g[f"lo{i}"] = {"class_type": "LoraLoaderModelOnly", "inputs": {"model": model, "lora_name": f"flux3\\{name}.safetensors",
                                                                       "strength_model": float(weight or 1)}}
        model = [f"lo{i}", 0]
    cond = ["6", 0]
    for i, im in enumerate(images):
        g[f"e{i}"] = {"class_type": "VAEEncode", "inputs": {"pixels": im, "vae": ["3", 0]}}
        g[f"r{i}"] = {"class_type": "ReferenceLatent", "inputs": {"conditioning": cond, "latent": [f"e{i}", 0]}}
        cond = [f"r{i}", 0]
    g.update({
        "8": {"class_type": "BasicGuider", "inputs": {"model": model, "conditioning": cond}},
        "9": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "10": {"class_type": "Flux2Scheduler", "inputs": {"steps": 20, "width": ["sz", 0], "height": ["sz", 1]}},
        "11": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "12": {"class_type": "EmptyFlux2LatentImage", "inputs": {"width": ["sz", 0], "height": ["sz", 1], "batch_size": 1}},
        "13": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["11", 0], "guider": ["8", 0], "sampler": ["9", 0],
                                                                  "sigmas": ["10", 0], "latent_image": ["12", 0]}},
        "14": {"class_type": "VAEDecode", "inputs": {"samples": ["13", 0], "vae": ["3", 0]}},
        "99": {"class_type": "SaveImage", "inputs": {"images": ["14", 0], "filename_prefix": "cmp/ref_flux"}},
    })
    return g, "99"


def joy(prompt, names, seed):
    g, images = loads(names)
    enc = {"clip": ["2", 0], "prompt": prompt, "vae": ["3", 0]}
    for i, im in enumerate(images, 1):
        enc[f"images.image{i - 1}"] = im
    g.update({
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "joyai_image_edit_int8_convrot.safetensors", "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen3vl_8b_joyimage_edit_int8_convrot.safetensors", "type": "joyimage", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "Wan2_1_VAE_bf16.safetensors"}},
        "6": {"class_type": "TextEncodeJoyImageEdit", "inputs": enc},
        "7": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["6", 0]}},
        # Закодований кадр як старт давав чорне зображення; потрібен порожній латент Wan
        "sz": {"class_type": "GetImageSize", "inputs": {"image": images[0]}},
        "8": {"class_type": "EmptyHunyuanLatentVideo", "inputs": {"width": ["sz", 0], "height": ["sz", 1], "length": 1, "batch_size": 1}},
        "9": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "seed": seed, "steps": 28, "cfg": 4.0, "sampler_name": "euler",
                                                    "scheduler": "simple", "positive": ["6", 0], "negative": ["7", 0],
                                                    "latent_image": ["8", 0], "denoise": 1.0}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
        "99": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "cmp/ref_joy"}},
    })
    return g, "99"


MODELS = {"qwen": qwen, "flux": flux, "joy": joy}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser()
    p.add_argument("case")
    p.add_argument("id")
    p.add_argument("--ref", nargs="+", required=True)
    p.add_argument("--prompt", required=True)
    p.add_argument("--models", nargs="+", default=list(MODELS), choices=list(MODELS))
    p.add_argument("--seeds", nargs="+", type=int, default=[1, 2])
    p.add_argument("--tag", default="")
    p.add_argument("--lora", nargs="*", default=[], help="лише flux: ім'я файлу з loras/flux3 без розширення[:сила]")
    a = p.parse_args()
    photos = Path(a.case) / "photos"
    cg.OUT_DIR = photos
    out = photos / "_cmp" / a.id
    out.mkdir(parents=True, exist_ok=True)
    log_path = out / "_log.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else {}
    paths = [photos / "base" / f"{r}.png" if (photos / "base" / f"{r}.png").exists() else photos / f"{r}.png" for r in a.ref]
    names = [cg.upload_image(x) for x in paths]
    done = []
    for model in a.models:
        for seed in a.seeds:
            name = f"{model}{a.tag}_s{seed}"
            graph, node = flux(a.prompt, names, seed, a.lora) if model == "flux" else MODELS[model](a.prompt, names, seed)
            t0 = time.time()
            try:
                pid = cg._post("/prompt", {"prompt": graph})["prompt_id"]
                outputs = cg.fetch_outputs(pid)
            except Exception as e:
                body = e.read().decode()[:1500] if hasattr(e, "read") else str(e)[:1500]
                print(f"[FAIL] {name}: {body}", flush=True)
                continue
            dest = out / f"{name}.png"
            cg.download(outputs[node]["images"][0], dest)
            done.append(dest)
            log[name] = {"model": model, "seed": seed, "prompt": a.prompt, "ref": a.ref, "seconds": round(time.time() - t0)}
            log_path.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"[done] {name} {round(time.time() - t0)}s", flush=True)
    if done:
        contact_sheet(done, photos / "_cmp" / f"{a.id}{a.tag}_sheet.jpg")
        print("[sheet]", photos / "_cmp" / f"{a.id}{a.tag}_sheet.jpg")


if __name__ == "__main__":
    main()
