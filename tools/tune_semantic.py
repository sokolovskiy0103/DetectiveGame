"""Підбір FLOOR і MARGIN для семантичного судді на tests/semantic_cases.json.

    uv run python tools/tune_semantic.py

Рішення для правила R (app/matcher.py, Semantic.decide):
    s(R) = max косинус із прикладами R;  s(C) = max косинус із контр-прикладами
    (теми першого допиту особи + загальні фрази + приклади інших правил цієї особи)
    спрацьовує, якщо s(R) >= FLOOR і s(R) - s(C) >= MARGIN.
Рішення гри = ключові слова АБО семантика. Ціль — нуль хибних спрацювань, максимум правильних.
Після зміни прикладів у case.json — перезапустити й перенести найкращі значення в app/matcher.py.
"""
import json
import sys
from itertools import product
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app import engine, matcher  # noqa: E402

cases = json.loads((engine.ROOT / "tests" / "semantic_cases.json").read_text(encoding="utf-8"))
rules = {r["unlock"][0]: r for r in engine.Game().case["rules"] if r.get("examples")}

matcher.semantic._load()
assert matcher.semantic.state == "ready", matcher.semantic.error
sem = matcher.semantic

rows = []
for rid, c in cases.items():
    r = rules[rid]
    for is_pos, texts in ((True, c["pos"]), (False, c["neg"])):
        for t in texts:
            kw = matcher.group_hit(r["match"][-1], matcher.Text(t))
            rows.append((rid, t, is_pos, kw, sem.similarity(t, r["examples"]), sem.similarity(t, r["counters"])))

n_pos = sum(r[2] for r in rows)
print(f"{len(rows)} фраз; лише ключові слова: {sum(r[3] for r in rows if r[2])}/{n_pos}, "
      f"хибних {sum(r[3] for r in rows if not r[2])}\n")

results = []
for floor, margin in product(np.arange(0.45, 0.80, 0.025), (0.0, 0.025, 0.05, 0.075, 0.1, 0.125)):
    fire = [kw or (s >= floor and s - c >= margin) for _, _, _, kw, s, c in rows]
    tp = sum(f and r[2] for f, r in zip(fire, rows))
    fp = sum(f and not r[2] for f, r in zip(fire, rows))
    results.append((fp, -tp, round(float(floor), 3), margin))
results.sort()
for fp, ntp, floor, margin in results[:6]:
    print(f"  FP={fp} TP={-ntp}/{n_pos}  FLOOR={floor} MARGIN={margin}")

print(f"\nПоточні FLOOR={matcher.FLOOR} MARGIN={matcher.MARGIN}; пропущені позитиви / хибні спрацювання:")
for rid, t, is_pos, kw, s, c in rows:
    fire = kw or (s >= matcher.FLOOR and s - c >= matcher.MARGIN)
    if fire != is_pos:
        print(f"  {'ПРОПУСК' if is_pos else 'ХИБНЕ'} {rid} s={s:.2f} c={c:.2f}  {t}")
