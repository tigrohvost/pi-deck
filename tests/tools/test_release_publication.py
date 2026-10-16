"""Failure injection at the release API boundary; no network or signing credentials."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from publish_release import ApiError, publish
from verify_release_bundle import BASE_ASSETS, MANIFEST, manifest_for, verify_files

COMMIT = "a" * 40
TAG = "v0.3.0-alpha17"
CERT = "b" * 64


class FakeGitHub:
    def __init__(self):
        self.value = None
        self.files = {}
        self.uploads = []
        self.deletes = []
        self.fail_name = None
        self.fail_count = 0
        self.publishes = 0
        self.commit = COMMIT

    def tag_commit(self, tag): return self.commit
    def find_release(self, tag): return copy.deepcopy(self.value)
    def release(self, release_id): return copy.deepcopy(self.value)
    def create_draft(self, tag, commit):
        self.value = {"id": 9, "tag_name": tag, "draft": True}
        return self.release(9)
    def assets(self, release_id):
        return [{"id": value["id"], "name": name, "size": len(value["data"]), "state": value["state"]}
                for name, value in self.files.items()]
    def download(self, asset, destination): destination.write_bytes(self.files[asset["name"]]["data"])
    def delete(self, asset):
        self.deletes.append(asset["name"])
        del self.files[asset["name"]]
    def upload(self, release_id, path):
        self.uploads.append(path.name)
        self.files[path.name] = {"id": len(self.uploads) + 10, "data": path.read_bytes(), "state": "uploaded"}
        if path.name == self.fail_name and self.fail_count > 0:
            self.fail_count -= 1
            self.files[path.name]["state"] = "starter"
            self.files[path.name]["data"] = b""
            raise ApiError("Injected interrupted upload")
    def publish(self, release_id):
        self.publishes += 1
        self.value["draft"] = False


class ReleasePublicationTests(unittest.TestCase):
    def setUp(self):
        self.area = tempfile.TemporaryDirectory()
        self.addCleanup(self.area.cleanup)
        self.bundle = Path(self.area.name)
        for name in BASE_ASSETS - {"SHA256SUMS"}:
            (self.bundle / name).write_bytes((COMMIT + "\n" if name == "SOURCE_COMMIT" else name).encode())
        (self.bundle / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256((self.bundle / name).read_bytes()).hexdigest()}  {name}\n"
            for name in sorted(BASE_ASSETS - {"SHA256SUMS"})))
        (self.bundle / MANIFEST).write_text(json.dumps(manifest_for(self.bundle, COMMIT, TAG, CERT)))
        self.api = FakeGitHub()

    def run_publish(self, **kwargs):
        return publish(self.api, self.bundle, COMMIT, TAG,
                       lambda directory: verify_files(directory, COMMIT, TAG, CERT),
                       sleep=lambda _: None, journal=lambda _: None, **kwargs)

    def test_interrupted_metadata_stays_draft_then_reuses_same_apk(self):
        self.api.fail_name = "pi-deck.cdx.json"
        self.api.fail_count = 3
        with self.assertRaises(ApiError): self.run_publish()
        self.assertTrue(self.api.value["draft"])
        self.assertEqual(0, self.api.publishes)
        self.assertEqual("starter", self.api.files["pi-deck.cdx.json"]["state"])
        apk = self.api.files["pi-deck.apk"]["data"]
        self.run_publish()
        self.assertFalse(self.api.value["draft"])
        self.assertEqual(1, self.api.uploads.count("pi-deck.apk"))
        self.assertEqual(apk, self.api.files["pi-deck.apk"]["data"])
        self.assertEqual(BASE_ASSETS | {MANIFEST}, set(self.api.files))

    def test_transient_failure_retries_without_duplicate_assets(self):
        self.api.fail_name = "models-v2.json"
        self.api.fail_count = 1
        self.run_publish()
        self.assertEqual(1, self.api.publishes)
        self.assertEqual(2, self.api.uploads.count("models-v2.json"))

    def test_repair_is_idempotent_and_checks_actual_bytes(self):
        self.run_publish()
        count = len(self.api.uploads)
        self.run_publish(repair_published=True)
        self.assertEqual(count, len(self.api.uploads))
        # Same filename, size and uploaded state are insufficient.
        self.api.files["models-v2.json"]["data"] = b"x" * len(self.api.files["models-v2.json"]["data"])
        self.run_publish(repair_published=True)
        self.assertEqual(count + 1, len(self.api.uploads))
        self.assertEqual(["models-v2.json"], self.api.deletes)

    def test_wrong_tag_or_release_ownership_never_deletes(self):
        self.run_publish()
        self.api.value["tag_name"] = "v-other"
        with self.assertRaises(ValueError): self.run_publish(repair_published=True)
        self.api.commit = "c" * 40
        with self.assertRaises(ValueError): self.run_publish(repair_published=True)
        self.assertEqual([], self.api.deletes)

    def test_rerun_must_not_replace_a_different_signed_apk(self):
        self.run_publish()
        self.api.files["pi-deck.apk"]["data"] = b"different signed build"
        with self.assertRaisesRegex(ValueError, "original signed artifact"):
            self.run_publish(repair_published=True)
        self.assertEqual([], self.api.deletes)

    def test_published_requires_explicit_repair(self):
        self.run_publish()
        with self.assertRaisesRegex(ValueError, "repair-published"): self.run_publish()

    def test_corrupt_manifest_or_checksum_fails_before_any_upload(self):
        (self.bundle / "models-v2.json").write_text("changed")
        with self.assertRaises(ValueError): self.run_publish()
        self.assertEqual([], self.api.uploads)

    def test_missing_asset_and_wrong_source_commit_fail_closed(self):
        (self.bundle / "SOURCE_COMMIT").write_text("c" * 40 + "\n")
        (self.bundle / MANIFEST).write_text(json.dumps(manifest_for(self.bundle, COMMIT, TAG, CERT)))
        with self.assertRaisesRegex(ValueError, "SOURCE_COMMIT"): self.run_publish()
        (self.bundle / "models-v2.json").unlink()
        with self.assertRaisesRegex(ValueError, "Missing"): self.run_publish()

    def test_final_verification_failure_never_publishes(self):
        calls = []
        def verify(directory):
            calls.append(directory)
            verify_files(directory, COMMIT, TAG, CERT)
            if len(calls) == 2: raise ValueError("APK signature rejected")
        with self.assertRaisesRegex(ValueError, "signature"):
            publish(self.api, self.bundle, COMMIT, TAG, verify, sleep=lambda _: None, journal=lambda _: None)
        self.assertEqual(0, self.api.publishes)
        self.assertTrue(self.api.value["draft"])


if __name__ == "__main__": unittest.main()
