"""Перевірка мобільного UI на емуляції телефону (Playwright + локальний Edge). Сервер має бути запущений.

    uv run python tests/ui_mobile.py [http://127.0.0.1:8000]

Нічого не змінює в стані гри: лише відкриває шторку, вкладки й документи.
"""
import sys

from playwright.sync_api import sync_playwright

URL = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000").rstrip("/") + "/?name=UI-тест"


def swipe(cdp, x0, y, x1, steps=8):
    pts = lambda x: [{"x": x, "y": y}]
    cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": pts(x0)})
    for i in range(1, steps + 1):
        cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": pts(x0 + (x1 - x0) * i / steps)})
    cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})


def main():
    failures = []

    def check(name, cond):
        print(("ok   " if cond else "FAIL ") + name)
        if not cond:
            failures.append(name)

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge")
        ctx = browser.new_context(**p.devices["Pixel 7"])
        page = ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(URL)
        page.wait_for_selector("#doc-list .doc-item")
        page.wait_for_timeout(800)
        cdp = ctx.new_cdp_session(page)
        is_open = lambda: page.evaluate("document.body.classList.contains('drawer-open')")
        mtab = lambda: page.evaluate("document.body.dataset.mtab")

        check("стартова вкладка — документ", mtab() == "doc")
        page.tap("#menu-btn"); page.wait_for_timeout(300)
        check("☰ на вкладці «Документ» відкриває шторку", is_open())
        page.tap("#backdrop", position={"x": 380, "y": 400}); page.wait_for_timeout(300)
        check("тап по затемненню закриває", not is_open())

        swipe(cdp, 26, 450, 220); page.wait_for_timeout(300)
        check("свайп не перевів браузер на іншу сторінку", "UI" in page.url)
        check("свайп від лівого краю над документом відкриває", is_open())
        swipe(cdp, 250, 450, 30); page.wait_for_timeout(300)
        check("свайп вліво закриває", not is_open())

        page.tap("#menu-btn"); page.wait_for_timeout(300)
        page.tap('#doc-list .doc-item[data-doc="A03"]'); page.wait_for_timeout(500)
        check("вибір документа закриває шторку", not is_open())
        check("…і відкриває його", "/doc/A03" in page.evaluate("document.querySelector('.pane iframe').src"))

        for tab in ("request", "question", "notes", "log", "doc"):
            page.tap(f'#bottom-nav [data-mtab="{tab}"]'); page.wait_for_timeout(200)
            check(f"нижня панель → {tab}", mtab() == tab)

        page.tap('#bottom-nav [data-mtab="request"]'); page.wait_for_timeout(200)
        page.tap("#menu-btn"); page.wait_for_timeout(300)
        check("☰ на іншій вкладці відкриває шторку", is_open())
        page.keyboard.press("Escape"); page.wait_for_timeout(300)
        check("Esc закриває", not is_open())
        page.tap("#req-target"); page.wait_for_timeout(200)
        check("тап у поле запиту не перемикає вкладку", mtab() == "request")

        check("немає JS-помилок", not errors)
        for e in errors:
            print("   ", e)
        browser.close()

    print(f"\n{'УСЕ ГАРАЗД' if not failures else 'ПРОВАЛЕНО: ' + str(len(failures))}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
