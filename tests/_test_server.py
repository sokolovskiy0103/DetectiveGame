"""Тестовий сервер гри з тимчасовим збереженням (не чіпає data/save.json).  uv run python tests/_test_server.py [port]"""
import sys, tempfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import app.engine as engine
engine.SAVE_PATH = Path(tempfile.mkdtemp()) / "save.json"
import uvicorn
from app import server
server.game.__init__()
uvicorn.run(server.app, host="127.0.0.1", port=int(sys.argv[1]) if len(sys.argv) > 1 else 8001, log_level="warning")
