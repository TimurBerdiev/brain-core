"""Seed the Boss dogfood backlog in a GitHub Issues task repository."""

import argparse
import json
import re
from pathlib import Path

import task


CATALOG = Path(__file__).resolve().parents[1] / "boss-web" / "dogfood.json"


def seed(repo, assignees, *, dry_run=False, api=task.repo_api):
    plans = []
    for item in json.loads(CATALOG.read_text(encoding="utf-8")):
        marker = f"<!-- boss-dogfood:{item['id']} -->"
        card = task.validate_card("\n".join(item["card"])) if "card" in item else None
        body = "\n\n".join([marker, *(
            f"## {heading}\n\n{item[field]}"
            for heading, field in (("Goal", "goal"), ("Done when", "done"), ("Plan", "plan"), ("Next", "next"))
        )])
        if card:
            body = f"Waiting on: {card[0]}\n\n{body}"
        login = assignees[item["owner"]]
        plans.append((marker, card, {
            "title": item["title"], "body": body, "assignees": [login] if login else [],
            "labels": ["input-needed" if card else "ready", item["priority"]],
        }))

    if dry_run:
        print(json.dumps({"repo": repo, "ensure_labels": list(task.LABELS), "issues": [
            {**issue, **({"card": f"{task.CARD}\n{card[1]}"} if card else {})}
            for _, card, issue in plans
        ]}, indent=2))
        return

    for login in dict.fromkeys(assignees.values()):
        if login:
            api(repo, f"assignees/{login}")
    existing = {label["name"] for label in api(repo, "labels?per_page=100", paginate=True)}
    legacy = "in-review" in existing and "input-needed" not in existing
    for name, (color, description) in task.LABELS.items():
        if name not in existing and not (legacy and name == "input-needed"):
            api(repo, "labels", "POST", {"name": name, "color": color, "description": description})

    issues = api(repo, "issues?state=all&per_page=100", paginate=True)
    for marker, card, data in plans:
        issue = next((i for i in issues if "pull_request" not in i and marker in (i.get("body") or "")), None)
        if issue is None:
            if card and legacy:
                data["labels"][0] = "in-review"
            issue = api(repo, "issues", "POST", data)
            print(f"Created {repo}#{issue['number']}: {data['title']}")
        else:
            print(f"Kept {repo}#{issue['number']}: {issue['title']}")
        if (card and issue["state"] == "open" and task.status(issue, {}) == "input-needed"
                and task.field(issue, "Waiting on") == card[0]):
            path = f"issues/{issue['number']}/comments"
            comments = api(repo, f"{path}?per_page=100", paginate=True)
            if not any(c["body"].lstrip().startswith((task.CARD, "<!-- answer -->", "<!-- question -->")) for c in comments):
                api(repo, path, "POST", {"body": f"{task.CARD}\n{card[1]}"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo", metavar="OWNER/REPO")
    parser.add_argument("--timur", default="TimurBerdiev", metavar="LOGIN")
    parser.add_argument("--roman", metavar="LOGIN", help="Optional second assignee; defaults to the primary assignee")
    parser.add_argument("--dry-run", action="store_true", help="Print the seed plan without any GitHub calls")
    args = parser.parse_args()
    if not re.fullmatch(r"[\w-]+/[\w.-]+", args.repo, re.ASCII) or args.repo.split("/")[1] in (".", ".."):
        parser.error("repo must be OWNER/REPO")
    assignees = {"timur": args.timur, "roman": args.roman or args.timur}
    for login in assignees.values():
        if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?", login):
            parser.error("assignees must be GitHub logins")
    seed(args.repo, assignees, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
