"""Сервер гри. Запуск: uv run python -m app.server [--port 8000] [--reset]

Гравці відкривають у браузері http://<IP цього ПК>:<порт>/ у локальній мережі.
"""
import argparse
import asyncio
import json
import re
import socket
from pathlib import Path

import uvicorn
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles

from app.engine import ROOT, Game
from app.matcher import semantic

DOCS_DIR = ROOT / "documents" / "html"
PHOTOS_DIR = ROOT / "assets" / "photos"
STATIC_DIR = Path(__file__).resolve().parent / "static"

game = Game()
app = FastAPI(title="Detective Game")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


def _photo_index():
    """Яке фото в яких документах використовується — фото віддаємо лише з відкритих документів."""
    index = {}
    for html in DOCS_DIR.glob("*.html"):
        for name in re.findall(r"assets/photos/([\w.-]+)", html.read_text(encoding="utf-8")):
            index.setdefault(name, set()).add(html.stem)
    return index


PHOTO_INDEX = _photo_index()


class Hub:
    def __init__(self):
        self.clients: dict[WebSocket, str] = {}

    def online(self):
        return sorted(set(self.clients.values()))

    async def broadcast(self, event=None):
        payload = json.dumps({"type": "state", "state": game.public_state(self.online()), "event": event}, ensure_ascii=False)
        for ws in list(self.clients):
            try:
                await ws.send_text(payload)
            except Exception:
                self.clients.pop(ws, None)


hub = Hub()
lock = asyncio.Lock()


@app.get("/", response_class=HTMLResponse)
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/doc/_base.css")
def base_css():
    return FileResponse(DOCS_DIR / "_base.css")


@app.get("/doc/{doc_id}")
def document(doc_id: str):
    if doc_id not in game.unlocked_ids():
        raise HTTPException(403, "Документ ще не відкрито")
    return FileResponse(DOCS_DIR / f"{doc_id}.html", media_type="text/html; charset=utf-8")


@app.get("/assets/photos/{name}")
def photo(name: str):
    if not (PHOTO_INDEX.get(name, set()) & game.unlocked_ids()):
        raise HTTPException(403)
    path = PHOTOS_DIR / name
    if not path.exists():
        raise HTTPException(404)
    return FileResponse(path)


@app.get("/api/status")
def status():
    return {"semantic": semantic.state, "semantic_error": semantic.error}


@app.post("/api/reset")
async def reset(request: Request):
    if request.client.host not in ("127.0.0.1", "::1"):
        raise HTTPException(403, "Скинути гру можна лише з комп'ютера-сервера")
    async with lock:
        game.reset()
    await hub.broadcast({"kind": "reset"})
    return {"ok": True}


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    name = (ws.query_params.get("name") or "Слідчий").strip()[:40] or "Слідчий"
    hub.clients[ws] = name
    await hub.broadcast({"kind": "join", "player": name})
    try:
        while True:
            msg = json.loads(await ws.receive_text())
            action = msg.get("action")
            async with lock:
                if action == "request":
                    # семантичний суддя рахує ембедінги — не блокуємо цикл подій
                    res = await asyncio.to_thread(game.request, name, msg.get("type", ""), msg.get("target", ""),
                                                  msg.get("details", ""))
                    event = {"kind": "request", "player": name, **res}
                elif action == "answer":
                    res = game.answer(name, msg.get("question", ""), msg.get("answers", {}))
                    event = {"kind": "answer", "player": name, **res}
                elif action == "hint":
                    game.hint(name, msg.get("question", ""))
                    event = {"kind": "hint", "player": name}
                elif action == "note_add":
                    game.add_note(name, msg.get("text", ""))
                    event = None
                elif action == "note_del":
                    game.delete_note(int(msg.get("id", 0)))
                    event = None
                elif action == "note_pin":
                    game.toggle_pin(int(msg.get("id", 0)))
                    event = None
                else:
                    continue
            await hub.broadcast(event)
    except WebSocketDisconnect:
        pass
    finally:
        hub.clients.pop(ws, None)
        await hub.broadcast({"kind": "leave", "player": name})


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips) or ["127.0.0.1"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--reset", action="store_true", help="почати справу з нуля")
    args = parser.parse_args()
    if args.reset:
        game.reset()
    semantic.start()  # модель розуміння запитів вантажиться у фоні (~5 с); до того працюють ключові слова
    print("\n  Справа «Сходи» — сервер запущено. Гравцям відкрити в браузері:")
    for ip in local_ips():
        print(f"    http://{ip}:{args.port}/")
    print(f"    (на цьому ПК: http://127.0.0.1:{args.port}/)\n")
    uvicorn.run(app, host="0.0.0.0", port=args.port, log_level="warning")
