"""Пакетна генерація аудіо справи: голоси українською (HolosTTS), шуми й музика (ComfyUI), накладання ефектів.

Запускати інтерпретатором venv з HolosTTS (див. docs/NEW_CASE.md, «Аудіо»), на цій машині —
    %TEMP%\\holosenv\\Scripts\\python.exe tools/audio_gen.py cases/stairs            # усі завдання, яких ще немає
    ... tools/audio_gen.py cases/stairs R01 A02                                    # лише вказані ID
    ... tools/audio_gen.py cases/stairs --force R01
    ... tools/audio_gen.py --voices                                                # список голосів
Результат: CASE/audio/<ID>.opus (для гри); оригінали wav/flac — CASE/audio/_raw/ (не комітяться).
Для kind=sfx/music потрібен запущений ComfyUI (http://127.0.0.1:8188); для tts/clone — ні.

Завдання (CASE/audio_jobs.json, список):
  {"id": "R01", "kind": "tts", "voice": "Speaker_0", "text": "Я прийшов додому о пів на одинадцяту."}
      Голос — з таблиці в docs/NEW_CASE.md; один персонаж = один голос на всю справу. Емоцію й інтонацію задають текст і
      розділові знаки ("?", "!", "...", тире, короткі речі); наголос розставляє модель, ручний — "+" перед голосною
      ("з+амок"). Довгі репліки діліть на окремі речення (кілька id або кілька речень через ". ").
  {"id": "R02", "kind": "clone", "ref": "шлях/до/зразка.wav", "text": "…"}
      Той самий синтез, але голос береться із запису (чистий голос 5–30 с, без музики й сторонніх голосів).
  {"id": "A01", "kind": "sfx", "prompt": "English description", "seconds": 10, "seed": 5}
      Stable Audio 3 (ComfyUI): шум, атмосфера, одиничний звук. Англійською, конкретно; додавай "no speech, no music".
  {"id": "M01", "kind": "music", "prompt": "style tags", "seconds": 60, "bpm": 90, "seed": 1}
      ACE-Step 1.5 (ComfyUI), інструментал.
Будь-який запис можна зібрати з шарів — ефекти й фон поверх основного звуку:
  "mix": [{"id": "A01", "at": 0, "gain_db": -18, "loop": true},     # фон під усю репліку
          {"id": "A02", "at": 2.5, "gain_db": -6}]                  # одиничний звук (двері, клацання) з 2.5-ї секунди
  id шару — це інше завдання з audio_jobs.json (воно має бути згенероване раніше, в списку воно йде вище). "loop" повторює
  шар на всю довжину; "at" — зсув у секундах; "gain_db" — гучність (фон −18…−24, подія −6…−12). Довжина = довшому з шарів.
Паузи в мові: "pause": 0.8 — тиша між реченнями (за замовчуванням 0.25 с); маркер {1.5} у тексті — тиша 1.5 с саме
  в цьому місці (замість звичайної паузи): "Алло... {1.2} Це я. {1.5} Бувай."
"phone": true — телефонна смуга 300–3400 Гц + легке стиснення на весь підсумковий звук (автовідповідач, дзвінок).
Необов'язково: "bitrate" ("48k" мова за замовчуванням, "96k" шуми й музика).
"""
import json
import random
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import imageio_ffmpeg
import soundfile as sf

FF = imageio_ffmpeg.get_ffmpeg_exe()
COMFY = "http://127.0.0.1:8188"


PHONE_FILTER = "highpass=f=300,lowpass=f=3400,acompressor=threshold=-24dB:ratio=3,volume=2dB"


def to_opus(src: Path, dest: Path, bitrate: str, phone=False):
    af = ["-af", PHONE_FILTER] if phone else []
    subprocess.run([FF, "-v", "error", "-y", "-i", str(src), "-ac", "1", *af, "-c:a", "libopus", "-b:a", bitrate, str(dest)], check=True)


# --- голос ------------------------------------------------------------------------------------------------------------
_state = {}


def _model():
    if "model" not in _state:
        import torch
        from holos.models import HolosTTS
        from ipa_uk import ipa
        from ukrainian_word_stress import Stressifier
        _state.update(model=HolosTTS("patriotyk/HolosTTS", device="cpu"), stress=Stressifier(), ipa=ipa, torch=torch)
    return _state


