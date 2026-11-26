import io
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch

import dogfood


class DogfoodTests(unittest.TestCase):
    def test_cli_defaults_all_tasks_to_timur(self):
        for options, expected in (([], "TimurBerdiev"), (["--roman", "other"], "other")):
            with self.subTest(options=options), patch("sys.argv", ["dogfood.py", "owner/boss", *options]), \
                    patch.object(dogfood, "seed") as seed:
                dogfood.main()
            seed.assert_called_once_with("owner/boss", {"timur": "TimurBerdiev", "roman": expected}, dry_run=False)

    def test_dry_run_is_offline_and_includes_checkpoints_and_cards(self):
        api = Mock()
        with redirect_stdout(io.StringIO()) as output:
            dogfood.seed("owner/boss", {"timur": "first", "roman": "second"}, dry_run=True, api=api)
        api.assert_not_called()
        plan = json.loads(output.getvalue())
        self.assertEqual(plan["repo"], "owner/boss")
        self.assertTrue({"ready", "input-needed", "p1", "p2", "p3"} <= set(plan["ensure_labels"]))
        self.assertEqual({a for issue in plan["issues"] for a in issue["assignees"]}, {"first", "second"})
        self.assertTrue(all("## Next\n\n" in issue["body"] for issue in plan["issues"]))
        cards = [issue for issue in plan["issues"] if "card" in issue]
        self.assertEqual(len(cards), 3)
        for issue in cards:
            marker, question, *_ = issue["card"].splitlines()
            self.assertEqual(marker, "<!-- card -->")
            self.assertTrue(issue["body"].startswith(f"Waiting on: {question[4:]}\n"))
