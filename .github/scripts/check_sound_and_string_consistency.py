#!/usr/bin/env python3
"""Static consistency checks for the Open Fire Android sources.

These checks mirror (and go beyond) what the JVM unit tests can see:

  1. Every sound key used anywhere in the Kotlin sources is declared in the
     sound catalogue (SoundLibrary, re-exported as AudioManager.SOUND_FILES),
     and every declared key points at a file that really exists in
     app/src/main/assets/sounds.
  2. Every asset sound file is actually referenced by the alias table.
  3. Every translation key used through t("...") exists in the English table,
     and the English, Hindi and Nepali tables define exactly the same keys.
  4. Braces, parentheses and brackets balance in every Kotlin file and no
     Kotlin file mixes tabs into its indentation.
  5. Every sound asset really is an Ogg file: it starts with an Ogg page
     capture pattern and a complete first page header. A truncated or
     non-audio asset (an HTML error page, a partial download, a Git LFS
     pointer) plays as silence on the device.
"""
import os
import re
import sys

ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
SOUNDS_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
MAIN = os.path.join(ROOT, "app", "src", "main", "java")
AUDIO = os.path.join(
    MAIN, "com", "blindtechnexus", "openfire", "audio", "AudioManager.kt"
)
SOUND_LIBRARY = os.path.join(
    MAIN, "com", "blindtechnexus", "openfire", "audio", "SoundLibrary.kt"
)
STRINGS = os.path.join(
    MAIN, "com", "blindtechnexus", "openfire", "data", "Strings.kt"
)

failures = []


def fail(message):
    failures.append(message)


def kotlin_files():
    for base, _dirs, files in os.walk(os.path.join(ROOT, "app", "src")):
        for name in sorted(files):
            if name.endswith(".kt"):
                yield os.path.join(base, name)


def skip_string(src, i):
    """Return the index just past the string literal that starts at src[i]."""
    i += 1
    n = len(src)
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if c == '"':
            return i + 1
        if c == "$" and i + 1 < n and src[i + 1] == "{":
            # A template expression holds real code, so its braces must be
            # walked with the same nesting rules as normal code.
            depth = 1
            i += 2
            while i < n and depth:
                d = src[i]
                if d == "{":
                    depth += 1
                elif d == "}":
                    depth -= 1
                elif d == '"':
                    i = skip_string(src, i) - 1
                elif d == "\\":
                    i += 1
                i += 1
            continue
        i += 1
    return i


