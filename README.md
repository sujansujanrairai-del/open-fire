# OpenFire Beta

An audio-first battle royale for Android, built for blind and low-vision players:
3D-positioned sound cues, gesture-only controls, speech feedback and a fully
spoken interface (English, हिन्दी and नेपाली).

* Version: 1.2 Beta (version code 14)
* Package: `com.blindtechnexus.openfire`
* Minimum Android: 7.0 (API 24), target API 36
* Language: Kotlin + Jetpack Compose

## Install the app

Every successful CI run publishes the canonical unsigned release APK:

| File | What it is |
| --- | --- |
| `open-fire-release-unsigned.apk` | Canonical, uncompressed-native-aligned unsigned release package. |

1. Open the **Actions** tab, pick the newest green **Android CI and Release APKs** run.
2. Download the `open-fire-release-apks.zip` artifact and unzip it.
3. Sign `open-fire-release-unsigned.apk` with your release key using `scripts/sign-release-apk.sh` (or `apksigner`).
4. Install `open-fire-release.apk` on your device (or run `adb install -r open-fire-release.apk`).

Android will refuse an unsigned APK with **"The package appears to be invalid"** if installed directly without signing. Signing an already-signed APK (such as testkey signed) will also corrupt signatures and cause the same error. The unsigned release package is properly aligned and ready for clean signing with your key. See [docs/INSTALLING.md](docs/INSTALLING.md).

### Signing with your own key

```sh
scripts/sign-release-apk.sh open-fire-release-unsigned.apk --out open-fire-release.apk \
  --ks my-upload-key.jks --ks-alias upload
```

The script aligns the APK first, then signs with the JAR (v1), v2 and v3 schemes, then
verifies the result for API 24..36 and refuses to leave a package that Android would
reject. Verify any APK — including one you received from someone else — with:

```sh
scripts/verify-installable-apk.sh open-fire-release.apk
```

## Build from source

Requirements: JDK 17+ (CI uses 21), Android SDK with platform 36 and build-tools 36.

```sh
./gradlew :app:assembleDebug          # installable debug build
./gradlew :app:assembleRelease        # signed with the CI test key unless a key is configured
./gradlew :app:testDebugUnitTest      # JVM unit tests (Robolectric included)
```

Signing keys are resolved in this order:

1. `KEYSTORE_PATH` + `STORE_PASSWORD` + `KEY_PASSWORD` (and `KEY_ALIAS`, default `upload`),
2. `my-upload-key.jks` in the repository root (with the same passwords),
3. `.github/ci/open-fire-test.p12` — the public CI test key, alias `open-fire-ci`,
   password `openfireci`.

## Repository layout

```
app/                      Android application module
  src/main/assets/sounds/ uncompressed audio assets (played through AssetManager.openFd)
  src/main/java/          Kotlin sources (audio, model, ui, viewmodel, data)
  src/test/               JVM unit tests
.github/workflows/        CI: tests, release build, signing, packaging
.github/scripts/          Python/Java release-archive tooling and tests
.github/ci/               public CI test signing key (not for distribution)
scripts/                  signing and APK verification helpers
docs/INSTALLING.md        why an APK is rejected as invalid, and how to fix it
```

## Tests

```sh
python3 .github/scripts/check_sound_and_string_consistency.py   # assets/translations
python3 -m unittest discover -s .github/scripts/tests -v        # release archive tooling
./gradlew :app:testDebugUnitTest                                # game logic
```

The release tooling enforces the packaging contract that Android's installer expects:
consistent ZIP headers, `resources.arsc` and sound assets stored and 4-byte aligned,
intact APK Signing Blocks, and no rewriting of signed packages.
