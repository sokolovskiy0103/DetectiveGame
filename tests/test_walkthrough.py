"""Наскрізна перевірка: повне проходження справи через рушій + перевірка, що всі документи існують.

    uv run python -m pytest tests -q      (або просто: uv run python tests/test_walkthrough.py)
"""
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import app.engine as engine  # noqa: E402

DOCS = engine.ROOT / "documents" / "html"


def new_game():
    engine.SAVE_PATH = Path(tempfile.mkdtemp()) / "save.json"
    return engine.Game()


def req(g, t, target, details=""):
    return g.request("тест", t, target, details)


def test_all_documents_have_html_and_photos_exist_or_are_known():
    g = new_game()
    for d in g.case["documents"]:
        assert (DOCS / f"{d['id']}.html").exists(), d["id"]
    for rule in g.case["rules"]:
        for u in rule["unlock"]:
            assert u in g.docs, u


def test_stage_gates():
    g = new_game()
    assert not req(g, "TEL", "+380 67 214 08 51")["ok"]
    assert not req(g, "EXP", "СМЕ, уточнення часу смерті")["ok"]
    r = req(g, "INT", "Іваненко Петро")
    assert not r["ok"] and "не фігурує" in r["text"]
    assert not req(g, "INT", "Гнатів")["ok"]                     # без пред'явлення — повтор
    assert not req(g, "INT", "Кульчицький", "білінг")["ok"] or "B10" in g.unlocked_ids()


def test_full_walkthrough():
    g = new_game()
    # Етап 1 — хибні сліди доступні одразу
    assert req(g, "ORG", "Смакуй")["new"] == ["B02"]
    assert req(g, "INT", "Оліфіренко Денис")["new"] == ["B03"]
    assert req(g, "VEH", "BC 77")["new"] == ["B09"]                # латиницею
    assert set(req(g, "INT", "Кульчицький Тарас")["new"]) == {"B10", "B10b"}
    assert set(req(g, "INT", "Кульчицький", "номер авто ВС 7741 НК")["new"]) == {"B11", "B11a", "B12"}
    assert req(g, "INT", "Дзюба Соломія", "Кравець бачив вас о 8:30")["new"] == ["B05"]
    assert req(g, "INT", "Кравець", "жінка в пальті о 19:58, підбори")["new"] == ["B19"]
    assert req(g, "DB", "Сенишин Максим Андрійович")["new"] == ["B15a"]
    assert req(g, "DB", "Гнатів Вікторія")["new"] == ["B15e"]
    assert req(g, "INT", "Максим Сенишин", "протокол комендантської 00:03")["new"] == ["B13"]

    # Q1 — неправильно (пастка), потім правильно
    q1_bad = {"what": "Його вбили", "weapon": "статуетка Феміди",
              "why": "Перекинутий стілець — були сліди боротьби"}          # лише один (хибний) аргумент
    r = g.answer("тест", "Q1", q1_bad)
    assert not r["ok"] and "✗ Які факти" in r["text"] and "✓ Чим" in r["text"]
    q1 = {"what": "Вбивство, а не падіння", "weapon": "бронзовою фігуркою Феміди зі столу",
          "why": "На столі пилове коло від статуетки, а капець лежить біля столу за 3 метри від тіла"}
    assert g.answer("тест", "Q1", q1)["ok"]
    assert g.state["stage"] == 2

    # Етап 2
    assert req(g, "TEL", "067-214-08-51")["new"] == ["B01"]
    assert req(g, "TEL", "+380677007741")["new"] == ["B08"]
    assert req(g, "TEL", "0639054418")["new"] == ["B20"]
    assert req(g, "TEL", "+380 98 512 30 07")["new"] == ["B21"]
    assert not req(g, "TEL", "+380 50 377 19 62")["ok"]            # Вікторія — лише етап 3
    assert req(g, "EXP", "судово-медична: уточнити час смерті")["new"] == ["B04"]
    assert req(g, "EXP", "комп'ютерно-технічна, ПК контори")["new"] == ["B07"]
    assert req(g, "EXP", "дактилоскопічна")["new"] == ["B18"]
    assert req(g, "ORG", "Рушай", "поїздки Сенишина Максима")["new"] == ["B14"]
    assert req(g, "ADR", "вул. Мулярська, 3")["new"] == ["B17"]
    assert req(g, "INT", "Максим", "бар Дим, покер у Бойка")["new"] == ["B16"]
    assert req(g, "ORG", "Лікарня №2", "Дзюба Ганна, швидка")["new"] == ["B06"]
    assert req(g, "INT", "Соломія Дзюба", "довіреність Петренка, бланк НОН 112487")["new"] == ["B05b"]
    assert not req(g, "ORG", "Лікарня № 2", "Гнатів, СКУД")["ok"]  # етап 3

    assert not g.answer("тест", "Q2", {"time": "22:00–02:00", "sms": "Ні", "where": "лікарня"})["ok"]
    q2 = {"time": "між 19:15 і 21:00", "sms": "Ні, він на той час уже був мертвий",
          "where": "у лікарні на Буковецькій", "knock": "син, Максим"}
    assert g.answer("тест", "Q2", q2)["ok"]
    assert g.state["stage"] == 3

    # Етап 3
    assert req(g, "ORG", "ЛМЛШД №2", "Гнатів В.О., СКУД і паркінг")["new"] == ["C01"]
    assert req(g, "INT", "Білик Оксана")["new"] == ["C02"]
    assert req(g, "TEL", "+380 50 377 19 62")["new"] == ["C03"]
    assert req(g, "ORG", "Спадковий реєстр", "заповіт Сенишина")["new"] == ["C04"]
    assert req(g, "INT", "Мацюк Ірина")["new"] == ["C05"]
    assert req(g, "INT", "Гнатів Вікторія", "білінг: Центр-14 18:20–19:58, пальто на реєстраторі")["new"] == ["C06"]
    assert not req(g, "INT", "Гнатів Вікторія", "білінг")["ok"]   # уже допитано

    assert not g.answer("тест", "Q3", {"who": "Максим Сенишин"})["ok"]
    assert not g.answer("тест", "Q3", {"who": "Гнатів разом із Кульчицьким"})["ok"]   # двоє — не приймається
    # штраф: Q1 −0.5, Q2 −0.5, Q3 −2 −2 = −5 → для перевірки рахунку далі не важливо
    q3 = {"who": "Вікторія Гнатів", "weapon": "Феміда", "time": "близько пів на восьму",
          "motive": "Новий заповіт 14.11 позбавляв її квартири, а ще борг Кульчицькому",
          "sms": "Гнатів, з лікарні", "search": "Багажник її Škoda і шафка в ординаторській",
          "money": "Максим", "heater": "помічниця Соломія", "window": "Кульчицький"}
    r = g.answer("тест", "Q3", q3)
    assert r["ok"] and set(r["new"]) == {"D01", "D02", "D03", "D04"}
    # 9 (Q3) + 1 (бонус Q2) − 0.5 (Q1) − 0.5 (Q2) − 2 − 2 (Q3) = 5
    assert g.score() == 5.0, g.score()
    assert g.rank() == "Суд повернув справу на доопрацювання"

    pub = g.public_state([])
    assert "rules" not in pub and all("correct" not in f for q in [pub["question"] or {}] for f in q.get("fields", []))


