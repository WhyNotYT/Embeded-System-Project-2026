#!/usr/bin/env python3
"""
Competitive Heart Rate Monitoring Game
Receives BPM data + thumbnail frames from native Android app(s).
Flask server with /game/home, /admin, and /api endpoints.

Architecture change: heart-rate detection has moved to the Android app.
The server receives POST /api/device_update from each phone and drives
the game state based on the reported phase/BPM. OpenCV and scipy are
no longer needed.
"""

import json
import threading
import time
from collections import deque
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional
from flask import Flask, Response, jsonify, request

# ─── Config ────────────────────────────────────────────────────────────────────

LEADERBOARD_FILE    = "leaderboard.json"
LEADERBOARD_TOP_N   = 5

# How long (seconds) we keep a player's last-seen state before marking offline.
DEVICE_TIMEOUT_S    = 10

app = Flask(__name__)

# ─── Data structures ──────────────────────────────────────────────────────────

@dataclass
class PlayerReading:
    player_id: int
    bpm: float
    timestamp: str
    name: str = ""

@dataclass
class LeaderboardEntry:
    name: str
    bpm: float
    timestamp: str
    rank: int = 0

@dataclass
class DeviceState:
    """Live state pushed from one Android device."""
    player_id: int
    phase: str = "idle"          # idle | finger_detected | measuring | result
    finger_present: bool = False
    current_bpm: float = 0.0
    measurement_progress: float = 0.0
    last_seen: float = 0.0       # time.time() of last update
    bpm_history: deque = field(default_factory=lambda: deque(maxlen=100))
    # Latest 128×128 JPEG thumbnail from the device (raw bytes).
    latest_frame: bytes = b""
    error: str = ""

    @property
    def online(self) -> bool:
        return (time.time() - self.last_seen) < DEVICE_TIMEOUT_S

# ─── Game state ───────────────────────────────────────────────────────────────

class GameState:
    def __init__(self):
        # Devices are registered dynamically when they POST /api/device_update.
        # Keyed by player_id (int).
        self.devices: dict[int, DeviceState] = {}
        self.phase = "idle"         # idle | waiting | measuring | result | enter_name
        self.round_readings: list[PlayerReading] = []
        self.last_winner: Optional[PlayerReading] = None
        self.pending_name_for_bpm: float = 0.0
        self.leaderboard: list[LeaderboardEntry] = []
        self.game_message = "Place finger on camera to start!"
        self.measurement_progress: float = 0.0
        self.current_measuring_player: int = 0
        self.lock = threading.Lock()
        self._load_leaderboard()

    # ── Leaderboard ───────────────────────────────────────────────────────────

    def _load_leaderboard(self):
        if Path(LEADERBOARD_FILE).exists():
            try:
                with open(LEADERBOARD_FILE) as f:
                    data = json.load(f)
                self.leaderboard = [LeaderboardEntry(**e) for e in data]
                self._rank_leaderboard()
            except Exception as e:
                print(f"[WARN] Could not load leaderboard: {e}")

    def _save_leaderboard(self):
        with open(LEADERBOARD_FILE, "w") as f:
            json.dump([asdict(e) for e in self.leaderboard], f, indent=2)

    def _rank_leaderboard(self):
        self.leaderboard.sort(key=lambda e: e.bpm, reverse=True)
        for i, e in enumerate(self.leaderboard):
            e.rank = i + 1

    def add_to_leaderboard(self, name: str, bpm: float, timestamp: str):
        entry = LeaderboardEntry(name=name, bpm=bpm, timestamp=timestamp)
        self.leaderboard.append(entry)
        self._rank_leaderboard()
        self.leaderboard = self.leaderboard[:20]
        self._rank_leaderboard()
        self._save_leaderboard()

    def is_top5(self, bpm: float) -> bool:
        if len(self.leaderboard) < LEADERBOARD_TOP_N:
            return True
        return bpm > self.leaderboard[LEADERBOARD_TOP_N - 1].bpm

    # ── API dict ──────────────────────────────────────────────────────────────

    def to_api_dict(self):
        devices_data = []
        for pid in sorted(self.devices):
            dev = self.devices[pid]
            devices_data.append({
                "player_id": int(dev.player_id),
                "online": bool(dev.online),
                "phase": str(dev.phase),
                "finger_present": bool(dev.finger_present),
                "measuring": bool(dev.phase == "measuring"),
                "current_bpm": float(round(dev.current_bpm, 1)),
                "measurement_progress": float(round(dev.measurement_progress, 2)),
                "bpm_history": [float(x) for x in dev.bpm_history],
                "has_frame": bool(len(dev.latest_frame) > 0),
                "error": str(dev.error),
            })

        def reading_dict(r):
            return {"player_id": int(r.player_id), "bpm": float(r.bpm),
                    "timestamp": str(r.timestamp), "name": str(r.name)}

        def lb_dict(e):
            return {"name": str(e.name), "bpm": float(e.bpm),
                    "timestamp": str(e.timestamp), "rank": int(e.rank)}

        return {
            "phase": str(self.phase),
            "two_device_mode": bool(len(self.devices) >= 2),
            "message": str(self.game_message),
            "measurement_progress": float(round(self.measurement_progress, 2)),
            "current_measuring_player": int(self.current_measuring_player),
            "devices": devices_data,
            "round_readings": [reading_dict(r) for r in self.round_readings],
            "last_winner": reading_dict(self.last_winner) if self.last_winner else None,
            "leaderboard": [lb_dict(e) for e in self.leaderboard],
            "pending_name_entry": bool(self.phase == "enter_name"),
            "pending_bpm": float(round(self.pending_name_for_bpm, 1)),
            "timestamp": datetime.now().isoformat(),
        }


