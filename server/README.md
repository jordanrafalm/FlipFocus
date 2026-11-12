# FlipFocus community pilot service

Runnable optional community backend using Python 3.11+ and SQLite. The local timer,
history, and streak work without this service or an account. It implements private
rooms, 15/25-minute invitations, consent, LIVE, weekly rankings, nudges, group goals,
organization aggregates, abuse reports, and account export/deletion. No external
messages or push notifications are sent; recipients fetch their own nudge inbox.

## Run and test

From the repository root:

```sh
python3 server/flipfocus_server.py
python3 -m unittest discover -s server/tests -v
```

The default endpoint is `http://127.0.0.1:8765`. The database is
`server/data/community.sqlite`, created with owner-only file permissions and ignored
by version control. Override `--host`, `--port`, or `--database` as needed. Android
emulator development uses `http://10.0.2.2:8765`; an iOS simulator uses loopback.

The built-in HTTP listener is for a local or controlled pilot. A shared deployment
needs a TLS reverse proxy with connection limits, a restricted network path to the
listener, private database backups, an operator who checks reports, and a retention
schedule. Forwarded address headers are deliberately not trusted. The server applies
registration limits of 10 per IP/hour and 240 requests per IP/minute; a proxy shares
its address, so configure upstream limits for the intended pilot. No deployment,
public DNS, production identity, store billing, or purchase verification is included.

## Identity and privacy

Registration requires `privacy_version: 1` and returns a high-entropy bearer token
once. SQLite stores its SHA-256 hash. This is a pseudonymous pilot account: nicknames
are not verified identities, and there is no email/password login or lost-token
recovery. Keep the token in platform secret storage, separate from history exports.
An existing token reconnects the same account on a new device. Account deletion
revokes it, deletes personal server contributions, and closes owned rooms; local
history is unaffected.

LIVE, ranking and nudges default to off. LIVE and ranking each require consent at
both account and room level. LIVE expires after 90 seconds without a heartbeat and
returns only the volunteered name and user ID, with no activity timestamps.

Contributions contain only a random local session ID and completed minutes. The
server hashes IDs for idempotency and stores minute/session totals by UTC week;
it never accepts task text, raw sensor readings or a completion timestamp. New
contributions belong to the server's current week. A completed week's contribution
cannot subsequently be attached to a new room. Completion counts are self-reported,
not sensor-attested, and must not be used to evaluate work performance.

Friends see a current-week group total. Individual weekly rows appear only with
separate ranking opt-in. Organizations have no LIVE, personal ranking, or nudges;
they see only the **previous completed UTC week**, and only if at least three
distinct accounts contributed in that week. This prevents polling current activity.
The threshold reduces disclosure; it does not provide differential privacy or prove
three accounts are three distinct people. The `week` field always identifies the
reported period. A person leaving retains the group's prior aggregate, removes their
visible membership/LIVE/ranking, and does not announce a failure. Deleting an account
also deletes their contribution, so retained aggregate totals can decrease.

Nudges require recipient opt-in and membership in the same open friends room. They
respect the recipient's IANA timezone and quiet hours (default 22:00–08:00), a
one-hour sender/recipient interval, three received per local day and ten sent per
rolling day. Declining blocks that sender across rooms until the recipient explicitly
unblocks. Leaving/rejoining does not reset limits. The inbox shows the past 24 hours;
rate-control records are purged after seven days by nudge creation or the maintenance
command below. Refusal/opt-out/quiet-hour failures share one error code.

The server does not persist HTTP access logs, tokens, raw IP addresses, individual
focus dates, task names or contact lists. Network rate limits use short-lived hashed
addresses in memory. SQLite secure deletion is enabled; operators must also honor
account deletion and retention when maintaining backups.

## Wire contract

All bodies are JSON objects. Successful requests return HTTP 200. Errors use
`{"error":"snake_case_code"}` with 400, 401, 403, 404, 409, 413, 415, 429 or 503.
Bodies are limited to 16 KiB. Ambiguous duplicate JSON keys and non-finite numbers
are rejected. Authenticated `/v1/` endpoints require `Authorization: Bearer <token>`;
company search is public and company writes require `FLIPFOCUS_ADMIN_TOKEN`.

