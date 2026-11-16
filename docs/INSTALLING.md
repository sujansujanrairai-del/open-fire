# Why Android says "The package appears to be invalid"

This document lists every reason an OpenFire Beta APK can be rejected at install time, what
the build now does about each one, and how to check a file yourself.

The message itself is useless: `PackageInstaller` maps a whole family of
`INSTALL_PARSE_FAILED_*` / `INSTALL_FAILED_*` results to the same one-line text, and
`adb install` is the only place where the real reason shows up. The checks below are what
the platform actually enforces, in the order it enforces them.

## 1. The APK is not signed

An unsigned package never installs. `apksigner verify` fails with
`DOES NOT VERIFY / ERROR: Missing signatures`.

A release build from Gradle is *not* signed unless a signing config is applied, and the
file is called `app-release-unsigned.apk` for a reason. Renaming it does not sign it.

**Now:** CI publishes the canonicalized, aligned `open-fire-release-unsigned.apk` (testkey signing has been removed to prevent broken double-signed packages). Sign it cleanly with your own key before installing.

## 2. The APK is signed with the JAR (v1) scheme only

From the Android 11 behaviour changes:

> Apps that target Android 11 (API level 30) that are currently only signed using APK
> Signature Scheme v1 must now also be signed using APK Signature Scheme v2 or higher.
> Users can't install or update apps that are only signed with APK Signature Scheme v1 on
> devices that run Android 11.

OpenFire Beta targets API 36, so a v1-only signature is unusable on any modern device. Tools
that produce it: `jarsigner`, most "APK signer" web sites, several file managers and old
GUI signers, and `apksigner sign --v2-signing-enabled false`.

**Now:** `scripts/sign-release-apk.sh` always passes
`--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true`, and
`scripts/verify-installable-apk.sh` fails the build if v2 and v3 are missing.

## 3. The APK was aligned (or otherwise modified) after signing

`zipalign` and `zip` rewrite the archive, which destroys the APK Signing Block. Android
then sees a package whose central directory does not match its signature and reports
"invalid".

> Caution: If you sign your APK using `apksigner` and make further changes to the APK, the
> APK's signature is invalidated. If you use `zipalign` to align your APK, use it before
> signing the APK. — Android developer documentation

**Now:** the order is enforced by `scripts/sign-release-apk.sh` (align → sign → verify), and
`prepare_release_archive.py` refuses to rewrite an APK that already carries a signing block
instead of silently stripping it.

## 4. The APK was re-signed on top of an older signature

Signing an already-signed APK leaves the old `META-INF/*.SF`, `*.RSA` files in place. When
those do not match the new signature, `StrictJarVerifier` fails and the install is
rejected.

**Now:** the signing script refuses an input that is already signed and tells you to start
from the `*-unsigned.apk` artifact.

## 5. `resources.arsc` or native libraries (`.so`) are compressed or not aligned
 
For apps targeting Android 11 or higher, the platform refuses to install a package whose
`resources.arsc` is compressed, or is stored but not 4-byte aligned, because it cannot be
memory-mapped. Similarly, when `extractNativeLibs="false"`, all native libraries (`.so`)
must be stored uncompressed (`ZIP_STORED`) and page-aligned (16 KiB boundary for 16 KB page
devices / Android 15+).

**Now:** CI canonicalizes the APK before signing (resources.arsc and `.so` files stored + aligned),
declares the audio extensions in `androidResources.noCompress`, configures `useLegacyPackaging = false`,
and `verify-installable-apk.sh` checks all alignment and storage conditions.

## 6. The ZIP is damaged

A truncated download, a broken central directory, a Local File Header that disagrees with
the Central Directory, or a Data Descriptor that no reader expects all end the same way.
`apksig` additionally inflates entries based on the Central Directory, which is why a
"stored" payload declared as "deflate" surfaces as
`W: Malformed ZIP entry` / `W: invalid stored block lengths`.

**Now:** `prepare_release_archive.py` validates both header sets, inflates every entry the
way apksig does, and the workflow runs `unzip -t`, `zipalign -c 4` and a Java
`ZipFile`/`ZipInputStream` round trip on every published file.

## 7. It is the wrong package for the device

* the device runs an older Android than the APK's `minSdkVersion` (24 = Android 7.0),
* the APK is a split/`.apks` bundle member installed on its own,
* or the app is already installed with a **different signing key** — that last one shows up
  as a package conflict, and the only fix is to uninstall the old copy first
  (`adb uninstall com.blindtechnexus.openfire`).

## Check any APK yourself

```sh
scripts/verify-installable-apk.sh open-fire-release-unsigned.apk
scripts/verify-installable-apk.sh --require-signature open-fire-release.apk
```

It prints one line per check (ZIP integrity, required entries, `resources.arsc` and `.so`
compression, alignment, v1/v2/v3 schemes, signature verification for API 24..36, signer
identity) and a verdict. For the raw platform reason of a failed install:

```sh
adb install -r open-fire-release.apk
```

## Which file should I install?

| Artifact | Installable | Use it for |
| --- | --- | --- |
| `open-fire-release-unsigned.apk` | sign first | Canonical release package: sign with your own key using `scripts/sign-release-apk.sh` |
| `open-fire-release.apk` | yes (after signing) | Installing on a phone (`adb install -r open-fire-release.apk`) |