def strip_noise(text):
    """Remove comments, string literals and char literals for structure checks."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"':
            i = skip_string(text, i)
            continue
        if c == "'":
            j = i + 1
            if j < n and text[j] == "\\":
                j += 1
            j += 1
            if j < n and text[j] == "'":
                i = j + 1
                continue
            # A plain apostrophe inside prose, not a character literal.
            out.append(" ")
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


# ---------------------------------------------------------------- 1 & 2: sounds
assets = set(os.listdir(SOUNDS_DIR))
# The catalogue lives in one place and is re-exported by AudioManager. The
# native engine reads SoundLibrary directly, so that is the table to verify.
catalogue = open(SOUND_LIBRARY, encoding="utf-8").read() if os.path.exists(SOUND_LIBRARY) else ""
if "val SOUND_FILES: Map<String, String> = mapOf(" in catalogue:
    audio_src = catalogue
else:
    audio_src = open(AUDIO, encoding="utf-8").read()

marker = "val SOUND_FILES: Map<String, String> = mapOf("
if marker not in audio_src:
    fail("could not find the SOUND_FILES table in SoundLibrary.kt or AudioManager.kt")
    print("\n".join(failures))
    sys.exit(1)
alias_block = audio_src.split(marker, 1)[1]
alias_block = alias_block.split("\n    )", 1)[0]
aliases = {}
for key, file_name in re.findall(r'"([A-Za-z0-9]+)"\s+to\s+"([^"]+)"', alias_block):
    aliases.setdefault(key, []).append(file_name)

if not aliases:
    fail("could not parse SOUND_FILES from the sound catalogue")

alias_keys = re.findall(r'"([A-Za-z0-9]+)"\s+to\s+"', alias_block)
for key in sorted(set(alias_keys)):
    if alias_keys.count(key) > 1:
        fail("sound key '%s' is declared more than once" % key)

for key, files in sorted(aliases.items()):
    for name in files:
        if name not in assets:
            fail("sound key '%s' points at missing asset sounds/%s" % (key, name))

referenced = set()
for files in aliases.values():
    referenced.update(files)
for name in sorted(assets):
    if name not in referenced:
        fail("asset sounds/%s is never referenced by soundAliases" % name)

# Every sound key used in the app must exist in the alias table.
used_keys = set()
for path in kotlin_files():
    if path == AUDIO:
        continue
    src = open(path, encoding="utf-8").read()
    for call in re.findall(
        r'\b(?:playSound|playLoop|stopSound|stopLoop)\(\s*"([^"]+)"', src
    ):
        used_keys.add((call, path))
    # Sound keys that come from the Weapon enum.
for path in kotlin_files():
    src = open(path, encoding="utf-8").read()
    for key in re.findall(
        r'(?:fireSoundKey|switchSoundKey|hitSoundKey|missSoundKey)\s*=\s*"([^"]+)"', src
    ):
        used_keys.add((key, path))

for key, path in sorted(used_keys):
    if key not in aliases:
        fail("sound key '%s' used in %s is not declared in soundAliases" % (key, path))

# ------------------------------------------------------------------- 3: strings
strings_src = open(STRINGS, encoding="utf-8").read()
tables = {}
for name in ("EN", "HI", "NE"):
    block = strings_src.split("private val %s = mapOf(" % name, 1)[1]
    block = block.split("\n    )", 1)[0]
    keys = re.findall(r'^\s{8}"([^"]+)" to ', block, flags=re.M)
    for key in sorted(set(keys)):
        if keys.count(key) > 1:
            fail("translation key '%s' is declared twice in %s" % (key, name))
    tables[name] = set(keys)
    if not tables[name]:
        fail("could not parse the %s string table" % name)

for lang in ("HI", "NE"):
    missing = tables["EN"] - tables[lang]
    extra = tables[lang] - tables["EN"]
    for key in sorted(missing):
        fail("missing %s translation for '%s'" % (lang, key))
    for key in sorted(extra):
        fail("extra %s translation for '%s'" % (lang, key))

for path in kotlin_files():
    src = open(path, encoding="utf-8").read()
    for key in re.findall(r'\bt\(\s*"([^"]+)"\s*\)', src):
        if key not in tables["EN"]:
            fail("translation key '%s' used in %s does not exist" % (key, path))
    for key in re.findall(r'(?:nameKey|pluralKey|descriptionKey)\s*=\s*"([^"]+)"', src):
        if key not in tables["EN"]:
            fail("translation key '%s' used in %s does not exist" % (key, path))

# ------------------------------------------------------------------ 4: structure
for path in kotlin_files():
    src = open(path, encoding="utf-8").read()
    clean = strip_noise(src)
    for open_ch, close_ch in (("{", "}"), ("(", ")"), ("[", "]")):
        if clean.count(open_ch) != clean.count(close_ch):
            fail(
                "%s: %s=%d but %s=%d"
                % (
                    os.path.relpath(path, ROOT),
                    open_ch,
                    clean.count(open_ch),
                    close_ch,
                    clean.count(close_ch),
                )
            )
    for number, line in enumerate(src.split("\n"), start=1):
        if line.startswith("\t") or "\t" in line[: len(line) - len(line.lstrip())]:
            fail("%s:%d uses tab indentation" % (os.path.relpath(path, ROOT), number))

# ------------------------------------------------------------- 5: OGG assets
# Every sound asset is an Ogg container (RFC 3533): it starts with a capture
# pattern "OggS" and its first page header is complete. A truncated download,
# an HTML error page or a Git LFS pointer fails this check instead of playing
# as silence on the device.
OGG_CAPTURE = b"OggS"
OGG_PAGE_HEADER_BYTES = 27

for name in sorted(assets):
    path = os.path.join(SOUNDS_DIR, name)
    with open(path, "rb") as handle:
        data = handle.read()
    if not name.endswith(".ogg"):
        fail("sound asset sounds/%s is not an .ogg file" % name)
        continue
    if not data:
        fail("sound asset sounds/%s is empty" % name)
        continue
    if data[:4] != OGG_CAPTURE:
        fail("sound asset sounds/%s does not start with an Ogg page (OggS)" % name)
        continue
    if len(data) < OGG_PAGE_HEADER_BYTES:
        fail("sound asset sounds/%s is truncated inside its first Ogg page" % name)
        continue
    segment_count = data[26]
    if len(data) < OGG_PAGE_HEADER_BYTES + segment_count:
        fail("sound asset sounds/%s is truncated inside its segment table" % name)

# ---------------------------------------------------------------------- report
print("sound keys declared : %d" % len(aliases))
print("sound assets present: %d" % len(assets))
print("translation keys    : EN=%d HI=%d NE=%d" % (
    len(tables["EN"]), len(tables["HI"]), len(tables["NE"])))

if failures:
    print("\nFAILURES (%d):" % len(failures))
    for message in failures:
        print("  - " + message)
    sys.exit(1)
print("\nAll static consistency checks passed.")
