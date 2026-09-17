# Release process

## Local verification

Use JDK 21 and Android SDK 35:

```sh
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/runtime -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/tools -v
python3 tools/validate_benchmark.py
```

`assembleRelease` without credentials intentionally produces an unsigned
candidate. Debug signing is never substituted.

Run the exact sideload candidate through the release-visible device path. The
default `install -r` preserves app data; `--skip-install` is only for an APK
already installed and still requires its on-device hash to match:

```sh
python3 tools/adb_release_acceptance.py \
  --serial R5CW11HGLVV \
  --apk build/release/pi-deck.apk \
  --report build/release/device-acceptance.json \
  --screen-cycle
```

The runner fails closed unless the installed APK hash equals the input, linked
boot never flashes a repair card, READY returns, an exact answer and a real
approved `pideck_bash` read of `/proc/sys/kernel/random/uuid` complete with a
UUID-shaped result, release-visible diagnostics show both terminal records,
lifecycle survives, and no app crash/LMK is observed. It bounds diagnostics by
a device timestamp and never clears the shared logcat buffer. A full secure
reboot still needs the owner to unlock Android once. Acceptance of an
Android-debug-signed APK proves sideload readiness, not production signing.

## Production signing

Create a long-lived release key outside the repository and configure these CI
secrets:

```text
PIDECK_RELEASE_KEYSTORE_B64
PIDECK_RELEASE_STORE_PASSWORD
PIDECK_RELEASE_KEY_ALIAS
PIDECK_RELEASE_KEY_PASSWORD
```

Every push to `main` (and a manual run on `main`) signs the exact unsigned
release APK that passed the build, unit tests, lint and runtime checks. Download
`pi-deck-signed-release` from the successful Actions run. Pull requests receive
only debug and unsigned artifacts; they do not receive signing secrets.

The repository variable `PIDECK_RELEASE_CERT_SHA256` pins the expected APK
certificate. CI checks it with `apksigner`, rejects Android Debug certificates
and debuggable APKs, and checks 16 KiB ZIP alignment. The signing key is
long-lived and must be retained for future in-place upgrades.

For a public GitHub Release, create an annotated OpenPGP-signed `v*` tag whose
version matches `versionName` in `app/build.gradle.kts`. CI verifies the tag
against `.github/release-signing.asc` read from `origin/main`, and checks that
the release commit belongs to `main`. This is repository-pinned verification;
it does not depend on GitHub's account-level Verified badge. Only the public
tag verification key is committed; the private tag key stays outside Git and
CI. A tag can be created using the owner's dedicated signing key:

```sh
GNUPGHOME=/path/to/private/tag-keyring git -c gpg.format=openpgp \
  -c user.signingkey=E683F63AE7A8345F7B39EF9112A3798831C3741F \
  tag -s v0.3.0-alpha15 -m 'PI//DECK 0.3.0-alpha15'
git push origin v0.3.0-alpha15
```

The tag workflow publishes:

- production-signed APK;
- SHA-256 checksums and exact source commit;
- APK version/ABI information and signing-certificate report;
- exact model, compatibility and native-runtime manifests;
- CycloneDX SBOM for Pi's published shrinkwrap plus Termux runtime requirements;
- build instructions and baseline.

Versions containing a hyphen (such as `alpha14`) are published as prereleases.
Private keystores and passwords must never be committed. Missing signing
inputs, a wrong tag/version/certificate, or a failed validation blocks release.

Earlier test APKs used the Android Debug certificate. The production key is
different, so Android cannot install a production APK over a debug-signed app
with the same package name. Preserve/export existing data before any manual
migration; the release workflow does not uninstall or modify an installed app.
Automated host checks do not replace acceptance of the exact APK on a handset.

## Reproducibility limits

Pi and its npm graph are content-pinned. Gradle wrapper/plugins and model assets
are pinned by the repository. Termux `pkg` still resolves native package
versions from the user's configured repository; runtime contract 56 records the
exact installed versions of the requested packages in the safe diagnostic
manifest, and the SBOM labels them as device-resolved. Reproducible Termux apt
snapshots and byte-for-byte APK reproducibility remain unresolved and must not
be claimed.

Runtime updates stage and smoke-test the exact Pi version, then atomically
switch the active symlink. A failed reinstall restores the previous target.
User `AGENTS.md`, models and sessions are outside the replaced runtime tree.