def test_fuzzy_names_and_cases():
    from app.matcher import Text, keyword_hit
    assert keyword_hit("кульчицьк", Text("Кульчицкий Тарас"))          # російське написання
    assert keyword_hit("дзюба", Text("допитати Дзюбу"))                 # відмінок
    assert keyword_hit("кравець", Text("Кравця"))
    assert keyword_hit("оліфіренк", Text("Олефіренко"))                 # одна помилка
    assert keyword_hit("гнатів", Text("Гнатов"))
    assert not keyword_hit("гнатів", Text("Гнатюк П."))                 # власник Pajero — інша людина
    assert not keyword_hit("#672140851", Text("067 214 08 50"))         # номери — лише точно
    g = new_game()
    assert req(g, "DB", "Кульчицкий")["new"] == ["B15b"]
    assert req(g, "DB", "Гнатюк")["ok"] is False


def test_judge_times_and_persons():
    from app.judge import parse_times
    t = lambda s: sorted(parse_times(s))
    assert t("між 19 і 21") == [19 * 60, 21 * 60]
    assert t("19:15–21:00") == [19 * 60 + 15, 21 * 60]
    assert t("пів на восьму") == [19 * 60 + 30]
    assert t("о восьмій вечора") == [20 * 60]
    assert t("12.11 о 19.30, через 1–2 години після вечері, рана 9 см") == [19 * 60 + 30]
    g = new_game()
    q3 = {f["id"]: f for f in g.questions["Q3"]["fields"]}
    assert g.judge.field(q3["who"], "Вікторія Олегівна")[0]
    assert g.judge.field(q3["who"], "цивільна дружина, лікарка")[0]
    assert not g.judge.field(q3["who"], "Кульчицький")[0]
    assert g.judge.field(q3["motive"], "заповіт і борг")[1] == 2
    assert g.judge.field(q3["motive"], "ревнощі")[1] == 0
    assert not g.judge.field(q3["search"], "квартира Максима")[0]


def test_keys_appear_in_earlier_documents():
    """Ключі запитів (номери, назви) мають бути видимі в документах, що відкриваються раніше."""
    text = lambda ids: " ".join((DOCS / f"{i}.html").read_text(encoding="utf-8") for i in ids)
    start = text(["A0" + str(i) for i in range(1, 10)] + ["A10", "A11", "A12", "A13", "A14"])
    for key in ["Смакуй", "214 08 51", "905 44 18", "512 30 07", "377 19 62", "ВС 77", "Кульчицьк", "Буковецьк"]:
        assert key in start, key
    assert "700 77 41" in text(["B01"])
    assert "Оліфіренко" in text(["B02"])
    assert "Мулярська" in text(["B14"])
    assert re.search(r"Мацюк|І\.М\.", text(["B07", "C04"]))
    assert "Білик" in text(["C01"])


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
