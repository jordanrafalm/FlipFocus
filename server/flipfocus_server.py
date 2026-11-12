#!/usr/bin/env python3
"""Self-hosted FlipFocus community pilot API. Python 3.11+, standard library only.

Local focus remains independent. This service stores volunteered weekly totals, never
sensor readings, tasks, or individual completion timestamps. Deploy behind HTTPS.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


PRIVACY_VERSION = 1
PRIVACY_TEXT = (
    "Rooms receive only voluntarily submitted weekly focus totals. LIVE and rankings are "
    "off by default and require separate account and room consent. Organizations see only "
    "the previous completed UTC week, never personal rankings or LIVE; totals remain hidden "
    "unless at least three people contributed. "
    "You may leave or delete your account; your local streak is unaffected."
)
LIVE_TTL_SECONDS = 90
MAX_BODY_BYTES = 16_384
ADMIN_TOKEN = os.environ.get("FLIPFOCUS_ADMIN_TOKEN", "")
SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, nickname TEXT NOT NULL,
 timezone TEXT NOT NULL, live_opt_in INTEGER NOT NULL DEFAULT 0,
 ranking_opt_in INTEGER NOT NULL DEFAULT 0, nudge_opt_in INTEGER NOT NULL DEFAULT 0,
 quiet_start_minute INTEGER NOT NULL DEFAULT 1320, quiet_end_minute INTEGER NOT NULL DEFAULT 480
);
CREATE TABLE IF NOT EXISTS companies (
 id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL
);
CREATE TABLE IF NOT EXISTS company_memberships (
 user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 company_id TEXT NOT NULL REFERENCES companies(id), job_title TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS rooms (
 id TEXT PRIMARY KEY, owner_id TEXT REFERENCES users(id) ON DELETE SET NULL,
 name TEXT NOT NULL, kind TEXT NOT NULL, invite_code TEXT UNIQUE NOT NULL,
 goal_minutes INTEGER NOT NULL, invitation_duration_minutes INTEGER NOT NULL,
 privacy_version INTEGER NOT NULL, closed INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS memberships (
 room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 role TEXT NOT NULL, privacy_version INTEGER NOT NULL,
 live_opt_in INTEGER NOT NULL DEFAULT 0, ranking_opt_in INTEGER NOT NULL DEFAULT 0,
 live_until INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(room_id,user_id)
);
CREATE TABLE IF NOT EXISTS weekly_totals (
 room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 week TEXT NOT NULL, minutes INTEGER NOT NULL, sessions INTEGER NOT NULL,
 PRIMARY KEY(room_id,user_id,week)
);
CREATE TABLE IF NOT EXISTS completion_keys (
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 session_hash TEXT NOT NULL, week TEXT NOT NULL, minutes INTEGER NOT NULL,
 PRIMARY KEY(user_id,session_hash)
);
CREATE TABLE IF NOT EXISTS completion_rooms (
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 session_hash TEXT NOT NULL, room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
 PRIMARY KEY(user_id,session_hash,room_id),
 FOREIGN KEY(user_id,session_hash) REFERENCES completion_keys(user_id,session_hash) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS nudges (
 id TEXT PRIMARY KEY, room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
 sender_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 created_at INTEGER NOT NULL, declined INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS nudge_blocks (
 recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 sender_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 PRIMARY KEY(recipient_id,sender_id)
);
CREATE TABLE IF NOT EXISTS reports (
 id TEXT PRIMARY KEY, room_id TEXT REFERENCES rooms(id) ON DELETE CASCADE,
 reporter_id TEXT REFERENCES users(id) ON DELETE SET NULL,
 reason TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending'
);
"""


class ApiError(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code
        super().__init__(code)


def text_field(body: dict, key: str, maximum: int, default=None) -> str:
    value = body.get(key, default)
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ApiError(400, f"invalid_{key}")
    value = value.strip()
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ApiError(400, f"invalid_{key}")
    return value


def integer_field(body: dict, key: str, minimum: int, maximum: int, default=None) -> int:
    value = body.get(key, default)
    if type(value) is not int or not minimum <= value <= maximum:
        raise ApiError(400, f"invalid_{key}")
    return value


def bool_field(body: dict, key: str) -> bool:
    if type(body.get(key)) is not bool:
        raise ApiError(400, f"invalid_{key}")
    return body[key]


def valid_timezone(value: str) -> str:
    try:
        ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError):
        raise ApiError(400, "invalid_timezone") from None
    return value


