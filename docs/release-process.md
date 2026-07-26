# Release process

## Local verification

Use JDK 21 and Android SDK 35:

```sh
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/runtime -v
python3 tools/validate_benchmark.py
```

`assembleRelease` without credentials intentionally produces an unsigned
candidate. Debug signing is never substituted.

## Production signing

Create a long-lived release key outside the repository and configure these CI
secrets:

```text
PIDECK_RELEASE_KEYSTORE_B64
PIDECK_RELEASE_STORE_PASSWORD
PIDECK_RELEASE_KEY_ALIAS
PIDECK_RELEASE_KEY_PASSWORD
```

Create an annotated cryptographically signed `v*` tag. The release job requires
GitHub to report that tag signature as verified, injects the key only for the
job, runs `verifyProductionSigning`, verifies the APK with `apksigner`, rejects
an Android Debug subject and publishes:

- production-signed APK;
- SHA-256 checksum file;
- exact model and compatibility manifests;
- CycloneDX SBOM for Pi's published shrinkwrap plus Termux runtime requirements;
- build instructions and baseline.

The keystore must never be committed. A release is blocked if any required
secret, tag verification, test, lint, integrity or signature check fails.

## Reproducibility limits

Pi and its npm graph are content-pinned. Gradle wrapper/plugins and model assets
are pinned by the repository. Termux `pkg` currently resolves native package
versions from the user's configured repository; the SBOM labels those entries
as device-resolved. Reproducible Termux apt snapshots and byte-for-byte APK
reproducibility are unresolved release-hardening work and must not be claimed.

Runtime updates stage and smoke-test the exact Pi version, then atomically
switch the active symlink. A failed reinstall restores the previous target.
User `AGENTS.md`, models and sessions are outside the replaced runtime tree.