game = GameState()

# ─── Device update endpoint ────────────────────────────────────────────────────
#
# The Android app POSTs here on every valley detection (~every heartbeat) and
# at the start/end of a measurement.  The request is multipart/form-data with:
#   status  – JSON blob  (always present)
#   frame   – JPEG bytes (present when a thumbnail is attached, ~every 2nd valley)

@app.route("/api/device_update", methods=["POST"])
def device_update():
    # ── Parse status JSON ─────────────────────────────────────────────────────
    ct = request.content_type or ""
    if "multipart" in ct:
        status_str = request.form.get("status", "{}")
        frame_file = request.files.get("frame")
        frame_bytes = frame_file.read() if frame_file else b""
    else:
        status_str = request.get_data(as_text=True) or "{}"
        frame_bytes = b""

    try:
        status = json.loads(status_str)
    except Exception:
        return jsonify({"error": "bad json"}), 400

    player_id = int(status.get("player_id", 1))

    with game.lock:
        # Register device on first contact.
        if player_id not in game.devices:
            game.devices[player_id] = DeviceState(player_id=player_id)
            print(f"[DEVICE] Player {player_id} registered")

        dev = game.devices[player_id]
        dev.phase               = status.get("phase", "idle")
        dev.finger_present      = bool(status.get("finger_present", False))
        dev.current_bpm         = float(status.get("current_bpm", 0.0))
        dev.measurement_progress= float(status.get("measurement_progress", 0.0))
        dev.last_seen           = time.time()
        if frame_bytes:
            dev.latest_frame = frame_bytes
        if dev.current_bpm > 0:
            dev.bpm_history.append(round(dev.current_bpm, 1))

    # Trigger game-state transitions on the background thread.
    threading.Thread(target=_handle_device_event,
                     args=(player_id, status.copy()),
                     daemon=True).start()

    return jsonify({"ok": True})

# ─── Frame endpoint ────────────────────────────────────────────────────────────
#
# Browser <img src="/frame/1"> polls this to show the latest thumbnail.
# We serve the raw JPEG the phone sent; no re-encoding needed.

@app.route("/frame/<int:player_id>")
def frame(player_id):
    with game.lock:
        dev = game.devices.get(player_id)
        jpg = dev.latest_frame if dev else b""

    if not jpg:
        # Return a tiny 1×1 black JPEG as placeholder.
        jpg = (b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
               b"\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t"
               b"\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a"
               b"\x1f\x1e\x1d\x1a\x1c\x1c $.\' \",#\x1c\x1c(7),01444\x1f'9=82<.342\x1e"
               b"\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00"
               b"\xff\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00"
               b"\x00\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08"
               b"\t\n\x0b\xff\xc4\x00\xb5\x10\x00\x02\x01\x03\x03\x02\x04\x03"
               b"\x05\x05\x04\x04\x00\x00\x01}\x01\x02\x03\x00\x04\x11\x05\x12"
               b"!1A\x06\x13Qa\x07\"q\x142\x81\x91\xa1\x08#B\xb1\xc1\x15R\xd1"
               b"\xf0$3br\x82\t\n\x16\x17\x18\x19\x1a%&'()*456789:CDEFGHIJSTU"
               b"VWXYZcdefghijstuvwxyz\x83\x84\x85\x86\x87\x88\x89\x8a\x92\x93"
               b"\x94\x95\x96\x97\x98\x99\x9a\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9"
               b"\xaa\xb2\xb3\xb4\xb5\xb6\xb7\xb8\xb9\xba\xc2\xc3\xc4\xc5\xc6"
               b"\xc7\xc8\xc9\xca\xd2\xd3\xd4\xd5\xd6\xd7\xd8\xd9\xda\xe1\xe2"
               b"\xe3\xe4\xe5\xe6\xe7\xe8\xe9\xea\xf1\xf2\xf3\xf4\xf5\xf6\xf7"
               b"\xf8\xf9\xfa\xff\xda\x00\x08\x01\x01\x00\x00?\x00\xfb\xd4P\x00"
               b"\x00\x00\x00\x1f\xff\xd9")

    return Response(jpg, mimetype="image/jpeg",
                    headers={"Cache-Control": "no-cache, no-store"})

