"""Пакетна генерація аудіо справи: голоси (Drama 3 через audio.cpp), шуми й музика (ComfyUI), накладання ефектів.

Запуск (потрібні soundfile і imageio-ffmpeg; на цій машині — C:\\Python314\\python.exe):
    python tools/audio_gen.py cases/stairs            # усі завдання, яких ще немає
    python tools/audio_gen.py cases/stairs R01 A02    # лише вказані ID
    python tools/audio_gen.py cases/stairs --force R01
Результат: CASE/audio/<ID>.opus (для гри); оригінали wav/flac і голоси персонажів — CASE/audio/_raw/ (не комітяться).
Для kind=sfx/music потрібен запущений ComfyUI (http://127.0.0.1:8188); для voice/speech — audio.cpp (docs/MEDIA.md),
а ComfyUI краще закрити: Drama 3 займає майже всю відеопам'ять.

Завдання (CASE/audio_jobs.json, список):
  {"id": "V_P02", "kind": "voice", "text": "[elderly man, low hoarse voice] Спокійна фраза на 5–10 секунд.", "seed": 2}
      Голос персонажа (кастинг): у ремарці — стать, вік, тембр англійською; фраза — нейтральна, без емоцій. Результат —
      лише _raw/V_P02.wav, зразок для speech; у гру не йде. Не той голос — інший seed (--force).
  {"id": "R01", "kind": "speech", "voice": "V_P02", "text": "[angry, raising voice] Не був я там! Не був!", "seed": 1}
      Репліка голосом персонажа — клон зразка voice (завдання voice має бути вище в списку). Подачу задають ремарки
      англійською в квадратних дужках будь-де в тексті: [sighs], [whispering], [pause], [crying], [laughs nervously]…
  {"id": "R02", "kind": "speech", "lines": [{"voice": "V_P02", "text": "Алло?"}, {"voice": "V_P05", "text": "[quietly] Це я."}],
   "gap": 0.4, "phone": true}
      Розмова кількох людей: кожна репліка — окремий дубль, між ними gap секунд тиші (за замовчуванням 0.4).
  {"id": "A01", "kind": "sfx", "prompt": "English description", "seconds": 10, "seed": 5}
      Stable Audio 3 (ComfyUI): шум, атмосфера, одиничний звук. Англійською, конкретно; додавай "no speech, no music".
  {"id": "M01", "kind": "music", "prompt": "style tags", "seconds": 60, "bpm": 90, "seed": 1}
      ACE-Step 1.5 (ComfyUI), інструментал.
Будь-який запис можна зібрати з шарів — ефекти й фон поверх основного звуку:
  "mix": [{"id": "A01", "at": 0, "gain_db": -18, "loop": true},     # фон під усю репліку
          {"id": "A02", "at": 2.5, "gain_db": -6}]                  # одиничний звук (двері, клацання) з 2.5-ї секунди
  id шару — це інше завдання з audio_jobs.json (воно має бути згенероване раніше, в списку воно йде вище). "loop" повторює
  шар на всю довжину; "at" — зсув у секундах; "gain_db" — гучність (фон −18…−24, подія −6…−12). Довжина = довшому з шарів.
"phone": true — телефонна смуга 300–3400 Гц + легке стиснення на весь підсумковий звук (автовідповідач, дзвінок).
Необов'язково: "bitrate" ("48k" мова за замовчуванням, "96k" шуми й музика), "max_tokens" (1500 ≈ 70 с мови).
"""
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import imageio_ffmpeg
import numpy as np
import soundfile as sf

FF = imageio_ffmpeg.get_ffmpeg_exe()
COMFY = "http://127.0.0.1:8188"
AUDIOCPP = Path(os.environ.get("AUDIOCPP", r"C:\Users\sokol\audiocpp"))
DRAMA_MODEL = AUDIOCPP / "models" / "drama-3-backbone-8bit-experts-Q3_K_S.gguf"


PHONE_FILTER = "highpass=f=300,lowpass=f=3400,acompressor=threshold=-24dB:ratio=3,volume=2dB"


def to_opus(src: Path, dest: Path, bitrate: str, phone=False):
    af = ["-af", PHONE_FILTER] if phone else []
    subprocess.run([FF, "-v", "error", "-y", "-i", str(src), "-ac", "1", *af, "-c:a", "libopus", "-b:a", bitrate, str(dest)], check=True)


