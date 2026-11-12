#!/usr/bin/env python3
"""Import quoted company names from markdown lists directly into Firestore.

DEFERRED / UNUSED by the app: the company directory is now bundled locally in
`shared/domain/.../CompanyDirectory.kt` (`LocalCompanyDirectory`) and searched fully offline, so a
`companies` Firestore collection and this admin import are no longer required on the free Spark
plan. Keep this script only for a possible future paid backend; do not run it as part of normal
setup.

Requires a Firebase service account key with Firestore access:

    pip install firebase-admin
    python3 scripts/import_companies.py --credentials service-account.json \\
        globalne_instytucje_bankowe_i_finansowe.md globalne_firmy_technologiczne.md

The Admin SDK bypasses Firestore security rules, so this must be run from a
trusted machine holding the service account key. Never commit that key.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path


def read_names(paths: list[Path]) -> list[str]:
    names: dict[str, str] = {}
    for path in paths:
        for name in re.findall(r'"([^"\n]+)"', path.read_text(encoding="utf-8")):
            clean = " ".join(name.split()).strip()
            if clean:
                names.setdefault(clean.casefold(), clean)
    return sorted(names.values(), key=str.casefold)


def company_id(name: str) -> str:
    value = re.sub(r"[^a-z0-9]+", "-", name.casefold()).strip("-")
    return value[:64] or "company"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--credentials", required=True, help="Path to a Firebase service account JSON key")
    args = parser.parse_args()

    import firebase_admin
    from firebase_admin import credentials, firestore

    firebase_admin.initialize_app(credentials.Certificate(str(args.credentials)))
    db = firestore.client()
    collection = db.collection("companies")

    used: set[str] = set()
    imported = 0
    skipped = 0
    for name in read_names(args.files):
        base = company_id(name)
        identifier = base
        suffix = 2
        while identifier in used:
            identifier = f"{base[:61]}-{suffix}"
            suffix += 1
        used.add(identifier)
        doc_ref = collection.document(identifier)
        if doc_ref.get().exists:
            skipped += 1
            continue
        doc_ref.set({"name": name, "nameLower": name.casefold()})
        imported += 1
    print(f"Imported {imported} companies, skipped {skipped} already present")


if __name__ == "__main__":
    main()

