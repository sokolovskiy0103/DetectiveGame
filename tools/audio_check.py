"""Перевірка аудіо: тривалість, гучність і розпізнавання мови назад (faster-whisper) — щоб порівняти зі сценарієм.

    python tools/audio_check.py cases/stairs/audio/R01.opus [ще файли…] [--lang uk]
Потрібен інтерпретатор із faster-whisper і soundfile (на цій машині — C:\\Python314\\python.exe).
"""
import sys

sys.stdout.reconfigure(encoding="utf-8")

import numpy as np
import soundfile as sf
from faster_whisper import WhisperModel


def load_16k_mono(path):
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if sr != 16000:
        mono = np.interp(np.linspace(0, len(mono), int(len(mono) * 16000 / sr), endpoint=False), np.arange(len(mono)), mono).astype("float32")
    return mono, len(data) / sr, float(np.sqrt((data ** 2).mean()))


if __name__ == "__main__":
    args = sys.argv[1:]
    lang = "uk"
    if "--lang" in args:
        i = args.index("--lang")
        lang = args[i + 1]
        del args[i:i + 2]
    model = WhisperModel("large-v3", device="cpu", compute_type="int8")
    for f in args:
        audio, dur, rms = load_16k_mono(f)
        segs, _ = model.transcribe(audio, language=lang, vad_filter=False, condition_on_previous_text=False)
        print(f"{f}: {dur:.1f} с, rms={rms:.3f}")
        for s in segs:
            print(f"  [{s.start:.1f}-{s.end:.1f}] {s.text.strip()}  (no_speech={s.no_speech_prob:.2f})")