class CommunityService:
    """Serialized SQLite transactions make membership, limits and idempotency atomic."""

    def __init__(self, database: str | Path = ":memory:", clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        if str(database) != ":memory:":
            path = Path(database)
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
            os.close(descriptor)
            path.chmod(0o600)
        self.db = sqlite3.connect(str(database), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA secure_delete=ON")
        self.db.execute("PRAGMA busy_timeout=5000")
        version = self.db.execute("PRAGMA user_version").fetchone()[0]
        if version > 1:
            self.db.close()
            raise RuntimeError("Database was created by a newer FlipFocus server")
        self.db.executescript(SCHEMA)
        self.db.executemany("INSERT OR IGNORE INTO companies(id,name) VALUES(?,?)", [
            ("acme", "Acme Polska"), ("allegro", "Allegro"), ("cdprojekt", "CD PROJEKT"),
            ("google", "Google Polska"), ("microsoft", "Microsoft Polska"),
            ("orange", "Orange Polska"), ("pwc", "PwC Polska"), ("startup", "Inna firma"),
        ])
        self.db.execute("PRAGMA user_version=1")
        self.db.commit()

    def close(self):
        self.db.close()

    def handle(self, method: str, path: str, body: dict | None = None, token: str | None = None):
        if body is None:
            body = {}
        if not isinstance(body, dict):
            raise ApiError(400, "json_object_required")
        with self.lock, self.db:
            if method == "GET" and path == "/health":
                return {"status": "ok", "api_version": 1}
            if method == "POST" and path == "/v1/register":
                return self.register(body)
            if method == "GET" and path.startswith("/v1/companies"):
                return self.search_companies(path)
            if method == "POST" and path == "/v1/admin/companies":
                if not ADMIN_TOKEN or not hmac.compare_digest(token or "", ADMIN_TOKEN):
                    raise ApiError(401, "admin_authentication_required")
                return self.add_company(body)
            user = self.authenticate(token)
            if method == "GET" and path == "/v1/company":
                return self.company_stats(user)
            if path == "/v1/me":
                if method == "GET":
                    return self.profile(user)
                if method == "DELETE":
                    self.db.execute("UPDATE rooms SET closed=1 WHERE owner_id=?", (user["id"],))
                    self.db.execute("DELETE FROM users WHERE id=?", (user["id"],))
                    return {"deleted": True}
            if path == "/v1/me/export" and method == "GET":
                rooms = [{key: room[key] for key in ("id", "name", "kind", "closed", "role", "privacy_version", "live_opt_in", "ranking_opt_in")}
                         for room in self.list_rooms(user)["rooms"]]
                return {"profile": self.profile(user), "rooms": rooms,
                        "weekly_totals": [dict(row) for row in self.db.execute(
                            "SELECT room_id,week,minutes,sessions FROM weekly_totals WHERE user_id=?", (user["id"],))]}
            if path == "/v1/me/preferences" and method == "PATCH":
                return self.preferences(user, body)
            if path == "/v1/rooms":
                if method == "GET":
                    return self.list_rooms(user)
                if method == "POST":
                    return self.create_room(user, body)
            if path == "/v1/rooms/join" and method == "POST":
                return self.join_room(user, body)
            if path.startswith("/v1/rooms/invite/") and method == "GET":
                code = path.removeprefix("/v1/rooms/invite/")
                room = self.db.execute("SELECT * FROM rooms WHERE invite_code=? AND closed=0", (code,)).fetchone()
                if not room:
                    raise ApiError(404, "invitation_unavailable")
                return self.invitation(room)
            if path == "/v1/nudges" and method == "GET":
                return self.list_nudges(user)
            match = re.fullmatch(r"/v1/nudges/([a-f0-9]+)/decline", path)
            if match and method == "POST":
                return self.decline_nudge(user, match[1])
            match = re.fullmatch(r"/v1/nudge-blocks/([a-f0-9]+)", path)
            if match and method == "DELETE":
                self.db.execute("DELETE FROM nudge_blocks WHERE recipient_id=? AND sender_id=?", (user["id"], match[1]))
                return {"allowed": True}
            match = re.fullmatch(r"/v1/rooms/([a-f0-9]+)(?:/([a-z-]+))?", path)
            if match:
                return self.room_action(user, method, match[1], match[2], body)
            raise ApiError(404, "not_found")

    def register(self, body):
        if type(body.get("privacy_version")) is not int or body["privacy_version"] != PRIVACY_VERSION:
            raise ApiError(400, "privacy_consent_required")
        nickname = text_field(body, "nickname", 32, "Focus friend")
        tz = valid_timezone(text_field(body, "timezone", 80, "UTC"))
        company_id = body.get("company_id")
        job_title = body.get("job_title")
        if company_id is not None:
            company_id = text_field(body, "company_id", 64)
            job_title = text_field(body, "job_title", 80)
            if not self.db.execute("SELECT 1 FROM companies WHERE id=?", (company_id,)).fetchone():
                raise ApiError(404, "company_not_found")
        user_id, token = secrets.token_hex(16), secrets.token_urlsafe(32)
        self.db.execute("INSERT INTO users(id,token_hash,nickname,timezone) VALUES(?,?,?,?)",
                        (user_id, hashlib.sha256(token.encode()).hexdigest(), nickname, tz))
        if company_id is not None:
            self.db.execute("INSERT INTO company_memberships(user_id,company_id,job_title) VALUES(?,?,?)",
                            (user_id, company_id, job_title))
        return {"user_id": user_id, "token": token, "nickname": nickname}

    def search_companies(self, path):
        query = parse_qs(urlsplit(path).query).get("q", [""])[0].strip().lower()
        if len(query) > 80:
            raise ApiError(400, "invalid_query")
        rows = self.db.execute("SELECT id,name FROM companies WHERE lower(name) LIKE ? ORDER BY name LIMIT 20",
                               (f"%{query}%",)).fetchall()
        return [{"id": row["id"], "name": row["name"]} for row in rows]

    def add_company(self, body):
        if "companies" in body:
            companies = body["companies"]
            if not isinstance(companies, list) or not 1 <= len(companies) <= 1000:
                raise ApiError(400, "invalid_companies")
            imported = []
            for item in companies:
                if not isinstance(item, dict):
                    raise ApiError(400, "invalid_company")
                company_id, name = self.company_fields(item)
                exists = self.db.execute("SELECT 1 FROM companies WHERE id=? OR name=?", (company_id, name)).fetchone()
                if exists:
                    continue
                self.db.execute("INSERT INTO companies(id,name) VALUES(?,?)", (company_id, name))
                imported.append({"id": company_id, "name": name})
            return {"imported": imported, "count": len(imported)}
        company_id, name = self.company_fields(body)
        try:
            self.db.execute("INSERT INTO companies(id,name) VALUES(?,?)", (company_id, name))
        except sqlite3.IntegrityError:
            raise ApiError(409, "company_exists") from None
        return {"id": company_id, "name": name}

    @staticmethod
    def company_fields(body):
        name = text_field(body, "name", 120)
        company_id = body.get("id", "")
        if not isinstance(company_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,63}", company_id):
            raise ApiError(400, "invalid_company_id")
        return company_id, name

    def authenticate(self, token):
        if not isinstance(token, str) or not 32 <= len(token) <= 128:
            raise ApiError(401, "authentication_required")
        user = self.db.execute(
            "SELECT u.*,cm.company_id,c.name AS company_name,cm.job_title FROM users u "
            "LEFT JOIN company_memberships cm ON cm.user_id=u.id LEFT JOIN companies c ON c.id=cm.company_id "
            "WHERE u.token_hash=?", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        if not user:
            raise ApiError(401, "authentication_required")
        return user

    @staticmethod
    def profile(user):
        keys = user.keys()
        company_id = user["company_id"] if "company_id" in keys else None
        company_name = user["company_name"] if "company_name" in keys else None
        job_title = user["job_title"] if "job_title" in keys else None
        return {"user_id": user["id"], "nickname": user["nickname"], "timezone": user["timezone"],
                "live_opt_in": bool(user["live_opt_in"]), "ranking_opt_in": bool(user["ranking_opt_in"]),
                "nudge_opt_in": bool(user["nudge_opt_in"]), "quiet_start_minute": user["quiet_start_minute"],
                "quiet_end_minute": user["quiet_end_minute"], "company_id": company_id,
                "company_name": company_name, "job_title": job_title}

    def company_stats(self, user):
        if not user["company_id"]:
            raise ApiError(404, "company_not_selected")
        week = self.week(previous=True)
        company = self.db.execute("SELECT id,name FROM companies WHERE id=?", (user["company_id"],)).fetchone()
        totals = self.db.execute(
            "SELECT COUNT(DISTINCT cm.user_id), COUNT(DISTINCT CASE WHEN ck.session_hash IS NOT NULL THEN cm.user_id END), "
            "SUM(ck.minutes), COUNT(ck.session_hash) FROM company_memberships cm "
            "LEFT JOIN completion_keys ck ON ck.user_id=cm.user_id AND ck.week=? WHERE cm.company_id=?",
            (week, user["company_id"])).fetchone()
        visible = totals[1] >= 3
        return {"id": company["id"], "name": company["name"], "week": week,
                "employee_count": totals[0], "contributing_employees": totals[1],
                "completed_minutes": totals[2] if visible else None,
                "completed_sessions": totals[3] if visible else None}

    def preferences(self, user, body):
        allowed = {"nickname", "timezone", "live_opt_in", "ranking_opt_in", "nudge_opt_in", "quiet_start_minute", "quiet_end_minute"}
        if not body or set(body) - allowed:
            raise ApiError(400, "invalid_preferences")
        for key in body:
            if key.endswith("opt_in"):
                value = int(bool_field(body, key))
            elif key.endswith("minute"):
                value = integer_field(body, key, 0, 1439)
            elif key == "timezone":
                value = valid_timezone(text_field(body, key, 80))
            else:
                value = text_field(body, key, 32)
            self.db.execute(f"UPDATE users SET {key}=? WHERE id=?", (value, user["id"]))
        if body.get("live_opt_in") is False:
            self.db.execute("UPDATE memberships SET live_until=0 WHERE user_id=?", (user["id"],))
        return self.profile(self.db.execute(
            "SELECT u.*,cm.company_id,c.name AS company_name,cm.job_title FROM users u "
            "LEFT JOIN company_memberships cm ON cm.user_id=u.id LEFT JOIN companies c ON c.id=cm.company_id "
            "WHERE u.id=?", (user["id"],)).fetchone())

    def week(self, previous=False):
        day = datetime.fromtimestamp(self.clock(), timezone.utc).date()
        return (day - timedelta(days=day.weekday() + (7 if previous else 0))).isoformat()

    def create_room(self, user, body):
        if type(body.get("privacy_version")) is not int or body["privacy_version"] != PRIVACY_VERSION:
            raise ApiError(400, "privacy_consent_required")
        name = text_field(body, "name", 64)
        kind = body.get("kind", "friends")
        if kind not in ("friends", "organization"):
            raise ApiError(400, "invalid_kind")
        goal = integer_field(body, "goal_minutes", 15, 1_000_000, 500)
        duration = integer_field(body, "invitation_duration_minutes", 15, 25, 25)
        if duration not in (15, 25):
            raise ApiError(400, "invalid_invitation_duration_minutes")
        count = self.db.execute("SELECT COUNT(*) FROM rooms WHERE owner_id=? AND closed=0", (user["id"],)).fetchone()[0]
        if count >= 20:
            raise ApiError(429, "room_limit")
        room_id, code = secrets.token_hex(16), secrets.token_urlsafe(12)
        self.db.execute("INSERT INTO rooms VALUES(?,?,?,?,?,?,?,?,0)",
                        (room_id, user["id"], name, kind, code, goal, duration, PRIVACY_VERSION))
        self.db.execute("INSERT INTO memberships(room_id,user_id,role,privacy_version) VALUES(?,?,?,?)",
                        (room_id, user["id"], "owner", PRIVACY_VERSION))
        return self.room_detail(user, room_id)

    @staticmethod
    def invitation(room):
        return {"name": room["name"], "kind": room["kind"], "privacy_version": room["privacy_version"],
                "privacy_text": PRIVACY_TEXT, "invitation_duration_minutes": room["invitation_duration_minutes"]}

    def join_room(self, user, body):
        code = text_field(body, "code", 80)
        room = self.db.execute("SELECT * FROM rooms WHERE invite_code=? AND closed=0", (code,)).fetchone()
        if not room:
            raise ApiError(404, "invitation_unavailable")
        if type(body.get("privacy_version")) is not int or body["privacy_version"] != room["privacy_version"]:
            raise ApiError(400, "privacy_consent_required")
        existing = self.db.execute("SELECT 1 FROM memberships WHERE room_id=? AND user_id=?", (room["id"], user["id"])).fetchone()
        if existing:
            return self.room_detail(user, room["id"])
        count = self.db.execute("SELECT COUNT(*) FROM memberships WHERE room_id=?", (room["id"],)).fetchone()[0]
        if count >= 200:
            raise ApiError(429, "room_full")
        self.db.execute("INSERT OR IGNORE INTO memberships(room_id,user_id,role,privacy_version) VALUES(?,?,?,?)",
                        (room["id"], user["id"], "participant", room["privacy_version"]))
        return self.room_detail(user, room["id"])

    def membership(self, user, room_id):
        member = self.db.execute("SELECT * FROM memberships WHERE room_id=? AND user_id=?", (room_id, user["id"])).fetchone()
        if not member:
            raise ApiError(404, "room_unavailable")
        room = self.db.execute("SELECT * FROM rooms WHERE id=?", (room_id,)).fetchone()
        return room, member

    def list_rooms(self, user):
        ids = [row[0] for row in self.db.execute("SELECT room_id FROM memberships WHERE user_id=?", (user["id"],))]
        return {"rooms": [self.room_detail(user, room_id) for room_id in ids]}

    def room_detail(self, user, room_id):
        room, member = self.membership(user, room_id)
        # A finalized reporting window prevents polling from exposing when someone focuses.
        week = self.week(previous=room["kind"] == "organization")
        totals = self.db.execute("SELECT SUM(minutes),SUM(sessions),COUNT(*) FROM weekly_totals WHERE room_id=? AND week=?",
                                 (room_id, week)).fetchone()
        visible = room["kind"] != "organization" or totals[2] >= 3
        result = {"id": room["id"], "name": room["name"], "kind": room["kind"], "closed": bool(room["closed"]),
                  "role": member["role"], "goal_minutes": room["goal_minutes"], "week": week,
                  "invitation_duration_minutes": room["invitation_duration_minutes"],
                  "privacy_version": room["privacy_version"], "privacy_text": PRIVACY_TEXT,
                  "member_count": self.db.execute("SELECT COUNT(*) FROM memberships WHERE room_id=?", (room_id,)).fetchone()[0],
                  "live_opt_in": bool(member["live_opt_in"]), "ranking_opt_in": bool(member["ranking_opt_in"]),
                  "aggregate": {"visible": visible, "completed_minutes": (totals[0] or 0) if visible else None,
                                "completed_sessions": (totals[1] or 0) if visible else None}, "ranking": [], "live": []}
        if member["role"] == "owner" and not room["closed"]:
            result["invite_code"] = room["invite_code"]
        if room["kind"] == "friends" and not room["closed"]:
            result["ranking"] = [dict(row) for row in self.db.execute(
                "SELECT u.id AS user_id,u.nickname,t.minutes AS completed_minutes "
                "FROM memberships m JOIN users u ON u.id=m.user_id "
                "JOIN weekly_totals t ON t.user_id=m.user_id AND t.room_id=m.room_id "
                "WHERE m.room_id=? AND t.week=? AND m.ranking_opt_in=1 AND u.ranking_opt_in=1 "
                "ORDER BY t.minutes DESC,u.id", (room_id, week))]
            result["live"] = [dict(row) for row in self.db.execute(
                "SELECT u.id AS user_id,u.nickname FROM memberships m JOIN users u ON u.id=m.user_id "
                "WHERE m.room_id=? AND m.live_opt_in=1 AND u.live_opt_in=1 AND m.live_until>?", (room_id, int(self.clock())))]
        return result

    def room_action(self, user, method, room_id, action, body):
        room, member = self.membership(user, room_id)
        if action is None and method == "GET":
            return self.room_detail(user, room_id)
        if action == "leave" and method == "POST":
            if member["role"] == "owner" and not room["closed"]:
                raise ApiError(409, "owner_must_close_room")
            self.db.execute("DELETE FROM memberships WHERE room_id=? AND user_id=?", (room_id, user["id"]))
            return {"left": True}
        if action == "report" and method == "POST":
            reason = body.get("reason")
            if reason not in ("spam", "harassment", "privacy", "other"):
                raise ApiError(400, "invalid_reason")
            existing = self.db.execute("SELECT id FROM reports WHERE room_id=? AND reporter_id=? AND status='pending'",
                                       (room_id, user["id"])).fetchone()
            report_id = existing[0] if existing else secrets.token_hex(16)
            if not existing:
                self.db.execute("INSERT INTO reports(id,room_id,reporter_id,reason) VALUES(?,?,?,?)", (report_id, room_id, user["id"], reason))
            return {"report_id": report_id, "status": "pending"}
        if action == "close" and method == "POST":
            if member["role"] != "owner":
                raise ApiError(403, "owner_required")
            self.db.execute("UPDATE rooms SET closed=1 WHERE id=?", (room_id,))
            self.db.execute("UPDATE memberships SET live_until=0 WHERE room_id=?", (room_id,))
            return self.room_detail(user, room_id)
        if room["closed"]:
            raise ApiError(409, "room_closed")
        if action == "preferences" and method == "PATCH":
            if not body or set(body) - {"live_opt_in", "ranking_opt_in"}:
                raise ApiError(400, "invalid_preferences")
            if room["kind"] == "organization" and any(body.values()):
                raise ApiError(403, "organization_individual_visibility_disabled")
            for key in body:
                value = int(bool_field(body, key))
                self.db.execute(f"UPDATE memberships SET {key}=? WHERE room_id=? AND user_id=?", (value, room_id, user["id"]))
            if body.get("live_opt_in") is False:
                self.db.execute("UPDATE memberships SET live_until=0 WHERE room_id=? AND user_id=?", (room_id, user["id"]))
            return self.room_detail(user, room_id)
        if action == "heartbeat" and method == "POST":
            focusing = bool_field(body, "focusing")
            if focusing and (room["kind"] != "friends" or not user["live_opt_in"] or not member["live_opt_in"]):
                raise ApiError(403, "live_consent_required")
            until = int(self.clock()) + LIVE_TTL_SECONDS if focusing else 0
            self.db.execute("UPDATE memberships SET live_until=? WHERE room_id=? AND user_id=?", (until, room_id, user["id"]))
            return {"live": focusing, "ttl_seconds": LIVE_TTL_SECONDS if focusing else 0}
        if action == "completions" and method == "POST":
            return self.complete(user, room_id, body)
        if action == "nudges" and method == "POST":
            return self.nudge(user, room, body)
        raise ApiError(404, "not_found")

    def complete(self, user, room_id, body):
        session_id = text_field(body, "session_id", 128)
        minutes = integer_field(body, "minutes", 1, 180)
        session_hash = hashlib.sha256(session_id.encode()).hexdigest()
        previous = self.db.execute("SELECT * FROM completion_keys WHERE user_id=? AND session_hash=?", (user["id"], session_hash)).fetchone()
        week = previous["week"] if previous else self.week()
        if previous and previous["minutes"] != minutes:
            raise ApiError(409, "completion_conflict")
        already_shared = self.db.execute("SELECT 1 FROM completion_rooms WHERE user_id=? AND session_hash=? AND room_id=?",
                                         (user["id"], session_hash, room_id)).fetchone()
        if previous and week != self.week() and not already_shared:
            raise ApiError(409, "completion_window_closed")
        if not previous:
            total = self.db.execute("SELECT COALESCE(SUM(minutes),0) FROM completion_keys WHERE user_id=? AND week=?", (user["id"], week)).fetchone()[0]
            if total + minutes > 10_080:
                raise ApiError(429, "weekly_focus_limit")
            self.db.execute("INSERT INTO completion_keys VALUES(?,?,?,?)", (user["id"], session_hash, week, minutes))
        inserted = self.db.execute("INSERT OR IGNORE INTO completion_rooms VALUES(?,?,?)", (user["id"], session_hash, room_id)).rowcount
        if inserted:
            self.db.execute("INSERT INTO weekly_totals VALUES(?,?,?,?,1) ON CONFLICT(room_id,user_id,week) "
                            "DO UPDATE SET minutes=minutes+excluded.minutes,sessions=sessions+1", (room_id, user["id"], week, minutes))
        return {"accepted": True, "duplicate": not bool(inserted), "room": self.room_detail(user, room_id)}

    def nudge(self, sender, room, body):
        if room["kind"] != "friends":
            raise ApiError(403, "nudges_disabled_for_organizations")
        recipient_id = text_field(body, "recipient_id", 64)
        recipient = self.db.execute("SELECT u.* FROM users u JOIN memberships m ON m.user_id=u.id WHERE m.room_id=? AND u.id=?",
                                    (room["id"], recipient_id)).fetchone()
        # Return the same denial for all recipient consent, membership and blocking failures.
        blocked = self.db.execute("SELECT 1 FROM nudge_blocks WHERE recipient_id=? AND sender_id=?", (recipient_id, sender["id"])).fetchone()
        if not recipient or recipient_id == sender["id"] or not recipient["nudge_opt_in"] or blocked:
            raise ApiError(403, "nudge_unavailable")
        now = int(self.clock())
        local = datetime.fromtimestamp(now, ZoneInfo(recipient["timezone"]))
        minute = local.hour * 60 + local.minute
        start, end = recipient["quiet_start_minute"], recipient["quiet_end_minute"]
        quiet = start <= minute < end if start < end else (minute >= start or minute < end) if start > end else False
        if quiet:
            raise ApiError(403, "nudge_unavailable")
        day_start = int(local.replace(hour=0, minute=0, second=0, microsecond=0).timestamp())
        recent = self.db.execute("SELECT COUNT(*) FROM nudges WHERE recipient_id=? AND created_at>=?", (recipient_id, day_start)).fetchone()[0]
        sender_count = self.db.execute("SELECT COUNT(*) FROM nudges WHERE sender_id=? AND created_at>=?", (sender["id"], now - 86_400)).fetchone()[0]
        previous = self.db.execute("SELECT MAX(created_at) FROM nudges WHERE recipient_id=? AND sender_id=?", (recipient_id, sender["id"])).fetchone()[0]
        if recent >= 3 or sender_count >= 10 or (previous is not None and now - previous < 3_600):
            raise ApiError(429, "nudge_rate_limited")
        nudge_id = secrets.token_hex(16)
        self.db.execute("INSERT INTO nudges(id,room_id,sender_id,recipient_id,created_at) VALUES(?,?,?,?,?)",
                        (nudge_id, room["id"], sender["id"], recipient_id, now))
        self.db.execute("DELETE FROM nudges WHERE created_at<?", (now - 7 * 86_400,))
        return {"nudge_id": nudge_id, "sent": True}

    def list_nudges(self, user):
        if not user["nudge_opt_in"]:
            return {"nudges": []}
        return {"nudges": [dict(row) for row in self.db.execute(
            "SELECT n.id,n.room_id,n.sender_id,u.nickname AS sender_nickname "
            "FROM nudges n JOIN users u ON u.id=n.sender_id JOIN rooms r ON r.id=n.room_id "
            "WHERE n.recipient_id=? AND n.declined=0 AND n.created_at>? AND r.closed=0 "
            "AND EXISTS(SELECT 1 FROM memberships m WHERE m.room_id=n.room_id AND m.user_id=n.sender_id) "
            "AND EXISTS(SELECT 1 FROM memberships m WHERE m.room_id=n.room_id AND m.user_id=n.recipient_id) "
            "ORDER BY n.created_at DESC", (user["id"], int(self.clock()) - 86_400))]}

    def decline_nudge(self, user, nudge_id):
        nudge = self.db.execute("SELECT * FROM nudges WHERE id=? AND recipient_id=?", (nudge_id, user["id"])).fetchone()
        if not nudge:
            raise ApiError(404, "nudge_unavailable")
        self.db.execute("UPDATE nudges SET declined=1 WHERE recipient_id=? AND sender_id=?", (user["id"], nudge["sender_id"]))
        self.db.execute("INSERT OR IGNORE INTO nudge_blocks VALUES(?,?)", (user["id"], nudge["sender_id"]))
        return {"declined": True}


class RequestLimiter:
    """In-memory network-abuse protection; no IP addresses or request logs are persisted."""
    def __init__(self):
        self.salt = secrets.token_bytes(32)
        self.calls = defaultdict(deque)
        self.lock = threading.Lock()

    def allow(self, address, registration):
        key = (hashlib.sha256(self.salt + address.encode()).digest(), registration)
        now = time.monotonic()
        window, limit = (3600, 10) if registration else (60, 240)
        with self.lock:
            calls = self.calls[key]
            while calls and calls[0] <= now - window:
                calls.popleft()
            if len(calls) >= limit:
                return False
            calls.append(now)
            if len(self.calls) > 10_000:
                self.calls = defaultdict(deque, {k: v for k, v in self.calls.items() if v and v[-1] > now - 3600})
            return True


def make_handler(service):
    limiter = RequestLimiter()

    class Handler(BaseHTTPRequestHandler):
        server_version = "FlipFocus/1"

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *_):
            pass  # Never log tokens, invitation codes, user IDs or private request paths.

        def do_GET(self):
            self.dispatch()

        def do_POST(self):
            self.dispatch()

        def do_PATCH(self):
            self.dispatch()

        def do_DELETE(self):
            self.dispatch()

        def dispatch(self):
            try:
                parsed_path = urlsplit(self.path)
                path = parsed_path.path + (f"?{parsed_path.query}" if parsed_path.query and parsed_path.path == "/v1/companies" else "")
                if not limiter.allow(self.client_address[0], path == "/v1/register"):
                    raise ApiError(429, "request_rate_limited")
                if self.headers.get("Transfer-Encoding"):
                    raise ApiError(400, "transfer_encoding_unsupported")
                if len(self.headers.get_all("Content-Length", [])) > 1:
                    raise ApiError(400, "invalid_content_length")
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise ApiError(400, "invalid_content_length") from None
                if length < 0 or length > MAX_BODY_BYTES:
                    raise ApiError(413, "request_too_large")
                if length and self.headers.get_content_type() != "application/json":
                    raise ApiError(415, "json_required")
                try:
                    body = json.loads(self.rfile.read(length), object_pairs_hook=unique_json_object,
                                      parse_constant=invalid_json_constant) if length else {}
                except (ValueError, UnicodeDecodeError, RecursionError):
                    raise ApiError(400, "invalid_json") from None
                auth = self.headers.get("Authorization", "")
                token = auth.removeprefix("Bearer ") if auth.startswith("Bearer ") else None
                result = service.handle(self.command, path, body, token)
                self.respond(200, result)
            except ApiError as error:
                self.respond(error.status, {"error": error.code})
            except (TimeoutError, ConnectionError):
                self.close_connection = True
            except sqlite3.Error:
                self.respond(503, {"error": "storage_unavailable"})

        def respond(self, status, body):
            payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(payload)

    return Handler


def unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def invalid_json_constant(_):
    raise ValueError("Non-finite JSON number")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--database", default="server/data/community.sqlite")
    args = parser.parse_args()
    service = CommunityService(args.database)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(service))
    print(f"FlipFocus community API listening on {args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        service.close()


if __name__ == "__main__":
    main()
