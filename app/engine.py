"""Ігрова логіка: стан справи, зіставлення запитів із правилами, перевірка контрольних питань.

Стан зберігається в data/save.json після кожної зміни, тож гру можна продовжити після перезапуску сервера.
"""
import json
import time
from pathlib import Path

from app.judge import Judge
from app.matcher import Text, group_hit, keyword_hit, semantic

ROOT = Path(__file__).resolve().parent.parent
CASE_PATH = ROOT / "content" / "case.json"
SAVE_PATH = ROOT / "data" / "save.json"

HINT_PENALTY = 0.5
WRONG_ANSWER_PENALTY = 0.5


def rule_matches(rule, text: Text, details: str) -> bool:
    """Усі групи ключів мають збігтися. Останню групу (що пред'являємо) може закрити семантичний суддя,
    якщо в правилі є приклади формулювань (`examples`); `counters` — заздалегідь зібрані контр-приклади."""
    *head, last = rule["match"]
    if not all(group_hit(g, text) for g in head):
        return False
    if group_hit(last, text):
        return True
    if rule.get("examples") and details.strip():
        return bool(semantic.decide(details, rule["examples"], rule.get("counters", [])))
    return False


class Game:
    def __init__(self):
        self.case = json.loads(CASE_PATH.read_text(encoding="utf-8"))
        self.docs = {d["id"]: d for d in self.case["documents"]}
        self.questions = {q["id"]: q for q in self.case["questions"]}
        self._attach_counters()
        self.judge = Judge(self.case["persons"], self.case["suspects"])
        self.state = self._load() or self._new_state()

    def _attach_counters(self):
        """Контр-приклади правила: «нейтральні» теми цієї особи, загальні фрази й приклади її інших правил."""
        sem_rules = [r for r in self.case["rules"] if r.get("examples")]
        for r in sem_rules:
            others = [e for o in sem_rules if o is not r and o["person"] == r["person"] for e in o["examples"]]
            r["counters"] = (self.case["semantic_counter"].get(r["person"], [])
                             + self.case["semantic_counter_generic"] + others)

    # ---------- стан ----------
    def _new_state(self):
        now = time.time()
        return {
            "started_at": now,
            "finished_at": None,
            "stage": 1,
            "unlocked": [{"id": d, "at": now, "by": "Справа"} for d in self.case["start_documents"]],
            "log": [],            # журнал запитів і відповідей
            "notes": [],          # спільні нотатки
            "attempts": {},       # Q -> кількість невдалих спроб
            "solved": {},         # Q -> {"by", "at", "answers", "points"}
            "hints": {},          # Q -> кількість відкритих підказок
            "penalty": 0.0,
            "players": [],
        }

    def _load(self):
        if SAVE_PATH.exists():
            return json.loads(SAVE_PATH.read_text(encoding="utf-8"))
        return None

    def save(self):
        SAVE_PATH.parent.mkdir(parents=True, exist_ok=True)
        SAVE_PATH.write_text(json.dumps(self.state, ensure_ascii=False, indent=1), encoding="utf-8")

    def reset(self):
        self.state = self._new_state()
        self.save()

    def unlocked_ids(self):
        return {u["id"] for u in self.state["unlocked"]}

    def _unlock(self, ids, by):
        have = self.unlocked_ids()
        new = [i for i in ids if i not in have]
        now = time.time()
        self.state["unlocked"] += [{"id": i, "at": now, "by": by} for i in new]
        return new

    def _log(self, entry):
        entry["at"] = time.time()
        self.state["log"].append(entry)

    # ---------- запити ----------
    def request(self, player: str, rtype: str, target: str, details: str):
        types = self.case["request_types"]
        if rtype not in types:
            return {"ok": False, "text": "Невідомий тип запиту."}
        text = Text(f"{target} {details}")
        msgs = self.case["messages"]
        have = self.unlocked_ids()
        stage = self.state["stage"]

        matched = [r for r in self.case["rules"] if r["type"] == rtype and rule_matches(r, text, details)]
        blocked_msg = None
        all_unlocked = bool(matched)
        result = None
        for r in matched:
            if r["unlock"] and all(u in have for u in r["unlock"]):
                continue
            all_unlocked = False
            if stage < r["stage"]:
                blocked_msg = blocked_msg or msgs.get(r.get("stage_msg", "stage_locked"))
                continue
            if r.get("requires_all") and not all(x in have for x in r["requires_all"]):
                blocked_msg = blocked_msg or r.get("blocked")
                continue
            if r.get("requires_any") and not any(x in have for x in r["requires_any"]):
                blocked_msg = blocked_msg or r.get("blocked")
                continue
            if r.get("response"):
                result = {"ok": True, "text": r["response"], "new": []}
            else:
                new = self._unlock(r["unlock"], player)
                titles = ", ".join(f"{i} «{self.docs[i]['title']}»" for i in new)
                result = {"ok": True, "text": f"До справи додано: {titles}.", "new": new}
            break

        if result is None:
            if blocked_msg:
                result = {"ok": False, "text": blocked_msg}
            elif matched and all_unlocked:
                result = {"ok": False, "text": msgs["already"] if rtype != "INT" else msgs["repeat"]}
            elif rtype in ("TEL", "EXP") and stage < 2:
                result = {"ok": False, "text": msgs["stage_locked"]}
            elif rtype == "INT":
                person = self._find_person(text)
                if person is None:
                    result = {"ok": False, "text": msgs["unknown_person"]}
                else:
                    result = {"ok": False, "text": self.case["person_refusals"].get(person, msgs["repeat"])}
            else:
                result = {"ok": False, "text": msgs[rtype]}

        self._log({"kind": "request", "player": player, "type": rtype, "type_label": types[rtype]["label"],
                   "target": target, "details": details, **result})
        self.save()
        return result

    def _find_person(self, text: Text):
        for p in self.case["persons"]:
            if any(keyword_hit(a, text) for a in p["aliases"]):
                return p["id"]
        return None

    # ---------- питання ----------
    def available_question(self):
        for q in self.case["questions"]:
            if q["id"] not in self.state["solved"]:
                return q if q["stage"] <= self.state["stage"] else None
        return None

    def answer(self, player: str, qid: str, answers: dict):
        q = self.questions.get(qid)
        if not q or q["id"] in self.state["solved"] or q["stage"] > self.state["stage"]:
            return {"ok": False, "text": "Це питання зараз недоступне."}

        answers = {f["id"]: str(answers.get(f["id"]) or "")[:1000] for f in q["fields"]}
        passed = True
        points = 0.0
        verdicts = []          # відгук гравцям: що переконало, а що ні — без правильних відповідей
        for f in q["fields"]:
            good, pts = self.judge.field(f, answers[f["id"]])
            points += pts
            if f.get("required") and not good:
                passed = False
            verdicts.append({"label": f["label"], "ok": good, "required": bool(f.get("required"))})

        if passed:
            text = q["success"]
        elif q.get("fail_penalty") and not verdicts[0]["ok"]:
            text = q["fail"]       # фінал: помилились з головним — обшук нічого не дав
        else:
            text = q["fail"] + " " + "; ".join(
                f"{'✓' if v['ok'] else '✗'} {v['label']}" for v in verdicts if v["required"])
        self._log({"kind": "answer", "player": player, "question": qid, "title": q["title"], "ok": passed,
                   "text": text, "answers": answers})
        if not passed:
            self.state["attempts"][qid] = self.state["attempts"].get(qid, 0) + 1
            self.state["penalty"] += q.get("fail_penalty", WRONG_ANSWER_PENALTY)
            self.save()
            return {"ok": False, "text": text, "verdicts": verdicts}

        self.state["solved"][qid] = {"by": player, "at": time.time(), "answers": answers, "points": points}
        self.state["stage"] = q["stage"] + 1
        new = []
        if self.state["stage"] == 4:
            new = self._unlock(self.case["final_documents"], "Обшук")
            self.state["finished_at"] = time.time()
        self.save()
        return {"ok": True, "text": q["success"], "new": new, "verdicts": verdicts}

    def hint(self, player: str, qid: str):
        q = self.questions.get(qid)
        if not q:
            return {"ok": False}
        n = self.state["hints"].get(qid, 0)
        if n >= len(q["hints"]):
            return {"ok": False, "text": "Підказок більше немає."}
        self.state["hints"][qid] = n + 1
        self.state["penalty"] += HINT_PENALTY
        self._log({"kind": "hint", "player": player, "question": qid, "text": q["hints"][n]})
        self.save()
        return {"ok": True}

    # ---------- нотатки ----------
    def add_note(self, player, text):
        text = text.strip()[:2000]
        if not text:
            return
        nid = max([n["id"] for n in self.state["notes"]], default=0) + 1
        self.state["notes"].append({"id": nid, "player": player, "text": text, "at": time.time(), "pinned": False})
        self.save()

    def delete_note(self, nid):
        self.state["notes"] = [n for n in self.state["notes"] if n["id"] != nid]
        self.save()

    def toggle_pin(self, nid):
        for n in self.state["notes"]:
            if n["id"] == nid:
                n["pinned"] = not n["pinned"]
        self.save()

    # ---------- рахунок і публічний стан ----------
    def score(self):
        pts = sum(s["points"] for s in self.state["solved"].values())
        return round(pts - self.state["penalty"], 1)

    def rank(self):
        if "Q3" not in self.state["solved"]:
            return None
        s = self.score()
        return next(r["title"] for r in self.questions["Q3"]["ranks"] if s >= r["min"])

    def public_state(self, online):
        """Те, що бачать гравці. Правила, відповіді й назви закритих документів сюди не потрапляють."""
        q = self.available_question()
        hints = {qid: self.questions[qid]["hints"][:n] for qid, n in self.state["hints"].items()}
        return {
            "title": self.case["title"],
            "subtitle": self.case["subtitle"],
            "stage": self.state["stage"],
            "stage_name": self.case["stage_names"][str(self.state["stage"])],
            "categories": self.case["categories"],
            "documents": [{**self.docs[u["id"]], "at": u["at"], "by": u["by"]} for u in self.state["unlocked"]],
            "request_types": self.case["request_types"],
            "log": self.state["log"][-200:],
            "notes": self.state["notes"],
            "question": None if not q else {k: q[k] for k in ("id", "title", "intro")} | {
                "fields": [{k: f[k] for k in ("id", "label", "kind", "placeholder", "multiline") if k in f}
                           for f in q["fields"]],
                "hints_total": len(q["hints"]),
                "attempts": self.state["attempts"].get(q["id"], 0),
            },
            "hints": hints,
            "solved": {k: {"by": v["by"], "at": v["at"]} for k, v in self.state["solved"].items()},
            "started_at": self.state["started_at"],
            "finished_at": self.state["finished_at"],
            "score": self.score(),
            "rank": self.rank(),
            "online": online,
        }
