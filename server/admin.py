#!/usr/bin/env python3
"""Local operator actions. Requires filesystem access to the private service database."""
import argparse
import json

from flipfocus_server import ApiError, CommunityService


def pending_reports(service):
    with service.lock:
        return [dict(row) for row in service.db.execute(
            "SELECT p.id,p.room_id,r.name AS room_name,p.reason,p.status "
            "FROM reports p LEFT JOIN rooms r ON r.id=p.room_id WHERE p.status='pending' ORDER BY p.id")]


def resolve_report(service, report_id, close_room=False):
    with service.lock, service.db:
        report = service.db.execute("SELECT room_id FROM reports WHERE id=?", (report_id,)).fetchone()
        if not report:
            raise ApiError(404, "report_unavailable")
        if close_room and report["room_id"]:
            service.db.execute("UPDATE rooms SET closed=1 WHERE id=?", (report["room_id"],))
            service.db.execute("UPDATE memberships SET live_until=0 WHERE room_id=?", (report["room_id"],))
        service.db.execute("UPDATE reports SET status='resolved' WHERE id=?", (report_id,))
        return {"report_id": report_id, "status": "resolved", "room_closed": bool(close_room)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="server/data/community.sqlite")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("reports", help="List pending abuse reports")
    resolve = subparsers.add_parser("resolve", help="Resolve an investigated report")
    resolve.add_argument("report_id")
    resolve.add_argument("--close-room", action="store_true")
    subparsers.add_parser("purge-expired-nudges", help="Remove nudge records older than seven days")
    args = parser.parse_args()
    service = CommunityService(args.database)
    try:
        if args.command == "reports":
            result = {"reports": pending_reports(service)}
        elif args.command == "resolve":
            result = resolve_report(service, args.report_id, args.close_room)
        else:
            with service.db:
                removed = service.db.execute("DELETE FROM nudges WHERE created_at<?", (int(service.clock()) - 7 * 86_400,)).rowcount
            result = {"removed": removed}
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except ApiError as error:
        parser.exit(1, f"{error.code}\n")
    finally:
        service.close()


if __name__ == "__main__":
    main()
