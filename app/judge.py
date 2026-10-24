"""Суддя вільних відповідей на контрольні питання.

Види полів (content/case.json → questions[].fields[]):
  person — у відповіді має бути названа саме ця людина й жоден інший підозрюваний;
  time   — усі згадані моменти часу мають лежати у вікні `window` (і хоч один має бути);
  text   — відповідь складається з частин-аргументів (`parts`); частина зараховується за ключовими словами
           (нечіткий пошук) або семантично (приклади `examples` проти контр-прикладів `wrong`).
"""
import re

from app.matcher import Text, keyword_hit, semantic

GENERIC_WRONG = ["не знаю", "важко сказати", "нещасний випадок", "він сам упав зі сходів", "пограбування",
                 "сліди боротьби", "без відповіді"]

# Порядкові числівники годин у розмовній формі («о восьмій», «пів на восьму»).
_HOUR_WORDS = {"першо": 1, "друго": 2, "третьо": 3, "четверто": 4, "п'ято": 5, "шосто": 6, "сьомо": 7, "восьмо": 8,
               "дев'ято": 9, "десято": 10, "одинадцято": 11, "дванадцято": 12,
               "першу": 1, "другу": 2, "третю": 3, "четверту": 4, "п'яту": 5, "шосту": 6, "сьому": 7, "восьму": 8,
               "дев'яту": 9, "десяту": 10, "одинадцяту": 11, "дванадцяту": 12,
               "перш": 1, "друг": 2, "трет": 3, "четверт": 4, "п'ят": 5, "шост": 6, "сьом": 7, "восьм": 8,
               "дев'ят": 9, "десят": 10, "одинадцят": 11, "дванадцят": 12}
_EVENING = 12  # гра про вечір: «о восьмій» = 20:00


def _hour_word(word: str):
    for stem in sorted(_HOUR_WORDS, key=len, reverse=True):
        if word.startswith(stem):
            return _HOUR_WORDS[stem]
    return None


def parse_times(text: str) -> list[int]:
    """Хвилини від 00:00 для всіх згаданих моментів: «19:30», «19.30», «між 19 і 21», «пів на восьму»,
    «о восьмій», «7 вечора». Числа, що явно не години (9 см, 1–2 години після їжі), ігноруються."""
    s = text.lower().replace("’", "'").replace("ʼ", "'")
    s = re.sub(r"\b\d{1,2}\.\d{1,2}\.\d{2,4}\b|\b1[0-5]\.11\b", " ", s)  # дати («12.11», «12.11.2024»)
    times = []
    for h, m in re.findall(r"\b([01]?\d|2[0-3])[:.]([0-5]\d)\b", s):
        times.append(int(h) * 60 + int(m))
    s = re.sub(r"\b([01]?\d|2[0-3])[:.]([0-5]\d)\b", " ", s)
    for w in re.findall(r"пів\s*на\s*([\w']+)", s):
        h = _hour_word(w)
        if h:
            times.append(((h - 1 + _EVENING) % 24) * 60 + 30)
    s = re.sub(r"пів\s*на\s*[\w']+", " ", s)
    for n, ev in re.findall(r"\b(\d{1,2})\s*(вечора|веч\b|год|годин)?", s):
        n = int(n)
        if 17 <= n <= 23:
            times.append(n * 60)
        elif ev.startswith("веч") and 1 <= n <= 11:
            times.append((n + 12) * 60)
    for w in re.findall(r"\bо\s+([\w']+)", s):
        h = _hour_word(w)
        if h:
            times.append(((h + _EVENING) % 24) * 60)
    return times


def _minutes(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


class Judge:
    def __init__(self, persons: list[dict], suspects: list[str]):
        self.persons = persons
        self.suspects = set(suspects)

    def named_persons(self, text: Text) -> set[str]:
        return {p["id"] for p in self.persons if any(keyword_hit(a, text) for a in p["aliases"])}

    def part_hit(self, part: dict, answer: str, text: Text, wrong: list[str]) -> bool:
        if any(keyword_hit(k, text) for k in part.get("kw", [])):
            return True
        if part.get("examples"):
            return bool(semantic.decide(answer, part["examples"], wrong + GENERIC_WRONG))
        return False

    def field(self, f: dict, answer) -> tuple[bool, float]:
        """(зараховано?, бали)"""
        answer = (answer or "").strip() if isinstance(answer, str) else ""
        if not answer:
            return False, 0.0
        text = Text(answer)
        kind = f["kind"]
        if kind == "person":
            named = self.named_persons(text)
            ok = f["person"] in named and not (named & self.suspects - {f["person"]})
        elif kind == "time":
            times = parse_times(answer)
            lo, hi = (_minutes(x) for x in f["window"])
            ok = bool(times) and all(lo <= t <= hi for t in times)
        else:
            # «авто Соломії і квартира Максима» — місця названо, але не тієї людини
            if f.get("only_person") and self.named_persons(text) & self.suspects - {f["only_person"]}:
                return False, 0.0
            wrong = f.get("wrong", [])
            hits = sum(self.part_hit(p, answer, text, wrong) for p in f["parts"])
            ok = hits >= f.get("min_parts", 1)
            if ok and f.get("points_per_part"):
                return True, float(min(hits * f["points_per_part"], f.get("max_points", hits)))
        return ok, float(f.get("points", 0 if f.get("required") else 1)) if ok else 0.0
