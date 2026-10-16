#!/usr/bin/env python3
"""Publish only a fully downloaded and verified draft; retries reuse a signed bundle."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.parse import quote

from verify_release_bundle import BASE_ASSETS, MANIFEST, require, sha256, verify_bundle


class ApiError(RuntimeError):
    pass


class GitHub:
    """Use GitHub CLI's credential/redirect handling; never log credentials or URLs."""
    def __init__(self, repository: str):
        require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)), "Invalid repository")
        self.repository = repository
        self.prefix = f"repos/{repository}"
        self.gh = os.environ.get("GH_BIN", "gh")

    def request(self, method: str, endpoint: str, body=None, *, upload: Path | None = None, output: Path | None = None):
        command = [self.gh, "api", "--method", method, endpoint,
                   "-H", "X-GitHub-Api-Version: 2022-11-28"]
        payload = None
        if upload is not None:
            command += ["--input", str(upload), "-H", "Content-Type: application/octet-stream"]
        elif body is not None:
            command += ["--input", "-"]
            payload = json.dumps(body).encode()
        if output is not None:
            command += ["-H", "Accept: application/octet-stream"]
        with output.open("wb") if output is not None else tempfile.TemporaryFile() as stream:
            result = subprocess.run(command, input=payload, stdout=stream, stderr=subprocess.PIPE, timeout=180)
            if result.returncode:
                # gh's error text may contain signed download URLs; keep the journal non-secret.
                raise ApiError(f"GitHub {method} failed (exit {result.returncode})")
            if output is not None:
                return None
            stream.seek(0)
            raw = stream.read()
        return json.loads(raw) if raw else None

    def tag_commit(self, tag: str) -> str:
        value = self.request("GET", f"{self.prefix}/git/ref/tags/{quote(tag, safe='')}")["object"]
        for _ in range(5):
            if value["type"] == "commit":
                return value["sha"]
            require(value["type"] == "tag", "Unexpected tag object type")
            value = self.request("GET", f"{self.prefix}/git/tags/{value['sha']}")["object"]
        raise ValueError("Excessively nested release tag")

    def find_release(self, tag: str):
        # Listing includes drafts with the authenticated contents:write token.
        for page in range(1, 11):
            values = self.request("GET", f"{self.prefix}/releases?per_page=100&page={page}")
            for value in values:
                if value["tag_name"] == tag:
                    return value
            if len(values) < 100:
                return None
        raise ValueError("Release inventory exceeded bound")

    def create_draft(self, tag: str, commit: str):
        return self.request("POST", f"{self.prefix}/releases", {
            "tag_name": tag, "target_commitish": commit, "name": f"PI//DECK {tag[1:]}",
            "draft": True, "prerelease": "-" in tag, "generate_release_notes": True,
        })

    def release(self, release_id: int):
        return self.request("GET", f"{self.prefix}/releases/{release_id}")

    def assets(self, release_id: int):
        values = self.request("GET", f"{self.prefix}/releases/{release_id}/assets?per_page=100")
        require(len(values) < 100, "Release asset inventory exceeded bound")
        return values

    def download(self, asset: dict, destination: Path):
        return self.request("GET", f"{self.prefix}/releases/assets/{int(asset['id'])}", output=destination)

    def delete(self, asset: dict):
        return self.request("DELETE", f"{self.prefix}/releases/assets/{int(asset['id'])}")

    def upload(self, release_id: int, path: Path):
        endpoint = f"https://uploads.github.com/{self.prefix}/releases/{release_id}/assets?name={quote(path.name, safe='')}"
        return self.request("POST", endpoint, upload=path)

    def publish(self, release_id: int):
        return self.request("PATCH", f"{self.prefix}/releases/{release_id}", {"draft": False})


