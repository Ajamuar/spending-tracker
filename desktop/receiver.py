"""Paired TLS receiver. Uses only Python's standard library and local OpenSSL."""
from __future__ import annotations

import argparse
import csv
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import ssl
import subprocess
import tempfile
import threading
from datetime import date, datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
import uuid

MAX_MINOR = 9_999_999_999_999
KINDS = {"EXPENSE", "INCOME", "TRANSFER", "CARD_PAYMENT", "REFUND", "INVESTMENT", "REIMBURSEMENT"}
STATUSES = {"POSTED", "PENDING", "FAILED", "REVERSED"}
FIELDS = {"id", "revision", "amount_minor", "kind", "status", "account_id", "merchant", "category", "tags", "note", "occurred_at", "date", "source", "reviewed", "excluded", "deleted", "linked_id", "personal_minor", "direction"}
LOCK = threading.Lock()


def validate(payload: object) -> dict:
    if not isinstance(payload, dict) or set(payload) != {"schema", "ledger_id", "entries"} or type(payload["schema"]) is not int or payload["schema"] != 1:
        raise ValueError("Unsupported envelope")
    uuid.UUID(payload["ledger_id"])
    entries = payload["entries"]
    if not isinstance(entries, list) or not 1 <= len(entries) <= 100:
        raise ValueError("Expected 1–100 entries")
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != FIELDS:
            raise ValueError("Unexpected transaction fields")
        uuid.UUID(entry["id"])
        if type(entry["revision"]) is not int or not 1 <= entry["revision"] <= 2**53:
            raise ValueError("Invalid revision")
        if type(entry["amount_minor"]) is not int or not 1 <= entry["amount_minor"] <= MAX_MINOR:
            raise ValueError("Invalid amount")
        personal = entry["personal_minor"]
        if personal is not None and (type(personal) is not int or not 0 <= personal <= entry["amount_minor"]):
            raise ValueError("Invalid personal share")
        if entry["kind"] not in KINDS or entry["status"] not in STATUSES:
            raise ValueError("Invalid classification")
        if entry["direction"] not in {"in", "out"}:
            raise ValueError("Invalid direction")
        if entry["kind"] == "EXPENSE" and entry["direction"] != "out" or entry["kind"] in {"INCOME", "REFUND"} and entry["direction"] != "in":
            raise ValueError("Direction contradicts classification")
        for key, limit in (("account_id", 80), ("merchant", 120), ("category", 80), ("tags", 500), ("note", 2000), ("source", 20)):
            if not isinstance(entry[key], str) or len(entry[key]) > limit or "\x00" in entry[key]:
                raise ValueError("Invalid text field")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", entry["account_id"]):
            raise ValueError("Set a repo account ID on the phone")
        if not entry["merchant"].strip() or not entry["category"].strip():
            raise ValueError("Empty description/category")
        if entry["source"] not in {"manual", "sms", "csv"}:
            raise ValueError("Invalid source")
        for key in ("reviewed", "excluded", "deleted"):
            if type(entry[key]) is not bool:
                raise ValueError("Invalid flag")
        if entry["linked_id"] is not None:
            uuid.UUID(entry["linked_id"])
            if entry["linked_id"] == entry["id"]:
                raise ValueError("Self link")
        if type(entry["occurred_at"]) is not int or not 0 <= entry["occurred_at"] <= int(datetime.now(timezone.utc).timestamp() * 1000) + 86_400_000:
            raise ValueError("Invalid timestamp")
        date.fromisoformat(entry["date"])
        # The Android contract uses IST; reject contradictory date/timestamp pairs.
        expected = datetime.fromtimestamp((entry["occurred_at"] + 19_800_000) / 1000, timezone.utc).date().isoformat()
        if entry["date"] != expected:
            raise ValueError("Date and timestamp disagree")
        key = (entry["id"], entry["revision"])
        if key in seen:
            raise ValueError("Duplicate change in batch")
        seen.add(key)
    return payload


def connect(path: Path) -> sqlite3.Connection:
    db = sqlite3.connect(path, timeout=20)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=FULL")
    db.execute("CREATE TABLE IF NOT EXISTS entries (ledger TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(ledger,id))")
    return db