def _speak(text, voice, raw: Path, pause=0.25):
    """Речення озвучуються окремо; між ними тиша `pause` с, а маркер {1.2} у тексті ставить тишу 1.2 с замість неї."""
    import contextlib
    import io
    import re
    s = _model()
    out, gap = [], None
    for i, chunk in enumerate(re.split(r"\{(\d+(?:\.\d+)?)\}", text)):
        if i % 2:  # маркер паузи
            gap = float(chunk)
            continue
        for sentence in (x.strip() for x in re.split(r"(?<=[.!?…])\s+", chunk) if x.strip()):
            if out:
                out.extend([0.0] * int(24000 * (gap if gap is not None else pause)))
            gap = None
            with contextlib.redirect_stdout(io.StringIO()):  # бібліотека друкує тензори
                out.extend(s["model"].generate(s["ipa"](s["stress"](sentence)), voice=voice).squeeze().cpu().numpy().tolist())
    sf.write(raw, out, 24000)


def gen_tts(job, raw: Path):
    _speak(job["text"], job["voice"], raw, job.get("pause", 0.25))


def gen_clone(job, raw: Path):
    from holos.models import VoiceEncoder
    if "encoder" not in _state:
        _state["encoder"] = VoiceEncoder("patriotyk/HolosTTS", device="cpu")
    _speak(job["text"], _state["encoder"].encode(job["ref"]), raw, job.get("pause", 0.25))


# --- ComfyUI: шуми й музика -------------------------------------------------------------------------------------------
def _comfy(path, data=None):
    req = urllib.request.Request(COMFY + path, data=json.dumps(data).encode() if data else None, headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req))


def _run_comfy(graph, save_node, raw: Path):
    pid = _comfy("/prompt", {"prompt": graph})["prompt_id"]
    while True:
        hist = _comfy(f"/history/{pid}")
        if pid in hist:
            if hist[pid].get("status", {}).get("status_str") == "error":
                raise RuntimeError(json.dumps(hist[pid]["status"], ensure_ascii=False)[:1500])
            item = hist[pid]["outputs"][save_node]["audio"][0]
            break
        time.sleep(2)
    q = urllib.parse.urlencode({"filename": item["filename"], "subfolder": item["subfolder"], "type": item["type"]})
    raw.write_bytes(urllib.request.urlopen(f"{COMFY}/view?{q}").read())


def gen_sfx(job, raw: Path):
    seed = job.get("seed", random.randint(0, 2**31))
    g = {
        "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "stable_audio_3_medium.safetensors"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "t5gemma_b_b_ul2.safetensors", "type": "stable_audio", "device": "default"}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": job["prompt"]}},
        "4": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": ""}},
        "5": {"class_type": "EmptyLatentAudio", "inputs": {"seconds": job.get("seconds", 10), "batch_size": 1}},
        "6": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "positive": ["3", 0], "negative": ["4", 0], "latent_image": ["5", 0], "seed": seed,
                                                    "steps": 8, "cfg": 1, "sampler_name": "lcm", "scheduler": "simple", "denoise": 1}},
        "7": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["6", 0], "vae": ["1", 2]}},
        "8": {"class_type": "SaveAudioAdvanced", "inputs": {"audio": ["7", 0], "filename_prefix": "case_audio/sfx", "format": "flac"}},
    }
    _run_comfy(g, "8", raw)