# --- голос: Drama 3 (audio.cpp) ---------------------------------------------------------------------------------------
def _drama(text, raw: Path, seed, max_tokens, ref: Path = None, ref_text=None):
    """Один дубль. Без ref — випадковий голос (його задає ремарка в тексті), з ref — клон зразка."""
    cmd = [str(AUDIOCPP / "bin" / "audiocpp_cli.exe"), "--task", "clon" if ref else "tts", "--family", "fish_drama3",
           "--backend", "cuda", "--mode", "offline", "--model", str(DRAMA_MODEL), "--text", text,
           "--max-tokens", str(max_tokens), "--seed", str(seed), "--threads", "8",
           # 16 ГБ відеопам'яті: частина експертів і кодек — на CPU
           "--session-option", "fish_drama3.cpu_expert_layers=12", "--session-option", "fish_drama3.context_length=4096",
           "--session-option", "fish_drama3.codec_backend=cpu", "--out", str(raw)]
    if ref:
        cmd += ["--voice-ref", str(ref), "--request-option", f"reference_text={ref_text}"]
    raw.unlink(missing_ok=True)
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = r.stdout + r.stderr
    if r.returncode or not raw.exists():
        raise RuntimeError(next((l for l in out.splitlines() if "failed" in l), out[-500:]))
    if '"reached_limit":true' in out:
        raise RuntimeError("дубль уперся в max_tokens (затягнута пауза чи реакція) — інший seed або коротший текст")


def _voice_ref(voice_id, raw_dir: Path, all_jobs):
    job = next((j for j in all_jobs if j["id"] == voice_id and j["kind"] == "voice"), None)
    ref = raw_dir / f"{voice_id}.wav"
    if job is None or not ref.exists():
        raise FileNotFoundError(f"немає голосу {voice_id} (завдання kind=voice, згенероване раніше)")
    return ref, re.sub(r"\[[^\]]*\]\s*", "", job["text"]).strip()


def gen_voice(job, raw: Path, raw_dir: Path, all_jobs):
    _drama(job["text"], raw, job.get("seed", 1), job.get("max_tokens", 600))


def gen_speech(job, raw: Path, raw_dir: Path, all_jobs):
    lines = job.get("lines") or [{"voice": job["voice"], "text": job["text"]}]
    if len(lines) == 1:
        ref, ref_text = _voice_ref(lines[0]["voice"], raw_dir, all_jobs)
        _drama(lines[0]["text"], raw, job.get("seed", 1), job.get("max_tokens", 1500), ref, ref_text)
        return
    parts, sr = [], None
    for i, line in enumerate(lines):
        ref, ref_text = _voice_ref(line["voice"], raw_dir, all_jobs)
        part = raw_dir / f"{job['id']}_{i}.wav"
        _drama(line["text"], part, line.get("seed", job.get("seed", 1)), job.get("max_tokens", 1500), ref, ref_text)
        data, sr = sf.read(part, dtype="float32", always_2d=True)
        parts += [data.mean(axis=1), np.zeros(int(sr * job.get("gap", 0.4)), dtype="float32")]
    sf.write(raw, np.concatenate(parts[:-1]), sr)


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


def gen_sfx(job, raw: Path, *_):
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


def gen_music(job, raw: Path, *_):
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


def run(case: Path, jobs, all_jobs, force=False):
    out_dir, raw_dir = case / "audio", case / "audio" / "_raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    log_path = out_dir / "_log.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else {}
    for job in jobs:
        kind, t0 = job["kind"], time.time()
        raw = raw_dir / f"{job['id']}.{'wav' if kind in ('voice', 'speech') else 'flac'}"
        dest = raw if kind == "voice" else out_dir / f"{job['id']}.opus"  # голос — лише зразок для speech
        if dest.exists() and not force:
            print(f"[skip] {job['id']}")
            continue
        try:
            {"voice": gen_voice, "speech": gen_speech, "sfx": gen_sfx, "music": gen_music}[kind](job, raw, raw_dir, all_jobs)
            if kind != "voice":
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
    force = "--force" in args
    args = [a for a in args if a != "--force"]
    case = Path(args[0])
    all_jobs = json.loads((case / "audio_jobs.json").read_text(encoding="utf-8"))
    ids = set(args[1:])
    run(case, [j for j in all_jobs if not ids or j["id"] in ids], all_jobs, force=force)
