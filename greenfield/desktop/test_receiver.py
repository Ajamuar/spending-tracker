import copy
import csv
import tempfile
from pathlib import Path
import unittest
import uuid

from receiver import accept, validate


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.entry = dict(id=str(uuid.uuid4()), revision=1, amount_minor=24950, kind="EXPENSE", status="POSTED",
                          account_id="test-bank", merchant="TEST CAFE", category="Food", tags="", note="",
                          occurred_at=0, date="1970-01-01", source="manual", reviewed=True, excluded=False,
                          deleted=False, linked_id=None, personal_minor=None, direction="out")
        self.payload = dict(schema=1, ledger_id=str(uuid.uuid4()), entries=[self.entry])

    def send(self, payload=None):
        return accept(payload or self.payload, self.root / "inbox.sqlite", self.root / "data")

    def rows(self):
        with (self.root / "data/mobile-transactions.csv").open() as stream:
            return list(csv.DictReader(stream))

    def test_replay_edit_clear_delete_and_stale_delivery(self):
        self.send(); self.send()
        self.assertEqual(len(self.rows()), 1)
        stale = copy.deepcopy(self.payload)
        self.entry.update(revision=2, category="Travel", note="Corrected")
        self.send()
        self.entry.update(revision=3, note="")
        self.send(); self.send(stale)
        self.assertEqual(self.rows()[0]["category"], "Travel")
        self.entry.update(revision=4, deleted=True)
        self.send(); self.send(stale)
        self.assertEqual(self.rows(), [])

    def test_identical_amounts_are_not_duplicates_and_manual_file_is_preserved(self):
        data = self.root / "data"
        data.mkdir()
        original = data / "transactions.csv"
        original.write_text("hand-entered ledger\n")
        self.payload["entries"].append(dict(self.entry, id=str(uuid.uuid4())))
        self.send()
        self.assertEqual(len(self.rows()), 2)
        self.assertEqual(original.read_text(), "hand-entered ledger\n")

    def test_invalid_batch_is_atomic_and_revisions_cannot_change_content(self):
        self.send()
        self.entry["merchant"] = "CHANGED WITHOUT REVISION"
        with self.assertRaises(ValueError): self.send()
        self.assertEqual(self.rows()[0]["description"], "TEST CAFE")
        self.entry.update(revision=2)
        self.payload["entries"].append(dict(self.entry, id=str(uuid.uuid4()), amount_minor=-1))
        with self.assertRaises(ValueError): self.send()
        self.assertEqual(self.rows()[0]["revision"], "1")

    def test_transfer_not_in_personal_spend_and_raw_bodies_rejected(self):
        self.entry["kind"] = "CARD_PAYMENT"
        self.send()
        with (self.root / "data/mobile-spending.csv").open() as stream:
            self.assertEqual(list(csv.DictReader(stream)), [])
        self.entry["body"] = "Synthetic body must never enter receiver"
        with self.assertRaises(ValueError): validate(self.payload)

    def test_personal_share_does_not_replace_full_money_movement(self):
        self.entry["personal_minor"] = 12500
        self.send()
        self.assertEqual(self.rows()[0]["amount_inr"], "249.50")
        with (self.root / "data/mobile-spending.csv").open() as stream:
            self.assertEqual(list(csv.DictReader(stream))[0]["amount_inr"], "125.00")
        self.entry.update(revision=2, note="")
        reply = self.send()
        self.assertEqual(reply["latest"][self.entry["id"]], 2)
        self.entry.update(revision=1)
        self.assertEqual(self.send()["latest"][self.entry["id"]], 2)


if __name__ == "__main__": unittest.main()