| Method/path | Request | Result |
| --- | --- | --- |
| `GET /health` | — | `{status:"ok",api_version:1}` |
| `GET /v1/companies?q=...` | — | `[{id,name}]`; public company search |
| `POST /v1/admin/companies` | `{id,name}` with `FLIPFOCUS_ADMIN_TOKEN` | Company; remote catalog administration |
| `POST /v1/register` | `{nickname,timezone:"UTC",privacy_version:1,company_id,job_title}` | `{user_id,token,nickname}` |
| `GET /v1/me` | — | Profile |
| `GET /v1/company` | — | Previous completed UTC week aggregate for the user's company |
| `PATCH /v1/me/preferences` | Subset of profile preferences | Profile |
| `GET /v1/me/export` | — | `{profile,rooms,weekly_totals}`; own data, no tokens/invite codes/peer rows |
| `DELETE /v1/me` | — | `{deleted:true}` |
| `GET /v1/rooms` | — | `{rooms:[Room]}` |
| `POST /v1/rooms` | `{name,kind:"friends"\|"organization",goal_minutes:500,privacy_version:1,invitation_duration_minutes:15\|25}` | Room |
| `GET /v1/rooms/invite/{code}` | — | `{name,kind,privacy_version,privacy_text,invitation_duration_minutes}` |
| `POST /v1/rooms/join` | `{code,privacy_version:1}` | Room |
| `GET /v1/rooms/{id}` | — | Room |
| `PATCH /v1/rooms/{id}/preferences` | `{live_opt_in,ranking_opt_in}` | Room |
| `POST /v1/rooms/{id}/heartbeat` | `{focusing:true\|false}` | `{live,ttl_seconds}` |
| `POST /v1/rooms/{id}/completions` | `{session_id,minutes:1..180}` | `{accepted:true,duplicate,room:Room}` |
| `POST /v1/rooms/{id}/nudges` | `{recipient_id}` | `{nudge_id,sent:true}` |
| `GET /v1/nudges` | — | `{nudges:[{id,room_id,sender_id,sender_nickname}]}` |
| `POST /v1/nudges/{id}/decline` | `{}` | `{declined:true}` |
| `DELETE /v1/nudge-blocks/{senderId}` | — | `{allowed:true}` |
| `POST /v1/rooms/{id}/report` | `{reason:"spam"\|"harassment"\|"privacy"\|"other"}` | `{report_id,status:"pending"}` |
| `POST /v1/rooms/{id}/leave` | `{}` | `{left:true}` |
| `POST /v1/rooms/{id}/close` | `{}`; owner only | Room |

Profile fields: `user_id`, `nickname`, `timezone`, `live_opt_in`, `ranking_opt_in`,
`nudge_opt_in`, `quiet_start_minute`, `quiet_end_minute`, `company_id`, `company_name`
and `job_title`. All except `user_id` and company identity may be updated with PATCH.
Quiet-hour boundaries are minutes after local midnight; equal boundaries disable quiet hours.

Company aggregates expose employee count and completed minutes/sessions only after at least
three distinct employees contributed. Individual company rankings are never returned.

The catalog importer reads quoted markdown lists and sends them through the admin endpoint:

```sh
FLIPFOCUS_ADMIN_TOKEN='...' python3 scripts/import_companies.py \
	--admin-token "$FLIPFOCUS_ADMIN_TOKEN" \
	/path/globalne_instytucje_bankowe_i_finansowe.md \
	/path/globalne_firmy_technologiczne.md
```

Room fields: `id`, `name`, `kind`, `closed`, `role` (`owner` or `participant`),
`goal_minutes`, `week` (`YYYY-MM-DD`, UTC Monday), `invitation_duration_minutes`,
`privacy_version`, `privacy_text`, `member_count`, `live_opt_in`, `ranking_opt_in`,
`aggregate`, `ranking`, and `live`. `invite_code` is returned only to the owner of an
open room. `aggregate` contains `visible`, nullable `completed_minutes` and nullable
`completed_sessions`. Ranking entries contain `user_id`, `nickname` and
`completed_minutes`. LIVE entries contain only `user_id` and `nickname`.

Completion retries with the same user/session/room are idempotent, including across
weeks and restarts. Changing minutes for an existing session yields
`completion_conflict`; attaching an old completion to a new room yields
`completion_window_closed`. One session can contribute to several rooms in its
current week. A user can create up to 20 active rooms; rooms support 200 members.

## Report handling and retention

Operator actions require direct access to the private database; they have no remote
admin endpoint. Listing reports does not expose bearer hashes or sensor information.

```sh
python3 server/admin.py reports
python3 server/admin.py resolve REPORT_ID --close-room
python3 server/admin.py purge-expired-nudges
```

Resolve a report after reviewing it; omit `--close-room` if no closure is needed.
Closed rooms reject new joins, activity and nudges. Reports remain available to the
operator as resolved records. For a pilot, schedule daily nudge maintenance and check
pending reports daily. Room closure is not an account ban or a substitute for a
documented production moderation process.