def gen_music(job, raw: Path):
    seed, secs = job.get("seed", random.randint(0, 2**31)), job.get("seconds", 60)
    g = {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "acestep_v1.5_xl_turbo_bf16.safetensors", "weight_dtype": "default"}},
        "2": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["1", 0], "shift": 3}},
        "3": {"class_type": "DualCLIPLoader", "inputs": {"clip_name1": "qwen_0.6b_ace15.safetensors", "clip_name2": "qwen_4b_ace15.safetensors", "type": "ace", "device": "default"}},
        "4": {"class_type": "VAELoader", "inputs": {"vae_name": "ace_1.5_vae.safetensors"}},
        "5": {"class_type": "TextEncodeAceStepAudio1.5", "inputs": {
            "clip": ["3", 0], "tags": job["prompt"], "lyrics": "[Instrumental]", "seed": seed, "bpm": job.get("bpm", 100), "duration": secs,
            "timesignature": "4", "language": "en", "keyscale": job.get("keyscale", "C major"), "generate_audio_codes": True,
            "cfg_scale": 2.0, "temperature": 0.85, "top_p": 0.9, "top_k": 0, "min_p": 0.0}},
        "6": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["5", 0]}},
        "7": {"class_type": "EmptyAceStep1.5LatentAudio", "inputs": {"seconds": secs, "batch_size": 1}},
        "8": {"class_type": "KSampler", "inputs": {"model": ["2", 0], "positive": ["5", 0], "negative": ["6", 0], "latent_image": ["7", 0], "seed": seed,
                                                    "steps": 8, "cfg": 1, "sampler_name": "euler", "scheduler": "simple", "denoise": 1}},
        "9": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["8", 0], "vae": ["4", 0]}},
        "10": {"class_type": "SaveAudioAdvanced", "inputs": {"audio": ["9", 0], "filename_prefix": "case_audio/music", "format": "flac"}},
    }
    _run_comfy(g, "10", raw)


# --- змішування шарів -------------------------------------------------------------------------------------------------
def mix_layers(base: Path, layers, raw_dir: Path, out: Path):
    """base + шари {id, at, gain_db, loop}: ефекти й фон поверх основного звуку."""
    cmd, chains, labels = [FF, "-v", "error", "-y", "-i", str(base)], ["[0:a]aresample=48000,aformat=channel_layouts=stereo[a0]"], ["[a0]"]
    total = sf.info(str(base)).duration
    for i, m in enumerate(layers, 1):
        src = next(raw_dir.glob(f"{m['id']}.*"), None)
        if src is None or src.stem.endswith("_mix"):
            raise FileNotFoundError(f"немає шару {m['id']} (згенеруйте його раніше)")
        total = max(total, m.get("at", 0) + (0 if m.get("loop") else sf.info(str(src)).duration))
        cmd += (["-stream_loop", "-1"] if m.get("loop") else []) + ["-i", str(src)]
        delay = int(m.get("at", 0) * 1000)
        chains.append(f"[{i}:a]aresample=48000,aformat=channel_layouts=stereo,volume={m.get('gain_db', -12)}dB,adelay={delay}|{delay}[a{i}]")
        labels.append(f"[a{i}]")
    chains.append("".join(labels) + f"amix=inputs={len(labels)}:duration=longest:normalize=0,atrim=0:{total:.3f}[o]")
    subprocess.run(cmd + ["-filter_complex", ";".join(chains), "-map", "[o]", str(out)], check=True)


def run(case: Path, jobs, force=False):
    out_dir, raw_dir = case / "audio", case / "audio" / "_raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    log_path = out_dir / "_log.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else {}
    for job in jobs:
        dest = out_dir / f"{job['id']}.opus"
        if dest.exists() and not force:
            print(f"[skip] {job['id']}")
            continue
        kind, t0 = job["kind"], time.time()
        raw = raw_dir / f"{job['id']}.{'wav' if kind in ('tts', 'clone') else 'flac'}"
        try:
            {"tts": gen_tts, "clone": gen_clone, "sfx": gen_sfx, "music": gen_music}[kind](job, raw)
            src = raw
            if job.get("mix"):
                src = raw_dir / f"{job['id']}_mix.wav"
                mix_layers(raw, job["mix"], raw_dir, src)
            to_opus(src, dest, job.get("bitrate", "96k" if kind in ("sfx", "music") else "48k"), job.get("phone", False))
        except Exception as e:
            print(f"[FAIL] {job['id']}: {e}", flush=True)
            continue
        log[job["id"]] = {**{k: v for k, v in job.items() if k != "id"}, "took": round(time.time() - t0)}
        log_path.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[done] {job['id']} {round(time.time() - t0)}s -> {dest}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    args = sys.argv[1:]
    if "--voices" in args:
        print("\n".join(sorted(_model()["model"].list_voices())))
        sys.exit()
    force = "--force" in args
    args = [a for a in args if a != "--force"]
    case = Path(args[0])
    all_jobs = json.loads((case / "audio_jobs.json").read_text(encoding="utf-8"))
    ids = set(args[1:])
    run(case, [j for j in all_jobs if not ids or j["id"] in ids], force=force)
