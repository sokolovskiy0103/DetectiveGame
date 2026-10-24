"""Розпізнавання запитів гравців: нечіткі ключові слова + (за наявності) семантичний суддя.

Шар 1 — FuzzyMatcher (як у Herd/party_games): нормалізація, стоп-слова, грубий стемінг, друкарські помилки
(Левенштейн), точні цифри. Працює завжди.
Шар 2 — Semantic: мультимовний sentence-encoder (косинус) — фраза має бути ближчою до прикладів правила, ніж до
контр-прикладів. Модель вантажиться у фоні; поки її немає або вона впала — гра працює лише на шарі 1.
"""
import os
import re
import threading

import numpy as np

STOP_WORDS = {
    "в", "у", "на", "з", "із", "зі", "до", "по", "під", "за", "біля", "коло", "від", "для", "про", "через", "при",
    "над", "між", "без", "о", "об", "і", "й", "та", "а", "це", "то", "ну", "що", "як", "чи", "ви", "вас", "вам",
    "ваш", "ваша", "ваше", "ваші", "він", "вона", "вони", "його", "її", "їх", "ми", "нас", "мені", "я", "не",
    "вул", "вулиця", "пан", "пані", "гр", "громадянин", "громадянка",
}

# найдовші спершу
ENDINGS = ["ами", "ями", "ові", "еві", "єві", "ого", "ому", "ими", "іми", "ях", "ах", "ою", "ею", "єю", "ом", "ем",
           "ам", "ям", "ів", "їв", "ий", "ій", "ія", "ая", "яя", "ое", "ее", "ые",
           "а", "я", "у", "ю", "і", "и", "е", "є", "о", "ь", "ї", "й", "ы"]

# Латинські літери, схожі на кириличні (номерні знаки вводять як завгодно).
_LOOKALIKE = str.maketrans({"a": "а", "b": "в", "c": "с", "e": "е", "h": "н", "i": "і", "k": "к", "m": "м",
                            "o": "о", "p": "р", "t": "т", "x": "х", "y": "у"})


def _base(text: str) -> str:
    return text.lower().replace("’", "'").replace("ʼ", "'").replace("`", "'").replace("ё", "е").replace("ы", "и")


def compact(text: str) -> str:
    """Без пробілів і розділових знаків (крім ' : - №) — для багатослівних ключів і номерів."""
    return re.sub(r"[^\w':№-]", "", _base(text))


def digits(text: str) -> str:
    return re.sub(r"\D", "", text)


def tokens(text: str) -> list[str]:
    words = re.sub(r"[^\w']", " ", _base(text)).split()
    return [w for w in words if w not in STOP_WORDS] or words


def stem(token: str) -> str:
    if token.isdigit():
        return token
    for e in ENDINGS:
        if token.endswith(e) and len(token) - len(e) >= 3:
            return token[: -len(e)]
    return token


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a or not b:
        return len(a) or len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _allowed_typos(n: int) -> int:
    return 0 if n < 5 else 1 if n < 9 else 2


class Text:
    """Запит гравця, попередньо оброблений один раз."""

    def __init__(self, raw: str):
        self.raw = raw
        self.compact = compact(raw)
        self.translit = compact(raw.lower().translate(_LOOKALIKE))
        self.digits = digits(raw)
        self.tokens = tokens(raw)
        self.stems = [stem(t) for t in self.tokens]


def keyword_hit(keyword: str, text: Text) -> bool:
    """Ключ — це префікс/основа слова («кульчицьк»), фраза («номер авто»), номер («#672140851») або ID («112487»)."""
    if keyword.startswith("#"):
        return keyword[1:] in text.digits
    kc = compact(keyword)
    if kc in text.compact or kc in text.translit:
        return True
    if any(ch.isdigit() for ch in kc) or " " in keyword.strip():
        return False  # номери й фрази — лише точний збіг
    ks = kc
    allowed = _allowed_typos(len(ks))
    # Стем ключа порівнюємо лише якщо він лишається довгим: «кравець»→«кравец» ≈ «кравц(я)», але
    # «гнатів»→«гнат» вже збігся б із «Гнатюк».
    kstem = stem(ks) if len(stem(ks)) >= 5 else None
    for tok, st in zip(text.tokens, text.stems):
        if st.startswith(ks) or tok.startswith(ks):
            return True
        if allowed and (levenshtein(tok[: len(ks)], ks) <= allowed or levenshtein(st, ks) <= allowed):
            return True
        if kstem and levenshtein(st, kstem) <= _allowed_typos(len(kstem)):
            return True
    return False


def group_hit(group, text: Text) -> bool:
    return any(keyword_hit(k, text) for k in group)


# ---------------- семантика ----------------

EMBED_MODEL = "sentence-transformers/paraphrase-multilingual-mpnet-base-v2"
# Підібрано на tests/semantic_cases.json (tools/tune_semantic.py): разом із ключовими словами 33/37 перефразувань
# і 0 хибних спрацювань. NLI (mDeBERTa-xnli, як у Herd) українською тут не додає точності — не використовується.
FLOOR = 0.55     # мінімальна схожість з прикладами правила
MARGIN = 0.075   # наскільки фраза має бути ближча до прикладів правила, ніж до контр-прикладів


class Semantic:
    """Чи пред'являє гравець те, що описують приклади правила, — відносно «нейтральних» тем цієї особи."""

    def __init__(self):
        self.state = "off"           # off | loading | ready | error
        self.error = ""
        self._embed = None
        self._cache: dict[str, np.ndarray] = {}
        self._lock = threading.Lock()

    def start(self):
        if self.state in ("loading", "ready") or os.environ.get("DG_NO_AI"):
            return
        self.state = "loading"
        threading.Thread(target=self._load, daemon=True).start()

    def _load(self):
        try:
            os.environ.setdefault("HF_HUB_OFFLINE", "1")
            from sentence_transformers import SentenceTransformer
            self._embed = SentenceTransformer(EMBED_MODEL, device="cpu")
            self.state = "ready"
        except Exception as e:  # гра має працювати й без моделі
            self.error = str(e)[:200]
            self.state = "error"

    def _vecs(self, texts):
        missing = [t for t in dict.fromkeys(texts) if t not in self._cache]
        if missing:
            for t, v in zip(missing, self._embed.encode(missing, normalize_embeddings=True)):
                self._cache[t] = v
        return np.stack([self._cache[t] for t in texts])

    def similarity(self, text: str, phrases: list[str]) -> float:
        return float((self._vecs(phrases) @ self._vecs([text])[0]).max())

    def decide(self, text: str, examples: list[str], counters: list[str]) -> bool | None:
        """None — модель недоступна (вирішують лише ключові слова)."""
        if self.state != "ready" or not text.strip() or not examples:
            return None
        with self._lock:
            s_rule = self.similarity(text, examples)
            s_counter = self.similarity(text, counters) if counters else 0.0
        return s_rule >= FLOOR and s_rule - s_counter >= MARGIN


semantic = Semantic()
