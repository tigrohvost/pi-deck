#!/usr/bin/env python3
"""Verify the exact signed APK and its complete, content-addressed release bundle."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

BASE_ASSETS = frozenset({
    "pi-deck.apk", "pi-deck.cdx.json", "models-v2.json", "compatibility.json",
    "native-runtime.json", "implementation-baseline.md", "BUILD-INSTRUCTIONS.md",
    "SOURCE_COMMIT", "signing-certificate.txt", "apk-info.txt", "SHA256SUMS",
})
MANIFEST = "release-manifest.json"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def manifest_for(bundle: Path, commit: str, tag: str, certificate: str) -> dict:
    require(bool(re.fullmatch(r"[0-9a-f]{40}", commit)), "Invalid source commit")
    require(bool(re.fullmatch(r"v[0-9][A-Za-z0-9.+-]*", tag)), "Invalid version tag")
    require(bool(re.fullmatch(r"[0-9a-f]{64}", certificate)), "Invalid signing certificate pin")
    files = []
    for name in sorted(BASE_ASSETS):
        path = bundle / name
        require(path.is_file() and not path.is_symlink(), f"Missing or unsafe asset: {name}")
        files.append({"name": name, "size": path.stat().st_size, "sha256": sha256(path)})
    return {"schemaVersion": 1, "sourceCommit": commit, "tag": tag,
            "signingCertificateSha256": certificate, "files": files}


def verify_files(bundle: Path, commit: str, tag: str, certificate: str) -> dict:
    expected = manifest_for(bundle, commit, tag, certificate)
    require(not (bundle / MANIFEST).is_symlink(), "Unsafe release manifest")
    actual = json.loads((bundle / MANIFEST).read_text())
    require(actual == expected, "Release manifest does not match expected files, hashes or provenance")
    require((bundle / "SOURCE_COMMIT").read_text().strip() == commit, "SOURCE_COMMIT mismatch")
    checksums = {}
    for line in (bundle / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        require(match is not None, "Malformed SHA256SUMS entry")
        digest, name = match.groups()
        require(name not in checksums, "Duplicate checksum entry")
        checksums[name] = digest
    require(set(checksums) == BASE_ASSETS - {"SHA256SUMS"}, "Incomplete checksum set")
    for name, digest in checksums.items():
        require(sha256(bundle / name) == digest, f"Checksum mismatch: {name}")
    return actual


def run_tool(build_tools: Path, name: str, *arguments: str) -> str:
    result = subprocess.run([str(build_tools / name), *arguments], capture_output=True, text=True, timeout=120)
    require(result.returncode == 0, f"{name} verification failed: {result.stderr[-1500:]}")
    return result.stdout


def verify_bundle(bundle: Path, commit: str, tag: str, certificate: str,
                  build_tools: Path, *, legacy_alpha15: bool = False) -> dict:
    manifest = verify_files(bundle, commit, tag, certificate)
    apk = str(bundle / "pi-deck.apk")
    signing = run_tool(build_tools, "apksigner", "verify", "--verbose", "--print-certs", apk)
    for report in (signing, (bundle / "signing-certificate.txt").read_text()):
        pins = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", report, re.M)
        require(pins == [certificate] and "android debug" not in report.lower(), "APK signing identity mismatch")
    run_tool(build_tools, "zipalign", "-c", "-P", "16", "4", apk)
    badging = run_tool(build_tools, "aapt", "dump", "badging", apk)
    for report in (badging, (bundle / "apk-info.txt").read_text()):
        match = re.search(r"^package: name='dev.pideck.app' versionCode='(\d+)' versionName='([^']+)'", report, re.M)
        require(match is not None and "v" + match[2] == tag, "APK package/version mismatch")
        require("application-debuggable" not in report, "Debuggable APK is forbidden")
        require("native-code: 'arm64-v8a'" in report, "Unexpected APK ABI")
    with zipfile.ZipFile(apk) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate APK entries")
        for name in ("models-v2.json", "compatibility.json", "native-runtime.json"):
            require(archive.read("assets/" + name) == (bundle / name).read_bytes(), f"APK metadata mismatch: {name}")
        compatibility = json.loads((bundle / "compatibility.json").read_text())
        historical = legacy_alpha15 and tag == "v0.3.0-alpha15" and commit == "43faa8b711a8d9ecf1d75b8ef86aa3fb0110ad61"
        require(compatibility["appVersion"] == tag[1:] or (historical and compatibility["appVersion"] == "0.3.0-alpha14"),
                "Compatibility version mismatch")
        native = json.loads((bundle / "native-runtime.json").read_text())
        native_names = {entry.get("file", entry.get("name", "")) for entry in native.get("files", [])}
        require({"lib/arm64-v8a/" + name for name in native_names}
                == {name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")},
                "Native runtime manifest is incomplete")
        for entry in native.get("files", []):
            name = entry.get("file", entry.get("name", ""))
            require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+\.so", name)), "Invalid native filename")
            require(hashlib.sha256(archive.read("lib/arm64-v8a/" + name)).hexdigest() == entry["sha256"],
                    f"APK native runtime mismatch: {name}")
        require(bool(native.get("files")), "Missing native runtime checksums")
    sbom = json.loads((bundle / "pi-deck.cdx.json").read_text())
    require(sbom.get("bomFormat") == "CycloneDX" and bool(sbom.get("components")), "Invalid SBOM")
    require(sbom.get("metadata", {}).get("component", {}).get("version") == compatibility["appVersion"],
            "SBOM application version mismatch")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--certificate", required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--create-manifest", action="store_true")
    parser.add_argument("--legacy-alpha15", action="store_true")
    args = parser.parse_args()
    if args.create_manifest:
        value = manifest_for(args.bundle, args.commit, args.tag, args.certificate)
        (args.bundle / MANIFEST).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    verify_bundle(args.bundle, args.commit, args.tag, args.certificate, args.build_tools, legacy_alpha15=args.legacy_alpha15)
    print(json.dumps({"verified": True, "tag": args.tag, "commit": args.commit, "assets": len(BASE_ASSETS) + 1}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