def atomic_csv(path: Path, fields: list[str], rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, name = tempfile.mkstemp(prefix=".ledger-", dir=path.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def materialize(db: sqlite3.Connection, output: Path) -> None:
    fields = ["date", "account_id", "category", "subcategory", "direction", "amount_inr", "description", "tags", "source", "source_id", "revision", "kind", "status", "excluded", "reviewed", "linked_id", "personal_amount_inr", "note"]
    rows, personal = [], []
    for ledger, body in db.execute("SELECT ledger,body FROM entries ORDER BY ledger,id"):
        entry = json.loads(body)
        if entry["deleted"]:
            continue
        row = dict(date=entry["date"], account_id=entry["account_id"], category=entry["category"], subcategory="",
                   direction=entry.get("direction", "in" if entry["kind"] in {"INCOME", "REFUND", "REIMBURSEMENT"} else "out"),
                   amount_inr=f'{entry["amount_minor"] // 100}.{entry["amount_minor"] % 100:02d}',
                   description=entry["merchant"], tags=entry["tags"], source=f"rupee-ledger:{ledger}",
                   source_id=entry["id"], revision=entry["revision"], kind=entry["kind"], status=entry["status"],
                   excluded=str(entry["excluded"]).lower(), reviewed=str(entry["reviewed"]).lower(), linked_id=entry["linked_id"] or "",
                   personal_amount_inr=f'{(entry["personal_minor"] if entry["personal_minor"] is not None else entry["amount_minor"]) // 100}.{(entry["personal_minor"] if entry["personal_minor"] is not None else entry["amount_minor"]) % 100:02d}', note=entry["note"])
        rows.append(row)
        if entry["status"] == "POSTED" and not entry["excluded"] and entry["kind"] in {"EXPENSE", "REFUND"}:
            personal.append(dict(row, amount_inr=row["personal_amount_inr"]))
    # App-owned files only: never overwrite the user's existing transactions.csv.
    atomic_csv(output / "mobile-transactions.csv", fields, rows)
    atomic_csv(output / "mobile-spending.csv", fields, personal)


def accept(payload: object, db_path: Path, output: Path) -> dict:
    validated = validate(payload)
    latest = {}
    with LOCK:
        db = connect(db_path)
        try:
            with db:
                for entry in validated["entries"]:
                    existing = db.execute("SELECT revision,body FROM entries WHERE ledger=? AND id=?", (validated["ledger_id"], entry["id"])).fetchone()
                    body = json.dumps(entry, sort_keys=True, separators=(",", ":"))
                    if existing and existing[0] == entry["revision"] and existing[1] != body:
                        raise ValueError("Revision collision")
                    if existing is None or entry["revision"] > existing[0]:
                        db.execute("INSERT INTO entries VALUES(?,?,?,?) ON CONFLICT(ledger,id) DO UPDATE SET revision=excluded.revision,body=excluded.body",
                                   (validated["ledger_id"], entry["id"], entry["revision"], body))
                    latest[entry["id"]] = max(entry["revision"], existing[0] if existing else 0)
            materialize(db, output)
        finally:
            db.close()
    return {"ledger_id": validated["ledger_id"], "materialized": True, "latest": latest,
            "ack": [f'{e["id"]}:{e["revision"]}' for e in validated["entries"]]}


def initialise(directory: Path, url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.path or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("Advertise an HTTPS origin without a path")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if any(directory.iterdir()):
        raise ValueError("Choose an empty receiver directory; existing pairing has not been changed")
    os.chmod(directory, 0o700)
    cert, key = directory / "certificate.pem", directory / "private-key.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes", "-days", "365",
                    "-keyout", str(key), "-out", str(cert), "-subj", "/CN=RupeeLedger receiver"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chmod(key, 0o600)
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    config = {"url": url, "pin": hashlib.sha256(der).hexdigest(), "token": secrets.token_urlsafe(32)}
    descriptor = os.open(directory / "pairing.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(config, stream)
    print("Receiver initialized. Pair from the private pairing.json file; do not commit or share it.")


def serve(directory: Path, host: str, port: int, output: Path) -> None:
    config = json.loads((directory / "pairing.json").read_text())
    output.mkdir(parents=True, exist_ok=True)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Financial payloads and authentication never enter HTTP logs.

        def do_POST(self):
            self.connection.settimeout(20)
            if self.path != "/v1/sync":
                self.reply(404, {"error": "Unknown endpoint"}); return
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + config["token"]).encode()):
                self.reply(401, {"error": "Invalid pairing"}); return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 1_000_000 or self.headers.get("Transfer-Encoding"):
                    self.reply(413, {"error": "Invalid batch size"}); return
                raw = self.rfile.read(size)
                if len(raw) != size:
                    raise ValueError("Incomplete body")
                result = accept(json.loads(raw), directory / "inbox.sqlite", output)
                self.reply(200, result)
            except (ValueError, TypeError, KeyError, OverflowError):
                self.reply(422, {"error": "Invalid change or account mapping"})
            except Exception:
                self.reply(503, {"error": "Local persistence unavailable; retry later"})

        def reply(self, code: int, body: dict):
            encoded = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)
    server = ThreadingHTTPServer((host, port), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(directory / "certificate.pem", directory / "private-key.pem")
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    print(f"Listening on {host}:{port}. CSV views are app-owned; existing ledger files are untouched.")
    try:
        server.serve_forever()
    finally:
        server.server_close()


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "serve"])
    parser.add_argument("--directory", type=Path, default=Path(".receiver"))
    parser.add_argument("--url", default="https://127.0.0.1:8765")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--output", type=Path, default=Path(".receiver/data"))
    args = parser.parse_args()
    try:
        if args.command == "init":
            initialise(args.directory, args.url)
        else:
            serve(args.directory, args.host, args.port, args.output)
    except (ValueError, OSError, subprocess.CalledProcessError):
        parser.exit(1, "Receiver setup failed. Check the private directory, URL, port and OpenSSL installation.\n")


if __name__ == "__main__":
    main()
