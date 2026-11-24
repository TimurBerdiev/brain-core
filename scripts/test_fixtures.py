"""The task format cases shared with the Boss app (tests/fixtures/*.json; boss/.../FixturesTest.kt reads the
same files), so task.py and Model.kt can't drift apart quietly. Run: python3 -m unittest discover -s scripts -p 'test_*.py'."""
import copy
import datetime
import json
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import task

FIXTURES = Path(__file__).resolve().parent.parent / "tests" / "fixtures"


def cases(name, key="cases"):
    return json.loads((FIXTURES / f"{name}.json").read_text())[key]


def issue(labels=(), body="", state="open", state_reason=None, number=7):
    return {"number": number, "title": "t", "state": state, "state_reason": state_reason, "body": body,
            "labels": [{"name": l} for l in labels], "_repo": None}


class FixtureTests(unittest.TestCase):
    def test_cards(self):
        for c in cases("cards"):
            with self.subTest(c["name"]), tempfile.NamedTemporaryFile("w", suffix=".md") as f:
                f.write("\n".join(c["card"]))
                f.flush()
                if c["valid"]:
                    self.assertEqual(task.read_card(f.name)[0], c["question"])
                else:
                    with self.assertRaises(SystemExit):
                        task.read_card(f.name)

    def test_status(self):
        for c in cases("status"):
            with self.subTest(c["labels"]):
                self.assertEqual(task.status(issue(c["labels"]), {}), c["status"])

    def test_fields(self):
        for c in cases("status", "fields"):
            with self.subTest(c["body"]):
                i = issue(body=c["body"])
                self.assertEqual(task.field(i, "Waiting on"), c["waiting_on"])
                self.assertEqual(task.field(i, "Check after"), c["check_after"])

    def test_transitions(self):
        for c in cases("transitions"):
            with self.subTest(c["name"]):
                b = c["before"]
                before = issue(b["labels"], b["body"], b["state"], b.get("state_reason"))
                sent = []
                with patch.object(task, "status_label", lambda r, st: st), \
                        patch.object(task, "iapi", lambda k, sub, method, data: sent.append(data)):
                    task.set_status(copy.deepcopy(before), c["to"], c["waiting_on"], c["check_after"])
                after = {"state": before["state"], "labels": [l["name"] for l in before["labels"]], "body": before["body"],
                         "state_reason": before["state_reason"], **(sent[-1] if sent else {})}
                want = c["after"]
                self.assertEqual(after["body"], want["body"])
                self.assertEqual(sorted(after["labels"]), want["labels"])
                self.assertEqual(after["state"], want["state"])
                if want["state"] == "closed":
                    self.assertEqual(after["state_reason"], want["state_reason"])
                else:
                    self.assertEqual(task.status(issue(after["labels"]), {}), c["to"])

    def test_claims(self):
        now = datetime.datetime.now().timestamp()
        for c in cases("claims"):
            with self.subTest(c["subject"]):
                line = f"92\tabc123\t{int(now - c['age_hours'] * 3600)}\t{c['subject']}\n"
                fake = lambda *args, check=True: types.SimpleNamespace(stdout=line if args[0] == "for-each-ref" else "")
                with patch.object(task, "task_repos", return_value=[None]), patch.object(task, "git", fake):
                    found = task.claims()
                holder = found[92][2] if task.active_claim(92, found) else ""
                self.assertEqual(holder, c["holder"])

    def test_filter_lines(self):
        for c in cases("filter", "lines"):
            with self.subTest(c["yaml"]), tempfile.TemporaryDirectory() as d:
                Path(d, "workers.yaml").write_text(c["yaml"])
                with patch.object(task, "ROOT", d):
                    self.assertEqual(sorted(task.agent_filter()), c["terms"] or [])

    def test_filter_matches(self):
        for c in cases("filter", "matches"):
            with self.subTest(c):
                i = issue(c["labels"])
                i["_repo"] = c["repo"]
                self.assertEqual(task.matches(i, c["filter"]), c["keep"])

    def test_filter_describe(self):
        for c in cases("filter", "describe"):
            with self.subTest(c["filter"]):
                self.assertEqual(task.describe_filter(c["filter"]), c["text"])


if __name__ == "__main__":
    unittest.main()