# ─── Game event handler ────────────────────────────────────────────────────────

# Tracks which players have already been recorded in the current round to
# avoid double-counting repeat "result" events.
_round_recorded: set = set()
_game_round_lock = threading.Lock()

def _handle_device_event(player_id: int, status: dict):
    """
    Called (in a daemon thread) whenever a device posts an update.
    Drives game transitions:
      - Any device reports 'measuring' → update progress
      - Device reports 'result' with valid BPM → record reading, maybe end round
    """
    phase = status.get("phase", "idle")
    bpm   = float(status.get("current_bpm", 0.0))

    with game.lock:
        game_phase = game.phase

    # ── While idle: start round when any device starts measuring ─────────────
    if phase == "measuring" and game_phase == "idle":
        with game.lock:
            if game.phase == "idle":   # double-check inside lock
                game.phase = "measuring"
                game.game_message = f"Player {player_id}: hold still..."
                game.current_measuring_player = player_id
                game.round_readings = []
                _round_recorded.clear()
                print(f"[GAME] Round started by Player {player_id}")

    # ── Progress update ───────────────────────────────────────────────────────
    if phase == "measuring":
        progress = float(status.get("measurement_progress", 0.0))
        with game.lock:
            game.measurement_progress = progress
            game.current_measuring_player = player_id

    # ── Result: device finished measuring ────────────────────────────────────
    if phase == "result" and bpm > 0:
        with _game_round_lock:
            if player_id in _round_recorded:
                return  # already recorded this player
            _round_recorded.add(player_id)

        reading = PlayerReading(
            player_id=player_id,
            bpm=bpm,
            timestamp=datetime.now().isoformat()
        )
        with game.lock:
            game.round_readings.append(reading)
            num_devices = len(game.devices)
            all_done = (len(game.round_readings) >= num_devices)

        print(f"[GAME] Player {player_id} result: {bpm:.1f} BPM")

        if all_done or num_devices == 1:
            _finish_round()

def _finish_round():
    with game.lock:
        readings = list(game.round_readings)

    valid = [r for r in readings if r.bpm > 0]
    if not valid:
        with game.lock:
            game.phase = "idle"
            game.game_message = "No valid readings. Try again!"
        return

    winner = max(valid, key=lambda r: r.bpm)

    with game.lock:
        game.last_winner = winner
        game.phase = "result"
        if len(valid) >= 2:
            others = [r for r in valid if r.player_id != winner.player_id]
            other_str = f" (Player {others[0].player_id}: {others[0].bpm:.0f} BPM)" \
                        if others else ""
            game.game_message = \
                f"🏆 Player {winner.player_id} wins! {winner.bpm:.0f} BPM{other_str}"
        else:
            game.game_message = \
                f"Player {winner.player_id}: {winner.bpm:.0f} BPM"

    print(f"[GAME] Round finished — winner Player {winner.player_id} {winner.bpm:.0f} BPM")

    time.sleep(3)

    if game.is_top5(winner.bpm):
        with game.lock:
            game.phase = "enter_name"
            game.pending_name_for_bpm = winner.bpm
            game.game_message = \
                f"🔥 New top-5 record! {winner.bpm:.0f} BPM — Enter your name!"
    else:
        time.sleep(4)
        with game.lock:
            game.phase = "idle"
            game.game_message = "Place finger on camera to start!"

# ─── Flask routes ─────────────────────────────────────────────────────────────

@app.route("/api")
def api():
    with game.lock:
        data = game.to_api_dict()
    return jsonify(data)

