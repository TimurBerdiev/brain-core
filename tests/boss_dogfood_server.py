"""Serve Boss and a disposable GitHub fixture on loopback."""

import argparse
import base64
import io
import json
import mimetypes
import re
import subprocess
import sys
from contextlib import redirect_stdout
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Lock, Thread
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import dogfood
import task

REPO = "local/boss"
USER = "timur"
WORKER = "dogfood-worker"


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run_git(directory, *args, input=None, check=True):
    return subprocess.run(["git", "-C", str(directory), "-c", f"user.name={WORKER}",
                           "-c", "user.email=worker@localhost", *args],
                          input=input, check=check, capture_output=True, text=True, timeout=20)


def git(directory, *args, **kwargs):
    return run_git(directory, *args, **kwargs).stdout


class Fixture:
    def __init__(self, root):
        self.work = root / "work"
        self.remote = root / "remote.git"
        git(root, "init", "-q", str(self.work))
        git(root, "init", "-q", "--bare", str(self.remote))
        git(self.work, "mktree", input="")
        self.labels = {}
        self.issues = {}
        self.comments = {}
        self.events = {}
        self.lock = Lock()
        self.worker_lock = Lock()

    def dispatch(self, path, method, data, query):
        if path == "labels":
            if method == "POST":
                self.labels[data["name"]] = data
                return 201, data
            return 200, list(self.labels.values())
        if re.fullmatch(r"assignees/(timur|roman)", path):
            return 204, None
        if path == "issues":
            if method == "POST":
                n = len(self.issues) + 1
                issue = self.issues[n] = {"number": n, "state": "open", "updated_at": now(), "html_url": ""}
                self.events[n], self.comments[n] = [], []
                return 201, self.update(issue, data)
            state = query.get("state", ["open"])[0]
            return 200, [i for i in self.issues.values() if state in ("all", i["state"])]
        match = re.fullmatch(r"issues/(\d+)(?:/(events|comments))?", path)
        if match and (n := int(match[1])) in self.issues:
            issue = self.issues[n]
            if match[2] == "events":
                return 200, self.events[n]
            if match[2] == "comments":
                if method == "POST":
                    comment = {"body": data["body"], "user": {"login": USER if
                               data["body"].startswith(("<!-- answer -->", "<!-- question -->")) else WORKER}}
                    self.comments[n].append(comment)
                    issue["updated_at"] = now()
                    return 201, comment
                return 200, self.comments[n]
            return 200, self.update(issue, data) if method == "PATCH" else issue
        if path in ("contents/repos.yaml", "contents/workspace.yaml"):
            yaml = "repos: {}" if "repos.yaml" in path else f"github: {USER}"
            return 200, {"content": base64.b64encode(yaml.encode()).decode()}
        if path == "branches":
            return 200, []
        if path == "git/matching-refs/heads/claim":
            refs = git(self.remote, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads/claim/")
            return 200, [{"ref": ref, "object": {"sha": sha}} for ref, sha in map(str.split, refs.splitlines())]
        if match := re.fullmatch(r"git/commits/([0-9a-f]{40})", path):
            date, message = git(self.remote, "show", "-s", "--format=%cI%n%s", match[1]).splitlines()
            return 200, {"committer": {"date": date}, "message": message}
        return 404, {"message": f"Unknown fixture route: {path}"}

    def update(self, issue, data):
        if "labels" in data:
            previous = {label["name"] for label in issue.get("labels", [])}
            issue["labels"] = [self.labels[label] for label in data["labels"]]
            for label in set(data["labels"]) - previous:
                self.events[issue["number"]].append({"event": "labeled", "label": self.labels[label], "created_at": now()})
        if "assignees" in data:
            issue["assignees"] = [{"login": login} for login in data["assignees"]]
        issue.update({key: data[key] for key in ("title", "body", "state", "state_reason") if key in data})
        issue["updated_at"] = now()
        return issue

    def take(self, api):
        def task_api(path, method="GET", data=None, paginate=False, repo_name=None):
            return api(REPO, path, method, data, paginate)

        with self.worker_lock, patch.multiple(task, ROOT=str(self.work), remote=lambda _: str(self.remote),
                me=lambda: USER, api=task_api, git=lambda *args, **kwargs: run_git(self.work, *args, **kwargs)):
            # Use the real worker pickup and claim path; only the REST host and Git remote are fixtures.
            with redirect_stdout(io.StringIO()) as output:
                task.cmd_take(SimpleNamespace(who=WORKER, agent="dev", ws=None))
            number = int(output.getvalue().split("\t", 1)[0])
            issue = task.iapi(number)
            comments = task.iapi(number, "/comments")
            answer = next((c["body"] for c in reversed(comments) if c["body"].startswith("<!-- answer -->")), "")
            next_step = issue["body"].partition("## Next\n")[2].strip()
            if not answer or not next_step:
                raise SystemExit("Claimed work has no answer or Next checkpoint")
            task.iapi(number, "/comments", "POST", {"body": f"Resumed as `{WORKER}` after reading:\n\n"
                       f"{answer.removeprefix('<!-- answer -->').strip()}\n\n**Next:** {next_step}"})
            return {"number": number, "worker": WORKER, "answer": answer, "next": next_step}


def rest_api(origin, repo, path, method="GET", data=None, paginate=False):
    headers = {"Content-Type": "application/json"} if data is not None else {}
    request = Request(f"{origin}/api/repos/{repo}/{path}", method=method, headers=headers,
                      data=json.dumps(data).encode() if data is not None else None)
    with urlopen(request, timeout=10) as response:
        return json.load(response) if response.status != 204 else None


class Handler(BaseHTTPRequestHandler):
    def send(self, status, body, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_request(self):
        if self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}":
            self.send(403, b"Loopback host required", "text/plain")
            return
        url = urlsplit(self.path)
        fixture = self.server.fixture
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"]))) if self.headers.get("Content-Length") else None
        prefix = f"/api/repos/{REPO}/"
        if url.path.startswith(prefix):
            with fixture.lock:
                status, result = fixture.dispatch(url.path[len(prefix):], self.command, data, parse_qs(url.query))
                body = (json.dumps(result).encode() if status != 204 else b"")
        elif url.path == "/worker/take" and self.command == "POST" and data == {}:
            try:
                status, result = 200, fixture.take(self.server.api)
            except SystemExit as error:
                status, result = 409, {"message": str(error)}
            body = json.dumps(result).encode()
        elif self.command == "GET" and re.fullmatch(r"/boss-web/(?:vendor/)?[\w.-]*", url.path):
            path = (ROOT / (url.path.lstrip("/") or "boss-web")).resolve()
            if path == ROOT / "boss-web":
                path /= "index.html"
            if path.is_file() and path.is_relative_to(ROOT / "boss-web") and path.suffix in {
                    ".js", ".mjs", ".html", ".css", ".png", ".svg", ".webmanifest", ".json"}:
                self.send(200, path.read_bytes(), mimetypes.guess_type(path)[0] or "application/octet-stream")
                return
            status, body = 404, b"{}"
        else:
            status, body = 404, b"{}"
        self.send(status, body)

    do_GET = do_POST = do_PATCH = handle_request

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=4174)
    args = parser.parse_args()
    with TemporaryDirectory() as path, ThreadingHTTPServer(("127.0.0.1", args.port), Handler) as server:
        server.fixture = Fixture(Path(path))
        origin = f"http://127.0.0.1:{server.server_port}"
        server.api = lambda *args, **kwargs: rest_api(origin, *args, **kwargs)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for _ in range(2):
                dogfood.seed(REPO, {"timur": USER, "roman": "roman"}, api=server.api)
            print(f"Boss dogfood: {origin}/boss-web/?local-dogfood", flush=True)
            thread.join()
        except KeyboardInterrupt:
            pass
        finally:
            server.shutdown()


if __name__ == "__main__":
    main()
