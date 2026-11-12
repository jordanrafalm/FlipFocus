import json
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from flipfocus_server import ApiError, CommunityService, LIVE_TTL_SECONDS, make_handler
from admin import pending_reports, resolve_report


class CommunityTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 9, 21, 12, tzinfo=timezone.utc).timestamp()
        self.service = CommunityService(clock=lambda: self.now)
        self.owner = self.register("Owner")
        self.friend = self.register("Friend")

    def tearDown(self):
        self.service.close()

    def register(self, nickname):
        return self.service.handle("POST", "/v1/register", {"nickname": nickname, "privacy_version": 1})

    def call(self, user, method, path, body=None):
        return self.service.handle(method, path, body, user["token"])

    def create(self, kind="friends"):
        return self.call(self.owner, "POST", "/v1/rooms", {"name": "Deep work", "kind": kind, "privacy_version": 1})

    def join(self, room, user=None):
        return self.call(user or self.friend, "POST", "/v1/rooms/join", {"code": room["invite_code"], "privacy_version": 1})

    def endpoint(self, room, action=""):
        return f"/v1/rooms/{room['id']}" + (f"/{action}" if action else "")

    def enable_nudge(self):
        self.call(self.friend, "PATCH", "/v1/me/preferences", {"nudge_opt_in": True})

    def assert_api_error(self, status, callback, code=None):
        with self.assertRaises(ApiError) as error:
            callback()
        self.assertEqual(status, error.exception.status)
        if code:
            self.assertEqual(code, error.exception.code)

    def test_private_by_default_and_token_is_hashed(self):
        profile = self.call(self.owner, "GET", "/v1/me")
        self.assertFalse(profile["live_opt_in"])
        self.assertFalse(profile["ranking_opt_in"])
        self.assertFalse(profile["nudge_opt_in"])
        self.assertNotIn("token", profile)
        stored = self.service.db.execute("SELECT token_hash FROM users WHERE id=?", (self.owner["user_id"],)).fetchone()[0]
        self.assertNotEqual(self.owner["token"], stored)
        self.assert_api_error(401, lambda: self.service.handle("GET", "/v1/rooms"))
        self.assert_api_error(401, lambda: self.service.handle("GET", "/v1/rooms", token="x" * 40))

    def test_invitation_preview_consent_and_membership_authorization(self):
        room = self.create()
        preview = self.call(self.friend, "GET", f"/v1/rooms/invite/{room['invite_code']}")
        self.assertEqual(1, preview["privacy_version"])
        self.assertIn("weekly", preview["privacy_text"])
        self.assert_api_error(404, lambda: self.call(self.friend, "GET", self.endpoint(room)))
        self.assert_api_error(400, lambda: self.call(self.friend, "POST", "/v1/rooms/join", {"code": room["invite_code"]}))
        joined = self.join(room)
        self.assertEqual("participant", joined["role"])
        self.assertEqual(2, joined["member_count"])
        self.assertNotIn("invite_code", joined)

    def test_completion_is_atomic_idempotent_and_weekly_only(self):
        room = self.create()
        body = {"session_id": "local-session", "minutes": 25}
        first = self.call(self.owner, "POST", self.endpoint(room, "completions"), body)
        duplicate = self.call(self.owner, "POST", self.endpoint(room, "completions"), body)
        self.assertFalse(first["duplicate"])
        self.assertTrue(duplicate["duplicate"])
        self.assertEqual({"visible": True, "completed_minutes": 25, "completed_sessions": 1}, duplicate["room"]["aggregate"])
        self.assert_api_error(409, lambda: self.call(self.owner, "POST", self.endpoint(room, "completions"), {**body, "minutes": 45}))
        self.assertEqual("2026-09-21", first["room"]["week"])
        columns = [row[1] for row in self.service.db.execute("PRAGMA table_info(completion_keys)")]
        self.assertNotIn("completed_at", columns)
        self.assertNotIn("session_id", columns)

    def test_concurrent_replay_counts_once(self):
        room = self.create()
        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(lambda _: self.call(self.owner, "POST", self.endpoint(room, "completions"),
                                                           {"session_id": "concurrent", "minutes": 15}), range(20)))
        self.assertEqual(1, sum(not item["duplicate"] for item in results))
        self.assertEqual(15, self.call(self.owner, "GET", self.endpoint(room))["aggregate"]["completed_minutes"])

    def test_replay_after_week_rollover_stays_in_original_week(self):
        room = self.create()
        body = {"session_id": "session", "minutes": 25}
        self.call(self.owner, "POST", self.endpoint(room, "completions"), body)
        self.now += 7 * 86_400
        duplicate = self.call(self.owner, "POST", self.endpoint(room, "completions"), body)
        self.assertTrue(duplicate["duplicate"])
        self.assertEqual(0, duplicate["room"]["aggregate"]["completed_minutes"])

    def test_ranking_requires_account_and_room_opt_in_and_can_be_hidden(self):
        room = self.create()
        self.call(self.owner, "POST", self.endpoint(room, "completions"), {"session_id": "a", "minutes": 25})
        self.call(self.owner, "PATCH", "/v1/me/preferences", {"ranking_opt_in": True})
        self.assertEqual([], self.call(self.owner, "GET", self.endpoint(room))["ranking"])
        enabled = self.call(self.owner, "PATCH", self.endpoint(room, "preferences"), {"ranking_opt_in": True})
        self.assertEqual(1, len(enabled["ranking"]))
        self.call(self.owner, "PATCH", "/v1/me/preferences", {"ranking_opt_in": False})
        self.assertEqual([], self.call(self.owner, "GET", self.endpoint(room))["ranking"])

    def test_live_is_explicit_temporary_and_global_opt_out_clears_heartbeat(self):
        room = self.create()
        self.assert_api_error(403, lambda: self.call(self.owner, "POST", self.endpoint(room, "heartbeat"), {"focusing": True}))
        self.call(self.owner, "PATCH", "/v1/me/preferences", {"live_opt_in": True})
        self.call(self.owner, "PATCH", self.endpoint(room, "preferences"), {"live_opt_in": True})
        self.call(self.owner, "POST", self.endpoint(room, "heartbeat"), {"focusing": True})
        live = self.call(self.owner, "GET", self.endpoint(room))["live"]
        self.assertEqual([{"user_id": self.owner["user_id"], "nickname": "Owner"}], live)
        self.now += LIVE_TTL_SECONDS
        self.assertEqual([], self.call(self.owner, "GET", self.endpoint(room))["live"])
        self.call(self.owner, "POST", self.endpoint(room, "heartbeat"), {"focusing": True})
        self.call(self.owner, "PATCH", "/v1/me/preferences", {"live_opt_in": False})
        self.call(self.owner, "PATCH", "/v1/me/preferences", {"live_opt_in": True})
        self.assertEqual([], self.call(self.owner, "GET", self.endpoint(room))["live"])

    def test_organization_suppresses_small_cohorts_and_all_personal_rows(self):
        room = self.create("organization")
        self.join(room)
        third = self.register("Third")
        self.join(room, third)
        for index, user in enumerate([self.owner, self.friend, third]):
            result = self.call(user, "POST", self.endpoint(room, "completions"), {"session_id": "one", "minutes": 25})
            self.assertFalse(result["room"]["aggregate"]["visible"])
            self.assertEqual([], result["room"]["ranking"])
            self.assertEqual([], result["room"]["live"])
        self.now += 7 * 86_400
        finalized = self.call(self.owner, "GET", self.endpoint(room))
        self.assertEqual("2026-09-21", finalized["week"])
        self.assertTrue(finalized["aggregate"]["visible"])
        self.assertEqual(75, finalized["aggregate"]["completed_minutes"])
        self.assert_api_error(403, lambda: self.call(self.owner, "PATCH", self.endpoint(room, "preferences"), {"ranking_opt_in": True}))
        self.assert_api_error(403, lambda: self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": third["user_id"]}))

    def test_nudge_requires_consent_and_decline_blocks_sender_across_rooms(self):
        room = self.create()
        self.join(room)
        request = lambda: self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": self.friend["user_id"]})
        self.assert_api_error(403, request)
        self.enable_nudge()
        nudge = request()
        self.assertEqual(1, len(self.call(self.friend, "GET", "/v1/nudges")["nudges"]))
        self.assert_api_error(404, lambda: self.call(self.owner, "POST", f"/v1/nudges/{nudge['nudge_id']}/decline"))
        self.call(self.friend, "POST", f"/v1/nudges/{nudge['nudge_id']}/decline")
        self.assertEqual([], self.call(self.friend, "GET", "/v1/nudges")["nudges"])
        self.now += 86_400
        self.assert_api_error(403, request)
        other = self.create()
        self.join(other)
        self.assert_api_error(403, lambda: self.call(self.owner, "POST", self.endpoint(other, "nudges"), {"recipient_id": self.friend["user_id"]}))

    def test_nudge_quiet_hours_follow_recipient_timezone(self):
        room = self.create()
        self.join(room)
        self.call(self.friend, "PATCH", "/v1/me/preferences", {"nudge_opt_in": True, "timezone": "Pacific/Auckland"})
        self.assert_api_error(403, lambda: self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": self.friend["user_id"]}))
        self.call(self.friend, "PATCH", "/v1/me/preferences", {"timezone": "UTC"})
        self.assertTrue(self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": self.friend["user_id"]})["sent"])

    def test_nudge_hourly_and_daily_limits(self):
        room = self.create()
        self.join(room)
        self.enable_nudge()
        request = lambda: self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": self.friend["user_id"]})
        request()
        self.assert_api_error(429, request)
        for _ in range(2):
            self.now += 3_600
            request()
        self.now += 3_600
        self.assert_api_error(429, request)

    def test_leaving_preserves_group_aggregate_but_removes_personal_visibility(self):
        room = self.create()
        self.join(room)
        self.call(self.friend, "POST", self.endpoint(room, "completions"), {"session_id": "a", "minutes": 25})
        self.call(self.friend, "POST", self.endpoint(room, "leave"))
        self.assert_api_error(404, lambda: self.call(self.friend, "GET", self.endpoint(room)))
        remaining = self.call(self.owner, "GET", self.endpoint(room))
        self.assertEqual(25, remaining["aggregate"]["completed_minutes"])
        self.assertEqual(1, remaining["member_count"])
        self.assertNotIn("departures", remaining)

    def test_owner_close_and_reporting(self):
        room = self.create()
        self.join(room)
        self.assert_api_error(409, lambda: self.call(self.owner, "POST", self.endpoint(room, "leave")))
        self.assert_api_error(403, lambda: self.call(self.friend, "POST", self.endpoint(room, "close")))
        report = self.call(self.friend, "POST", self.endpoint(room, "report"), {"reason": "privacy"})
        self.assertEqual("pending", report["status"])
        self.assertEqual(report, self.call(self.friend, "POST", self.endpoint(room, "report"), {"reason": "privacy"}))
        closed = self.call(self.owner, "POST", self.endpoint(room, "close"))
        self.assertTrue(closed["closed"])
        self.assertNotIn("invite_code", closed)
        self.assert_api_error(404, lambda: self.join(room))
        self.assert_api_error(409, lambda: self.call(self.friend, "POST", self.endpoint(room, "completions"), {"session_id": "a", "minutes": 25}))

    def test_account_export_excludes_secrets_and_delete_revokes_token(self):
        room = self.create()
        self.join(room)
        self.call(self.owner, "POST", self.endpoint(room, "completions"), {"session_id": "a", "minutes": 25})
        exported = self.call(self.owner, "GET", "/v1/me/export")
        self.assertNotIn("token", json.dumps(exported))
        self.assertNotIn("invite_code", json.dumps(exported))
        self.assertEqual(25, exported["weekly_totals"][0]["minutes"])
        self.call(self.owner, "DELETE", "/v1/me")
        self.assert_api_error(401, lambda: self.call(self.owner, "GET", "/v1/me"))
        remaining = self.call(self.friend, "GET", self.endpoint(room))
        self.assertTrue(remaining["closed"])
        self.assertEqual(0, remaining["aggregate"]["completed_minutes"])

    def test_operator_can_resolve_and_close_reported_room(self):
        room = self.create()
        self.join(room)
        report = self.call(self.friend, "POST", self.endpoint(room, "report"), {"reason": "harassment"})
        self.assertEqual(1, len(pending_reports(self.service)))
        resolved = resolve_report(self.service, report["report_id"], close_room=True)
        self.assertTrue(resolved["room_closed"])
        self.assertEqual([], pending_reports(self.service))
        self.assertTrue(self.call(self.owner, "GET", self.endpoint(room))["closed"])

    def test_outsider_cannot_mutate_or_read_another_room(self):
        room = self.create()
        for method, action, body in [("GET", "", None), ("POST", "close", {}), ("POST", "leave", {}),
                                     ("PATCH", "preferences", {"ranking_opt_in": True}),
                                     ("POST", "completions", {"session_id": "a", "minutes": 25}),
                                     ("POST", "report", {"reason": "other"})]:
            self.assert_api_error(404, lambda: self.call(self.friend, method, self.endpoint(room, action), body))

    def test_validation_rejects_wrong_types_and_rolls_back_partial_preferences(self):
        self.assert_api_error(400, lambda: self.call(self.owner, "PATCH", "/v1/me/preferences", {"live_opt_in": True, "quiet_end_minute": -1}))
        self.assertFalse(self.call(self.owner, "GET", "/v1/me")["live_opt_in"])
        self.assert_api_error(400, lambda: self.call(self.owner, "PATCH", "/v1/me/preferences", {"live_opt_in": "true"}))
        self.assert_api_error(400, lambda: self.call(self.owner, "PATCH", "/v1/me/preferences", {"timezone": "not/a/zone"}))
        room = self.create()
        self.assert_api_error(400, lambda: self.call(self.owner, "POST", self.endpoint(room, "completions"), {"session_id": "a", "minutes": True}))
        self.assert_api_error(400, lambda: self.service.handle("POST", "/v1/register", {"nickname": "Wrong consent", "privacy_version": True}))

    def test_completion_cannot_mutate_finalized_reporting_window_in_new_room(self):
        room = self.create()
        body = {"session_id": "a", "minutes": 25}
        self.call(self.owner, "POST", self.endpoint(room, "completions"), body)
        other = self.create("organization")
        self.now += 7 * 86_400
        self.assert_api_error(409, lambda: self.call(self.owner, "POST", self.endpoint(other, "completions"), body), "completion_window_closed")

    def test_leaving_and_rejoining_does_not_reset_nudge_limits(self):
        room = self.create()
        self.join(room)
        self.enable_nudge()
        self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": self.friend["user_id"]})
        self.call(self.friend, "POST", self.endpoint(room, "leave"))
        self.assertEqual([], self.call(self.friend, "GET", "/v1/nudges")["nudges"])
        self.join(room)
        self.assert_api_error(429, lambda: self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": self.friend["user_id"]}))

    def test_nudge_opt_out_hides_existing_inbox(self):
        room = self.create()
        self.join(room)
        self.enable_nudge()
        self.call(self.owner, "POST", self.endpoint(room, "nudges"), {"recipient_id": self.friend["user_id"]})
        self.call(self.friend, "PATCH", "/v1/me/preferences", {"nudge_opt_in": False})
        self.assertEqual([], self.call(self.friend, "GET", "/v1/nudges")["nudges"])

    def test_persistence_survives_restart_and_contains_no_raw_token(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "community.sqlite"
            service = CommunityService(database, clock=lambda: self.now)
            account = service.handle("POST", "/v1/register", {"nickname": "Persisted", "privacy_version": 1})
            service.close()
            restored = CommunityService(database, clock=lambda: self.now)
            try:
                self.assertEqual("Persisted", restored.handle("GET", "/v1/me", token=account["token"])["nickname"])
                self.assertNotIn(account["token"].encode(), database.read_bytes())
                self.assertEqual(0o600, database.stat().st_mode & 0o777)
            finally:
                restored.close()


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.service = CommunityService()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.service))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.service.close()

    def test_real_http_registration_auth_and_json_errors(self):
        request = Request(self.base + "/v1/register", json.dumps({"nickname": "HTTP", "privacy_version": 1}).encode(),
                          {"Content-Type": "application/json"}, method="POST")
        with urlopen(request, timeout=3) as response:
            account = json.load(response)
            self.assertEqual("no-store", response.headers["Cache-Control"])
        with urlopen(Request(self.base + "/v1/me", headers={"Authorization": "Bearer " + account["token"]}), timeout=3) as response:
            self.assertEqual(account["user_id"], json.load(response)["user_id"])
        with self.assertRaises(HTTPError) as error:
            urlopen(Request(self.base + "/v1/register", b"{", {"Content-Type": "application/json"}), timeout=3)
        self.assertEqual(400, error.exception.code)
        self.assertEqual({"error": "invalid_json"}, json.load(error.exception))

    def test_http_rejects_ambiguous_oversized_and_nonfinite_json(self):
        for payload in [b'{"privacy_version":1,"privacy_version":2}', b'{"privacy_version":NaN}', b'[' * 2000 + b']' * 2000]:
            with self.assertRaises(HTTPError) as error:
                urlopen(Request(self.base + "/v1/register", payload, {"Content-Type": "application/json"}), timeout=3)
            self.assertEqual(400, error.exception.code)
            error.exception.close()
        with self.assertRaises(HTTPError) as error:
            urlopen(Request(self.base + "/v1/register", b" " * 20_000, {"Content-Type": "application/json"}), timeout=3)
        self.assertEqual(413, error.exception.code)
        error.exception.close()


if __name__ == "__main__":
    unittest.main()