def publish(api, bundle: Path, commit: str, tag: str, verify, *, repair_published=False,
            attempts=3, sleep=time.sleep, journal=lambda message: print(message, flush=True)) -> dict:
    """All mutations are fenced by exact repository release ID, tag and immutable commit."""
    require(1 <= attempts <= 5, "Invalid retry bound")
    verify(bundle)
    expected = {name: {"size": (bundle / name).stat().st_size, "sha256": sha256(bundle / name)}
                for name in BASE_ASSETS | {MANIFEST}}
    release = None
    for attempt in range(attempts):
        try:
            require(api.tag_commit(tag) == commit, "Remote tag does not match SOURCE_COMMIT")
            release = api.find_release(tag) or api.create_draft(tag, commit)
            break
        except (ApiError, subprocess.TimeoutExpired):
            if attempt + 1 == attempts:
                raise
            sleep(2 ** attempt)
    release_id = release["id"]

    def owned():
        require(api.tag_commit(tag) == commit, "Release tag moved; refusing mutation")
        current = api.release(release_id)
        require(current["id"] == release_id and current["tag_name"] == tag, "Release ownership mismatch")
        require(current["draft"] or repair_published, "Published release requires explicit --repair-published")
        return current

    owned()
    with tempfile.TemporaryDirectory(prefix="pideck-release-verify-") as temporary:
        downloaded = Path(temporary)

        def matches(asset, name):
            info = expected[name]
            if asset.get("state") != "uploaded" or asset.get("size") != info["size"]:
                return False
            destination = downloaded / name
            api.download(asset, destination)
            return destination.stat().st_size == info["size"] and sha256(destination) == info["sha256"]

        for name in sorted(expected):
            for attempt in range(attempts):
                try:
                    owned()
                    candidates = [asset for asset in api.assets(release_id) if asset["name"] == name]
                    require(len(candidates) <= 1, "Ambiguous asset ownership")
                    if candidates and matches(candidates[0], name):
                        journal(json.dumps({"action": "reused", "asset": name}))
                        break
                    if candidates:
                        require(name != "pi-deck.apk" or candidates[0].get("state") != "uploaded",
                                "Existing signed APK differs; reuse the original signed artifact, never re-sign a published version")
                        # Re-read membership immediately before deleting, never delete by name alone.
                        owned()
                        asset = candidates[0]
                        members = api.assets(release_id)
                        require(any(item["id"] == asset["id"] and item["name"] == name for item in members),
                                "Asset no longer belongs to this release")
                        api.delete(asset)
                        journal(json.dumps({"action": "replaced", "asset": name, "id": asset["id"]}))
                    api.upload(release_id, bundle / name)
                    candidates = [asset for asset in api.assets(release_id) if asset["name"] == name]
                    if len(candidates) != 1 or not matches(candidates[0], name):
                        raise ApiError("Uploaded asset failed byte verification")
                    journal(json.dumps({"action": "uploaded", "asset": name}))
                    break
                except (ApiError, subprocess.TimeoutExpired):
                    if attempt + 1 == attempts:
                        raise
                    sleep(2 ** attempt)
        # Final independent inventory/download pass, including the manifest and APK signature.
        owned()
        assets = api.assets(release_id)
        for name in sorted(expected):
            candidates = [asset for asset in assets if asset["name"] == name]
            require(len(candidates) == 1 and matches(candidates[0], name), f"Incomplete release asset: {name}")
        verify(downloaded)
        current = owned()
        if current["draft"]:
            api.publish(release_id)
        final = api.release(release_id)
        require(not final["draft"] and final["tag_name"] == tag, "Release publication not confirmed")
        journal(json.dumps({"action": "verified", "tag": tag, "sourceCommit": commit, "assets": len(expected)}))
        return final


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--certificate", required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--repair-published", action="store_true")
    parser.add_argument("--legacy-alpha15", action="store_true")
    args = parser.parse_args()
    def verify(directory):
        return verify_bundle(directory, args.commit, args.tag, args.certificate, args.build_tools,
                             legacy_alpha15=args.legacy_alpha15)
    publish(GitHub(args.repository), args.bundle, args.commit, args.tag, verify,
            repair_published=args.repair_published)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
