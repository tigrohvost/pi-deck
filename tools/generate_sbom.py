#!/usr/bin/env python3
"""Generate a deterministic CycloneDX SBOM from the pinned Pi shrinkwrap."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import re
import tarfile
import urllib.parse
import uuid
from pathlib import Path
from typing import Any


def npm_purl(name: str, version: str) -> str:
    if name.startswith("@") and "/" in name:
        namespace, package = name.split("/", 1)
        encoded_name = urllib.parse.quote(namespace, safe="") + "/" + urllib.parse.quote(
            package, safe=""
        )
    else:
        encoded_name = urllib.parse.quote(name, safe="")
    return "pkg:npm/" + encoded_name + "@" + version


def package_name(path: str, package: dict[str, Any]) -> str | None:
    declared = package.get("name")
    if isinstance(declared, str):
        return declared
    marker = "node_modules/"
    if marker not in path:
        return None
    return path.rsplit(marker, 1)[1]


def component(name: str, version: str, kind: str = "library") -> dict[str, Any]:
    return {
        "type": kind,
        "name": name,
        "version": version,
        "bom-ref": npm_purl(name, version),
        "purl": npm_purl(name, version),
    }


def integrity_hash(value: str) -> dict[str, str]:
    try:
        algorithm, encoded = value.split("-", 1)
        if algorithm != "sha512":
            raise ValueError("unsupported algorithm")
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("Invalid npm SHA-512 integrity") from error
    if len(raw) != 64:
        raise ValueError("Invalid npm SHA-512 digest length")
    return {"alg": "SHA-512", "content": raw.hex()}


def verified_pi_hashes(raw_tarball: bytes, metadata: dict[str, Any]) -> list[dict[str, str]]:
    expected_sha512 = integrity_hash(metadata["npmIntegrity"])["content"]
    expected_sha1 = metadata["npmShasum"]
    actual_sha512 = hashlib.sha512(raw_tarball).hexdigest()
    actual_sha1 = hashlib.sha1(raw_tarball).hexdigest()
    if actual_sha512 != expected_sha512 or actual_sha1 != expected_sha1:
        raise ValueError("Pinned Pi tarball integrity mismatch")
    return [
        {"alg": "SHA-1", "content": actual_sha1},
        {"alg": "SHA-512", "content": actual_sha512},
    ]


def sidecar_component(metadata: dict[str, Any]) -> dict[str, Any]:
    try:
        flavor = metadata["flavor"]
        build = metadata["build"]
        repository = metadata["repository"]
        commit = metadata["commit"]
        sha256 = metadata["sha256"]
    except (KeyError, TypeError) as error:
        raise ValueError("Native sidecar metadata is incomplete") from error
    if (
        not isinstance(flavor, str)
        or re.fullmatch(r"[a-z0-9][a-z0-9._-]+", flavor) is None
        or not isinstance(build, str)
        or not build
        or not isinstance(repository, str)
        or not repository.startswith("https://github.com/")
        or not isinstance(commit, str)
        or re.fullmatch(r"[0-9a-f]{40}", commit) is None
        or not isinstance(sha256, str)
        or re.fullmatch(r"[0-9a-f]{64}", sha256) is None
    ):
        raise ValueError("Native sidecar metadata is unsafe")
    reference = (
        "pkg:generic/llama-cpp-"
        + urllib.parse.quote(flavor, safe="")
        + "@"
        + urllib.parse.quote(build, safe="")
    )
    return {
        "type": "library",
        "name": "llama-cpp-" + flavor,
        "version": build,
        "bom-ref": reference,
        "purl": reference,
        "hashes": [{"alg": "SHA-256", "content": sha256}],
        "externalReferences": [
            {"type": "vcs", "url": repository + "#" + commit}
        ],
        "properties": [
            {"name": "pideck:runtimeFlavor", "value": flavor},
            {"name": "pideck:sourceCommit", "value": commit},
            {"name": "pideck:resolution", "value": "pinned-source-build"},
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pi-tarball", type=Path, required=True)
    parser.add_argument(
        "--compatibility",
        type=Path,
        default=Path("app/src/main/assets/compatibility.json"),
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    compatibility = json.loads(args.compatibility.read_text(encoding="utf-8"))
    raw_tarball = args.pi_tarball.read_bytes()
    try:
        pi_hashes = verified_pi_hashes(raw_tarball, compatibility["pi"])
    except (KeyError, TypeError, ValueError) as error:
        raise SystemExit(str(error)) from error

    with tarfile.open(args.pi_tarball, "r:gz") as archive:
        shrinkwrap_member = archive.getmember("package/npm-shrinkwrap.json")
        shrinkwrap_stream = archive.extractfile(shrinkwrap_member)
        if shrinkwrap_stream is None:
            raise SystemExit("Pinned Pi tarball has no readable shrinkwrap")
        shrinkwrap = json.load(shrinkwrap_stream)

    components_by_ref: dict[str, dict[str, Any]] = {}
    path_to_ref: dict[str, str] = {}
    for path, package in shrinkwrap["packages"].items():
        if not isinstance(package, dict):
            continue
        name = package_name(path, package)
        version = package.get("version")
        if not isinstance(name, str) or not isinstance(version, str):
            continue
        item = component(name, version, "application" if path == "" else "library")
        item["hashes"] = (
            [integrity_hash(package["integrity"])]
            if isinstance(package.get("integrity"), str)
            else []
        )
        components_by_ref[item["bom-ref"]] = item
        path_to_ref[path] = item["bom-ref"]

    pi_ref = npm_purl(
        compatibility["pi"]["package"],
        compatibility["pi"]["version"],
    )
    if pi_ref not in components_by_ref:
        raise SystemExit("Pinned Pi shrinkwrap has no root package component")
    components_by_ref[pi_ref]["hashes"] = pi_hashes
    components_by_ref[pi_ref]["properties"] = [
        {"name": "pideck:npmGitHead", "value": compatibility["pi"]["gitHead"]}
    ]

    dependencies: dict[str, set[str]] = {reference: set() for reference in components_by_ref}
    for path, package in shrinkwrap["packages"].items():
        source_ref = path_to_ref.get(path)
        if source_ref is None or not isinstance(package, dict):
            continue
        names = set(package.get("dependencies", {})) | set(
            package.get("optionalDependencies", {})
        )
        for name in names:
            candidates = [
                (candidate_path, reference)
                for candidate_path, reference in path_to_ref.items()
                if candidate_path.endswith("/node_modules/" + name)
                or candidate_path == "node_modules/" + name
            ]
            if candidates:
                dependencies[source_ref].add(min(candidates, key=lambda item: len(item[0]))[1])

    app_version = compatibility["appVersion"]
    app_ref = f"pkg:apk/dev.pideck.app@{app_version}"
    app = {
        "type": "application",
        "name": "PI//DECK Android",
        "version": app_version,
        "bom-ref": app_ref,
        "purl": app_ref,
        "properties": [
            {"name": "pideck:baselineCommit", "value": compatibility["baselineCommit"]},
            {
                "name": "pideck:modelManifestSchema",
                "value": str(compatibility["modelManifestSchema"]),
            },
            {
                "name": "pideck:nodeMinimum",
                "value": compatibility["node"]["minimumVersion"],
            },
            {
                "name": "pideck:llamaCppRange",
                "value": (
                    compatibility["llamaCpp"]["minimumVersion"]
                    + ".."
                    + compatibility["llamaCpp"]["maximumTestedVersion"]
                ),
            },
        ],
    }
    native_components = []
    for name, version in (
        ("termux", compatibility["termux"]["minimumVersion"]),
        ("nodejs", ">=" + compatibility["node"]["minimumVersion"]),
        ("python", "termux-repository-resolved"),
        ("llama-cpp", compatibility["llamaCpp"]["maximumTestedVersion"]),
        ("curl", "termux-repository-resolved"),
        ("git", "termux-repository-resolved"),
        ("ripgrep", "termux-repository-resolved"),
        ("jq", "termux-repository-resolved"),
        ("procps", "termux-repository-resolved"),
        ("termux-exec", "termux-repository-resolved"),
        ("termux-api", compatibility["termuxApi"]["minimumVersion"]),
    ):
        reference = f"pkg:generic/termux-{name}@{urllib.parse.quote(version, safe='')}"
        native_components.append(
            {
                "type": "framework" if name == "termux" else "library",
                "name": name,
                "version": version,
                "bom-ref": reference,
                "purl": reference,
                "properties": [
                    {
                        "name": "pideck:resolution",
                        "value": (
                            "exact-compatible-build"
                            if name == "llama-cpp"
                            else "resolved-on-device-by-termux-pkg"
                        ),
                    }
                ],
            }
        )
    native_components.extend(
        sidecar_component(value)
        for value in compatibility["llamaCpp"].get("sidecars", [])
    )
    ordered_components = [app] + sorted(
        [*components_by_ref.values(), *native_components],
        key=lambda item: item["bom-ref"],
    )
    fingerprint = hashlib.sha256(
        "\n".join(item["bom-ref"] for item in ordered_components).encode("utf-8")
    ).hexdigest()
    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{uuid.uuid5(uuid.NAMESPACE_URL, fingerprint)}",
        "version": 1,
        "metadata": {"component": app},
        "components": ordered_components[1:],
        "dependencies": [
            {
                "ref": app_ref,
                "dependsOn": sorted(
                    [
                        npm_purl(
                            compatibility["pi"]["package"],
                            compatibility["pi"]["version"],
                        ),
                        *(item["bom-ref"] for item in native_components),
                    ]
                ),
            },
            *[
                {"ref": reference, "dependsOn": sorted(targets)}
                for reference, targets in sorted(dependencies.items())
            ],
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