@app.route("/api/submit_name", methods=["POST"])
def api_submit_name():
    body = request.get_json(silent=True) or {}
    name = body.get("name", "").strip()[:32]
    if not name:
        return jsonify({"error": "name required"}), 400
    with game.lock:
        if game.phase != "enter_name":
            return jsonify({"error": "not in name-entry phase"}), 400
        bpm = game.pending_name_for_bpm
        ts  = datetime.now().isoformat()
    game.add_to_leaderboard(name, bpm, ts)
    with game.lock:
        game.phase = "idle"
        game.game_message = f"🏅 {name} added to leaderboard! ({bpm:.0f} BPM)"
    return jsonify({"ok": True})

@app.route("/api/reset", methods=["POST"])
def api_reset():
    with game.lock:
        game.phase = "idle"
        game.game_message = "Place finger on camera to start!"
        game.round_readings = []
        game.measurement_progress = 0.0
    _round_recorded.clear()
    return jsonify({"ok": True})

@app.route("/api/start_round", methods=["POST"])
def api_start_round():
    if game.phase not in ("idle",):
        return jsonify({"error": f"Round in progress (phase: {game.phase})"}), 400
    with game.lock:
        game.phase = "waiting"
        game.game_message = "Place finger on camera!"
        game.round_readings = []
    _round_recorded.clear()
    return jsonify({"ok": True, "message": "Waiting for devices"})

# ── /game/home ─────────────────────────────────────────────────────────────────

