#!/usr/bin/env python3
"""Publishes one file to a GitHub branch as a single orphan commit, over the API. Stdlib only.

The GitHub Action does this with `git init` + `git push --force`, which keeps the data branch at
exactly one commit so the repo does not grow a commit per hour forever. Lambda has no git, so this
reproduces it through the Git Data API:

  blob -> tree -> commit (no parents, so it is an orphan) -> force-update the ref

Four calls. The alternative, the simpler Contents API, appends an ordinary commit each time and
would leave the branch carrying ~8,700 commits a year; this keeps the published branch identical
in shape to what the workflow produces, so either publisher can take over from the other.
"""
import json
import os
import urllib.error
import urllib.request

# GITHUB_API_BASE overrides the endpoint for local testing against a mock only.
API = os.environ.get("GITHUB_API_BASE", "https://api.github.com")


class PublishError(Exception):
    pass


def _call(method, url, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "aurum-brief-feed/1",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp) if resp.length != 0 else {}
    except urllib.error.HTTPError as e:
        detail = e.read(500).decode("utf-8", "replace")
        # Never echo the URL or headers: the token is in the headers and some URLs carry refs.
        raise PublishError(f"GitHub {method} {url.split(API)[-1]} -> HTTP {e.code}: {detail}")
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        raise PublishError(f"GitHub {method} failed: {type(e).__name__}")


def read_published(repo, branch, path, token):
    """The file's current content as text, or None if the branch or file does not exist yet.

    Read through the API rather than raw.githubusercontent on purpose: the raw CDN caches for
    about five minutes, and a stale read here would make the freshness guard think the last
    publish was older than it is and burn a grounded call republishing the same hour.
    """
    url = f"{API}/repos/{repo}/contents/{path}?ref={branch}"
    try:
        body = _call("GET", url, token)
    except PublishError as e:
        if "HTTP 404" in str(e):
            return None
        raise
    import base64
    return base64.b64decode(body["content"]).decode("utf-8")


def publish(repo, branch, files, message, token):
    """Force-pushes [files] ({path: text}) to [branch] as a lone orphan commit."""
    blobs = {}
    for path, text in files.items():
        blobs[path] = _call("POST", f"{API}/repos/{repo}/git/blobs", token,
                            {"content": text, "encoding": "utf-8"})["sha"]

    tree = _call("POST", f"{API}/repos/{repo}/git/trees", token, {
        "tree": [{"path": p, "mode": "100644", "type": "blob", "sha": sha}
                 for p, sha in blobs.items()],
    })["sha"]

    # No "parents" key: an orphan commit, so the branch is replaced rather than extended.
    commit = _call("POST", f"{API}/repos/{repo}/git/commits", token,
                   {"message": message, "tree": tree})["sha"]

    ref = f"refs/heads/{branch}"
    try:
        _call("PATCH", f"{API}/repos/{repo}/git/refs/heads/{branch}", token,
              {"sha": commit, "force": True})
    except PublishError as e:
        if "HTTP 422" in str(e) or "HTTP 404" in str(e):      # branch does not exist yet
            _call("POST", f"{API}/repos/{repo}/git/refs", token, {"ref": ref, "sha": commit})
        else:
            raise
    return commit