HOME_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>💓 BPM BATTLE</title>
<link href="https://fonts.googleapis.com/css2?family=Bebas+Neue&family=Space+Mono:ital,wght@0,400;0,700;1,400&display=swap" rel="stylesheet">
<style>
  :root {
    --red:#ff1a1a;--red2:#ff6b6b;--bg:#0a0a0a;--card:#111;--border:#222;
    --text:#f0f0f0;--dim:#666;--gold:#ffd700;--green:#00ff88;
  }
  *{box-sizing:border-box;margin:0;padding:0}
  body{background:var(--bg);color:var(--text);font-family:'Space Mono',monospace;min-height:100vh;overflow-x:hidden}
  header{display:flex;align-items:center;justify-content:space-between;padding:1.2rem 2rem;border-bottom:1px solid var(--border);background:rgba(255,26,26,.05)}
  header h1{font-family:'Bebas Neue',sans-serif;font-size:2.8rem;letter-spacing:4px;color:var(--red);text-shadow:0 0 20px rgba(255,26,26,.5)}
  #phase-badge{font-size:.75rem;font-weight:700;letter-spacing:2px;text-transform:uppercase;padding:.3rem .8rem;border-radius:2px;background:var(--border);color:var(--dim);transition:all .3s}
  #phase-badge.measuring{background:var(--red);color:#fff;animation:blink 1s infinite}
  #phase-badge.result{background:var(--gold);color:#000}
  #phase-badge.enter_name{background:var(--green);color:#000}
  @keyframes blink{0%,100%{opacity:1}50%{opacity:.5}}
  .main-grid{display:grid;grid-template-columns:1fr 1fr;grid-template-rows:auto auto;gap:1.5rem;padding:1.5rem 2rem;max-width:1400px;margin:0 auto}
  @media(max-width:900px){.main-grid{grid-template-columns:1fr}}
  .card{background:var(--card);border:1px solid var(--border);border-radius:4px;padding:1.2rem;position:relative;overflow:hidden}
  .card-title{font-family:'Bebas Neue',sans-serif;font-size:1.1rem;letter-spacing:3px;color:var(--dim);margin-bottom:1rem;text-transform:uppercase}
  .cameras{grid-column:1/-1;display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:1rem}
  .camera-card{position:relative}
  /* Thumbnail image from Android device */
  .camera-feed{width:100%;aspect-ratio:1/1;background:#000;object-fit:cover;border-radius:2px;border:2px solid var(--border);display:block;image-rendering:pixelated}
  .camera-feed.measuring{border-color:var(--red);box-shadow:0 0 15px rgba(255,26,26,.4)}
  .camera-overlay{position:absolute;top:0;left:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;pointer-events:none}
  .camera-bpm{font-family:'Bebas Neue',sans-serif;font-size:3.5rem;color:var(--red);text-shadow:0 0 30px rgba(255,26,26,.8),0 2px 4px rgba(0,0,0,.9);letter-spacing:2px}
  .player-label{font-family:'Bebas Neue',sans-serif;font-size:.9rem;letter-spacing:3px;padding:.3rem .8rem;margin-bottom:.5rem;display:inline-block;border-radius:2px}
  .p1{background:var(--red);color:#fff}
  .p2{background:#1a6fff;color:#fff}
  .graph-container{position:relative;height:80px}
  canvas.graph{width:100%;height:80px}
  #message-bar{grid-column:1/-1;text-align:center;font-family:'Bebas Neue',sans-serif;font-size:1.6rem;letter-spacing:4px;padding:1rem;background:rgba(255,26,26,.08);border:1px solid rgba(255,26,26,.2);border-radius:4px;transition:all .5s;min-height:60px}
  #progress-wrap{grid-column:1/-1;display:none}
  #progress-wrap.active{display:block}
  .progress-bar-bg{background:var(--border);border-radius:2px;height:8px;overflow:hidden}
  #progress-bar{height:8px;background:linear-gradient(90deg,var(--red),var(--red2));width:0%;transition:width .5s;border-radius:2px}
  .progress-label{font-size:.7rem;color:var(--dim);margin-bottom:.4rem;letter-spacing:1px;text-transform:uppercase}
  .leaderboard-section{grid-column:1/-1}
  table.lb{width:100%;border-collapse:collapse;font-size:.85rem}
  table.lb th{font-family:'Bebas Neue',sans-serif;letter-spacing:2px;font-size:.9rem;color:var(--dim);text-align:left;padding:.4rem .8rem;border-bottom:1px solid var(--border)}
  table.lb td{padding:.6rem .8rem;border-bottom:1px solid #1a1a1a;transition:background .2s}
  table.lb tr:hover td{background:rgba(255,255,255,.03)}
  .rank-badge{font-family:'Bebas Neue',sans-serif;font-size:1.1rem;width:2rem;display:inline-block;text-align:center}
  .rank-1{color:var(--gold)}.rank-2{color:#c0c0c0}.rank-3{color:#cd7f32}
  .bpm-val{color:var(--red);font-weight:700}
  #name-modal{display:none;position:fixed;inset:0;background:rgba(0,0,0,.85);z-index:100;align-items:center;justify-content:center}
  #name-modal.show{display:flex}
  .modal-box{background:var(--card);border:2px solid var(--red);border-radius:6px;padding:2.5rem 3rem;text-align:center;max-width:440px;width:90%;box-shadow:0 0 60px rgba(255,26,26,.3)}
  .modal-box h2{font-family:'Bebas Neue',sans-serif;font-size:2rem;letter-spacing:4px;color:var(--red);margin-bottom:.5rem}
  .modal-bpm-big{font-family:'Bebas Neue',sans-serif;font-size:4rem;color:var(--gold);letter-spacing:2px}
  #name-input{margin-top:1.5rem;width:100%;background:#1a1a1a;border:1px solid var(--border);color:var(--text);font-family:'Space Mono',monospace;font-size:1.2rem;padding:.7rem 1rem;border-radius:3px;text-align:center;letter-spacing:2px;outline:none;transition:border .2s}
  #name-input:focus{border-color:var(--red)}
  #name-submit{margin-top:1rem;width:100%;background:var(--red);color:#fff;border:none;font-family:'Bebas Neue',sans-serif;font-size:1.3rem;letter-spacing:3px;padding:.8rem;border-radius:3px;cursor:pointer;transition:opacity .2s}
  #name-submit:hover{opacity:.85}
  .heartbeat-icon{display:inline-block;animation:heartbeat 1s ease infinite}
  @keyframes heartbeat{0%,100%{transform:scale(1)}14%{transform:scale(1.2)}28%{transform:scale(1)}42%{transform:scale(1.1)}70%{transform:scale(1)}}
  /* Finger indicator pill */
  .finger-pill{font-size:.65rem;letter-spacing:1px;padding:.15rem .5rem;border-radius:10px;display:inline-block;margin-left:.5rem}
  .finger-on{background:var(--green);color:#000}
  .finger-off{background:var(--border);color:var(--dim)}
</style>
</head>
<body>

<header>
  <h1><span class="heartbeat-icon">💓</span> BPM BATTLE</h1>
  <span id="phase-badge">IDLE</span>
</header>

<div class="main-grid">
  <div id="message-bar">Place finger on camera to start!</div>

  <div id="progress-wrap">
    <div class="progress-label">Measuring heart rate...</div>
    <div class="progress-bar-bg"><div id="progress-bar"></div></div>
  </div>

  <div class="cameras" id="cameras-container"></div>

  <div class="card leaderboard-section">
    <div class="card-title">🏆 Leaderboard</div>
    <table class="lb">
      <thead><tr><th>#</th><th>Name</th><th>BPM</th><th>Date</th></tr></thead>
      <tbody id="lb-body"></tbody>
    </table>
    <p id="lb-empty" style="color:var(--dim);text-align:center;padding:1rem;font-size:.8rem">No records yet. Be the first!</p>
  </div>
</div>

<div id="name-modal">
  <div class="modal-box">
    <h2>🔥 NEW RECORD!</h2>
    <div class="modal-bpm-big" id="modal-bpm">0</div>
    <div style="color:var(--dim);font-size:.75rem;letter-spacing:2px;margin-top:.3rem">BPM</div>
    <input id="name-input" type="text" maxlength="32" placeholder="ENTER YOUR NAME" autocomplete="off">
    <button id="name-submit">SUBMIT TO LEADERBOARD</button>
  </div>
</div>

<script>
// Refresh thumbnails every 500ms (cheap — just a small JPEG)
const thumbTimers = {};

function getOrCreateDevice(pid, labelClass) {
  let el = document.getElementById(`dev-card-${pid}`);
  if (!el) {
    el = document.createElement('div');
    el.id = `dev-card-${pid}`;
    el.className = 'card camera-card';
    el.innerHTML = `
      <span class="player-label ${labelClass}">PLAYER ${pid}</span>
      <span class="finger-pill finger-off" id="finger-${pid}">NO FINGER</span>
      <div style="position:relative">
        <img id="cam-feed-${pid}" class="camera-feed" src="/frame/${pid}?t=0" alt="Feed">
        <div class="camera-overlay"><span id="cam-bpm-${pid}" class="camera-bpm"></span></div>
      </div>
      <div class="graph-container" style="margin-top:.8rem">
        <canvas id="graph-${pid}" class="graph" height="80"></canvas>
      </div>
      <div style="margin-top:.5rem;font-size:.7rem;color:var(--dim)" id="cam-status-${pid}">Waiting...</div>
    `;
    document.getElementById('cameras-container').appendChild(el);

    // Start thumbnail refresh loop for this player
    thumbTimers[pid] = setInterval(() => {
      const img = document.getElementById(`cam-feed-${pid}`);
      if (img) img.src = `/frame/${pid}?t=${Date.now()}`;
    }, 500);
  }
  return el;
}

function drawGraph(pid, history) {
  const canvas = document.getElementById(`graph-${pid}`);
  if (!canvas) return;
  canvas.width = canvas.offsetWidth || 400;
  const ctx = canvas.getContext('2d');
  const w = canvas.width, h = canvas.height;
  ctx.clearRect(0,0,w,h);
  if (!history || history.length < 2) return;
  const vals = history.slice(-50);
  const min = Math.min(...vals)-5, max = Math.max(...vals)+5;
  ctx.beginPath(); ctx.strokeStyle='#ff1a1a'; ctx.lineWidth=2;
  vals.forEach((v,i) => {
    const x = i/(vals.length-1)*w, y = h-(v-min)/(max-min)*h;
    i===0 ? ctx.moveTo(x,y) : ctx.lineTo(x,y);
  });
  ctx.stroke();
  const grad = ctx.createLinearGradient(0,0,0,h);
  grad.addColorStop(0,'rgba(255,26,26,.3)'); grad.addColorStop(1,'rgba(255,26,26,0)');
  ctx.lineTo(w,h); ctx.lineTo(0,h); ctx.closePath();
  ctx.fillStyle=grad; ctx.fill();
}

async function poll() {
  try {
    const res = await fetch('/api');
    const d = await res.json();

    const badge = document.getElementById('phase-badge');
    badge.textContent = d.phase.toUpperCase();
    badge.className = d.phase;

    document.getElementById('message-bar').textContent = d.message;

    const pw = document.getElementById('progress-wrap');
    if (d.phase === 'measuring') {
      pw.classList.add('active');
      document.getElementById('progress-bar').style.width = (d.measurement_progress*100)+'%';
    } else {
      pw.classList.remove('active');
    }

    d.devices.forEach(dev => {
      const cls = dev.player_id===1?'p1':'p2';
      getOrCreateDevice(dev.player_id, cls);

      const feedEl   = document.getElementById(`cam-feed-${dev.player_id}`);
      const bpmEl    = document.getElementById(`cam-bpm-${dev.player_id}`);
      const statEl   = document.getElementById(`cam-status-${dev.player_id}`);
      const fingerEl = document.getElementById(`finger-${dev.player_id}`);

      feedEl.className = 'camera-feed' + (dev.measuring ? ' measuring' : '');
      bpmEl.textContent = dev.current_bpm > 0 ? Math.round(dev.current_bpm) : '';
      fingerEl.textContent  = dev.finger_present ? 'FINGER ON' : 'NO FINGER';
      fingerEl.className    = 'finger-pill ' + (dev.finger_present ? 'finger-on' : 'finger-off');
      statEl.textContent    = dev.online
        ? (dev.measuring ? 'Measuring...' : dev.finger_present ? 'Finger detected' : 'Idle')
        : 'Offline';
      drawGraph(dev.player_id, dev.bpm_history);
    });

    // Leaderboard
    const body  = document.getElementById('lb-body');
    const empty = document.getElementById('lb-empty');
    if (d.leaderboard.length===0) {
      body.innerHTML=''; empty.style.display='block';
    } else {
      empty.style.display='none';
      body.innerHTML = d.leaderboard.map(e => {
        const rc = e.rank<=3?`rank-${e.rank}`:'';
        const medal = e.rank===1?'🥇':e.rank===2?'🥈':e.rank===3?'🥉':e.rank;
        const date = new Date(e.timestamp).toLocaleDateString();
        return `<tr>
          <td><span class="rank-badge ${rc}">${medal}</span></td>
          <td>${e.name}</td>
          <td class="bpm-val">${Math.round(e.bpm)}</td>
          <td style="color:var(--dim);font-size:.75rem">${date}</td>
        </tr>`;
      }).join('');
    }

    const modal = document.getElementById('name-modal');
    if (d.phase==='enter_name') {
      modal.classList.add('show');
      document.getElementById('modal-bpm').textContent = Math.round(d.pending_bpm);
    } else {
      modal.classList.remove('show');
    }
  } catch(e) { console.warn('poll error',e); }
  setTimeout(poll, 600);
}

document.getElementById('name-submit').addEventListener('click', async () => {
  const name = document.getElementById('name-input').value.trim();
  if (!name) return;
  await fetch('/api/submit_name',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});
  document.getElementById('name-input').value='';
  document.getElementById('name-modal').classList.remove('show');
});

document.getElementById('name-input').addEventListener('keydown', e => {
  if (e.key==='Enter') document.getElementById('name-submit').click();
});

poll();
</script>
</body>
</html>
"""

@app.route("/game/home")
def game_home():
    return HOME_HTML

# ── /admin ─────────────────────────────────────────────────────────────────────

ADMIN_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>BPM Battle — Admin</title>
<link href="https://fonts.googleapis.com/css2?family=Bebas+Neue&family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
  :root{--red:#ff1a1a;--bg:#0d0d0d;--card:#141414;--border:#1e1e1e;--text:#e0e0e0;--dim:#555;--green:#00cc66;--yellow:#f5c518}
  *{box-sizing:border-box;margin:0;padding:0}
  body{background:var(--bg);color:var(--text);font-family:'Space Mono',monospace}
  header{padding:1rem 2rem;border-bottom:1px solid var(--border);display:flex;align-items:center;gap:1rem}
  header h1{font-family:'Bebas Neue',sans-serif;font-size:1.8rem;letter-spacing:3px;color:var(--red)}
  .admin-tag{font-size:.65rem;background:var(--red);color:#fff;padding:.2rem .5rem;letter-spacing:2px;border-radius:2px}
  .grid{display:grid;grid-template-columns:1fr 1fr;gap:1rem;padding:1.5rem 2rem;max-width:1200px;margin:0 auto}
  @media(max-width:800px){.grid{grid-template-columns:1fr}}
  .card{background:var(--card);border:1px solid var(--border);border-radius:4px;padding:1rem}
  .card-title{font-family:'Bebas Neue',sans-serif;font-size:1rem;letter-spacing:3px;color:var(--dim);margin-bottom:.8rem}
  .btn{font-family:'Space Mono',monospace;font-size:.75rem;letter-spacing:1px;padding:.5rem 1rem;border:1px solid var(--border);border-radius:3px;cursor:pointer;background:var(--card);color:var(--text);margin:.2rem;transition:all .2s}
  .btn:hover{background:var(--border)}
  .btn.red{background:var(--red);border-color:var(--red);color:#fff}
  .btn.green{background:var(--green);border-color:var(--green);color:#000}
  .status-dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px}
  .dot-on{background:var(--green)}.dot-off{background:var(--dim)}.dot-err{background:var(--red)}
  .feed{width:100%;aspect-ratio:1/1;background:#000;display:block;border-radius:2px;object-fit:cover;image-rendering:pixelated}
  pre#log{background:#000;border:1px solid var(--border);border-radius:3px;padding:.8rem;font-size:.65rem;height:180px;overflow-y:auto;color:#0f0}
  .stat-row{display:flex;justify-content:space-between;padding:.3rem 0;border-bottom:1px solid var(--border);font-size:.75rem}
  .stat-row:last-child{border-bottom:none}
  .stat-label{color:var(--dim)}
  .full{grid-column:1/-1}
</style>
</head>
<body>
<header>
  <h1>BPM BATTLE</h1>
  <span class="admin-tag">ADMIN</span>
</header>

<div class="grid">
  <div class="card">
    <div class="card-title">Controls</div>
    <button class="btn green" onclick="startRound()">▶ Force Start Round</button>
    <button class="btn red" onclick="resetGame()">↺ Reset Game</button>
    <div style="margin-top:.8rem;font-size:.7rem;color:var(--dim)">
      Note: rounds normally auto-start when a device begins measuring.
    </div>
  </div>

  <div class="card">
    <div class="card-title">Game Status</div>
    <div id="stats-container"></div>
    <hr style="border-color:var(--border);margin:.8rem 0">
    <div class="card-title" style="margin-bottom:.4rem">Message</div>
    <div id="game-msg" style="font-size:.8rem;color:var(--yellow)"></div>
  </div>

  <div id="dev-feeds" class="full" style="display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:1rem"></div>

  <div class="card full">
    <div class="card-title">Log</div>
    <pre id="log">> Admin panel loaded\n</pre>
  </div>
</div>

<script>
function log(msg){const el=document.getElementById('log');el.textContent+='>'+msg+'\n';el.scrollTop=el.scrollHeight}

async function startRound(){const r=await fetch('/api/start_round',{method:'POST'});const d=await r.json();log(d.message||d.error||'started')}
async function resetGame(){await fetch('/api/reset',{method:'POST'});log('Game reset')}

const feedTimers={};
function ensureDevFeed(pid){
  let el=document.getElementById(`admin-dev-${pid}`);
  if(!el){
    const wrap=document.createElement('div');
    wrap.className='card';
    wrap.innerHTML=`<div class="card-title">PLAYER ${pid}</div>
      <img id="admin-dev-${pid}" class="feed" src="/frame/${pid}?t=0" alt="">
      <div id="admin-dev-bpm-${pid}" style="font-size:.8rem;margin-top:.4rem;color:#ff1a1a"></div>`;
    document.getElementById('dev-feeds').appendChild(wrap);
    feedTimers[pid]=setInterval(()=>{
      const img=document.getElementById(`admin-dev-${pid}`);
      if(img)img.src=`/frame/${pid}?t=${Date.now()}`;
    },400);
  }
}

async function poll(){
  try{
    const r=await fetch('/api');const d=await r.json();
    document.getElementById('stats-container').innerHTML=[
      ['Phase',d.phase.toUpperCase()],
      ['Devices',d.devices.length],
      ['Progress',Math.round(d.measurement_progress*100)+'%'],
    ].map(([l,v])=>`<div class="stat-row"><span class="stat-label">${l}</span><span>${v}</span></div>`).join('');
    document.getElementById('game-msg').textContent=d.message;
    d.devices.forEach(dev=>{
      ensureDevFeed(dev.player_id);
      const bpmEl=document.getElementById(`admin-dev-bpm-${dev.player_id}`);
      if(bpmEl)bpmEl.textContent=`${dev.online?'●':'○'} P${dev.player_id} — ${dev.current_bpm>0?Math.round(dev.current_bpm)+' BPM':'no reading'} — ${dev.phase}`;
    });
  }catch(e){log('poll error: '+e)}
  setTimeout(poll,800);
}
poll();
</script>
</body>
</html>
"""

@app.route("/admin")
def admin():
    return ADMIN_HTML

# ─── Startup ──────────────────────────────────────────────────────────────────

def startup():
    print("=" * 60)
    print("  BPM BATTLE — Server started (Android-native mode)")
    print("  Heart-rate detection runs on each Android device.")
    print()
    print("  Game:  http://0.0.0.0:5000/game/home")
    print("  Admin: http://0.0.0.0:5000/admin")
    print("  API:   http://0.0.0.0:5000/api")
    print()
    print("  Android devices POST status to: POST /api/device_update")
    print("  Thumbnails served at:           GET  /frame/<player_id>")
    print()
    print("  Set SERVER_URL in MainActivity.java to this machine's LAN IP.")
    print("=" * 60)


if __name__ == "__main__":
    startup()
    app.run(host="0.0.0.0", port=5000, debug=False, threaded=True)