#!/usr/bin/env python3
"""Normalize unsigned release APKs and package a standard seekable ZIP archive.

Failure signature this script exists to prevent
===============================================
Tools built on ``com.android.apksig`` (``apksigner``, Android Studio, AI Studio and
several third-party APK utilities) report a broken release APK as::

    W: Malformed ZIP entry: assets/sounds/medkit.ogg
    W: invalid stored block lengths

``com.android.apksig.internal.zip.LocalFileRecord`` resolves an entry's payload from
the **Central Directory** (compression method, compressed size) and then inflates it
(``outputUncompressedData``/``getUncompressedData``).  When the recorded method says
``DEFLATE`` but the bytes are really stored audio, or when the Local File Header and
the Central Directory disagree about method/flags/sizes, or when the payload is
truncated, the inflater is handed raw MP3 bytes.  An MP3 that starts with
``ID3\\x04`` begins with ``0x49`` = ``BFINAL=1, BTYPE=00`` (stored block) followed by
``LEN=0x3344``/``NLEN=0x0004``, so zlib fails immediately with::

    invalid stored block lengths

and apksig wraps that cause in ``ApkFormatException("Malformed ZIP entry: " + name)``.

The sound assets must stay ``ZIP_STORED``: ``AudioManager`` opens them with
``AssetManager.openFd()``, which cannot descriptor-map compressed entries.  The sound
entries are therefore both the ones that must remain uncompressed *and* the ones whose
raw bytes look like a bogus DEFLATE stream when a header lies about the method.

Packaging contract (enforced here, in CI, for the archive and for every APK in it)
=================================================================================
* Exactly one canonical set of ZIP records: Local File Header and Central Directory
  agree on name, flags, method, CRC-32 and both sizes; no GPB bit 3 (Data Descriptor),
  no encryption, no ZIP64, nothing after the End of Central Directory.
* ``resources.arsc`` and ``assets/sounds/*.ogg`` are ``ZIP_STORED`` and 4-byte aligned
  so the framework can mmap them (``openFd``).
* Every payload decodes with the method that the Central Directory declares, exactly
  reproducing what apksig's inflater will do, so "Malformed ZIP entry" and
  "invalid stored block lengths" cannot reach the published artifact.

The outer archive stores the APKs without compression: APKs are already ZIP files, so
recompressing them saves little and adds another DEFLATE stream to decode.  The upload
action must upload this file directly (``archive: false``), not wrap it in another ZIP.

Signed APKs (and "the package appears to be invalid")
=====================================================
Android does not install unsigned packages, and since Android 11 it refuses
packages that target API 30+ and are signed with APK Signature Scheme v1 only
("apps that target Android 11 that are currently only signed using APK Signature
Scheme v1 must now also be signed using APK Signature Scheme v2 or higher").
Both failures surface as *"The package appears to be invalid"* on the device,
with no hint about the cause.

A v2+ signature lives in the **APK Signing Block**, a structure between the last
ZIP entry and the Central Directory, plus (for v1) ``META-INF/MANIFEST.MF``,
``META-INF/*.SF`` and ``META-INF/*.RSA`` entries.  Any tool that rewrites the
archive -- ``zip``, ``zipalign``, this script's canonicalizer -- invalidates it.
That is why this module:

* validates the signing block (size fields, ID-value pairs) when it is present,
* never rewrites an APK that already carries one, and
* is meant to run **before** signing; the signing step is
  ``scripts/sign-release-apk.sh`` (align, then sign, then verify).
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path

LFH_SIGNATURE = 0x04034B50
CDH_SIGNATURE = 0x02014B50
EOCD_SIGNATURE = 0x06054B50

# An APK signed with APK Signature Scheme v2+ carries a signing block between the
# last ZIP entry and the Central Directory -- the layout is
#   [uint64 size][ID-value pair...][uint64 size]["APK Sig Block 42"][Central Directory]
# Android's PackageParser (and apksig) resolve the Central Directory first and
# then require the block's two size fields to agree.  Rewriting a signed APK with
# zip/zipalign drops or invalidates this block, which is the "app is not
# installed / package appears to be invalid" failure for a package that looks
# perfectly fine to a ZIP extractor.
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SIG_BLOCK_MAGIC_SIZE = len(SIG_BLOCK_MAGIC)
SIG_BLOCK_SIZE_FIELD = 8

GPB_ENCRYPTED_MASK = 0x0001
GPB_DATA_DESCRIPTOR_MASK = 0x0008
GPB_STRONG_ENCRYPTION_MASK = 0x0040
GPB_EFS_MASK = 0x0800  # UTF-8 file name (general purpose bit 11)
SUPPORTED_GPB_FLAGS = GPB_EFS_MASK

ALIGNMENT_EXTRA_ID = 0xD935
DEFAULT_ALIGNMENT = 4
NATIVE_LIBRARY_ALIGNMENT = 16384
ALIGNMENT_EXTRA_HEADER_SIZE = 6  # id + data size + alignment value

ZIP_VERSION = 20  # 2.0: DEFLATE and Data Descriptors are understood
DEFAULT_DATE = 0x0021  # 1980-01-01
STREAM_CHUNK_SIZE = 4096
SAFE_NAME_BYTES = set(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                      b"!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ ")


class ZipValidationError(RuntimeError):
    """Raised when a ZIP or APK fails structural or stream compatibility checks."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def must_store(name: str) -> bool:
    """Entries the Android runtime must be able to mmap straight out of the APK."""
    return (
        name == "resources.arsc"
        or name.endswith(".so")
        or (name.startswith("assets/sounds/") and name.endswith(".ogg"))
    )


def alignment_for(name: str) -> int:
    return NATIVE_LIBRARY_ALIGNMENT if name.endswith(".so") else DEFAULT_ALIGNMENT


def inflate_exact(
    payload: bytes,
    *,
    expected_usize: int,
    expected_crc: int,
    label: str,
) -> bytes:
    """Inflate a raw DEFLATE stream the way apksig does and verify size and CRC."""
    inflater = zlib.decompressobj(-zlib.MAX_WBITS)
    chunks = []
    try:
        for offset in range(0, len(payload), STREAM_CHUNK_SIZE):
            chunk = inflater.decompress(payload[offset : offset + STREAM_CHUNK_SIZE])
            if chunk:
                chunks.append(chunk)
        tail = inflater.flush()
        if tail:
            chunks.append(tail)
    except zlib.error as exc:
        raise ZipValidationError(
            f"{label}: DEFLATE stream error: {exc} "
            f"(the entry declares DEFLATE but its bytes are not a DEFLATE stream)"
        ) from exc
    raw = b"".join(chunks)
    if not inflater.eof:
        raise ZipValidationError(f"{label}: truncated DEFLATE stream (no final block)")
    if inflater.unused_data:
        raise ZipValidationError(
            f"{label}: {len(inflater.unused_data)} unused bytes after the DEFLATE stream"
        )
    if len(raw) != expected_usize:
        raise ZipValidationError(
            f"{label}: inflated size {len(raw)} != declared {expected_usize}"
        )
    actual_crc = zlib.crc32(raw) & 0xFFFFFFFF
    if actual_crc != expected_crc:
        raise ZipValidationError(
            f"{label}: inflated CRC {actual_crc:#010x} != declared {expected_crc:#010x}"
        )
    return raw


def deflate_raw(payload: bytes, level: int = 6) -> bytes:
    compressor = zlib.compressobj(level, zlib.DEFLATED, -zlib.MAX_WBITS)
    return compressor.compress(payload) + compressor.flush()


def alignment_extra_field(alignment: int, data_start_without_extra: int) -> bytes:
    """Return a well-formed 0xD935 alignment extra field (the layout zipalign uses)."""
    padding = (-(data_start_without_extra + ALIGNMENT_EXTRA_HEADER_SIZE)) % alignment
    header = struct.pack("<HHH", ALIGNMENT_EXTRA_ID, 2 + padding, alignment)
    return header + b"\x00" * padding


def apk_signing_block_range(
    data: bytes,
    cd_offset: int,
    label: object,
) -> tuple[int, int] | None:
    """Return ``(start, end)`` of the APK Signing Block that ends at ``cd_offset``.

    ``None`` means the archive has no signing block at all (an unsigned APK, or a
    plain ZIP).  A block that is present but malformed raises: Android resolves
    the Central Directory first and then requires both size fields of the block
    to agree, so a broken block makes the device refuse the package
    ("The package appears to be invalid") even though every ZIP tool still reads
    the file happily.
    """
    if cd_offset < SIG_BLOCK_MAGIC_SIZE + 2 * SIG_BLOCK_SIZE_FIELD:
        return None
    if data[cd_offset - SIG_BLOCK_MAGIC_SIZE : cd_offset] != SIG_BLOCK_MAGIC:
        return None

    trailing_size_offset = cd_offset - SIG_BLOCK_MAGIC_SIZE - SIG_BLOCK_SIZE_FIELD
    trailing_size = struct.unpack_from("<Q", data, trailing_size_offset)[0]
    minimum_size = SIG_BLOCK_MAGIC_SIZE + SIG_BLOCK_SIZE_FIELD
    if trailing_size < minimum_size:
        raise ZipValidationError(
            f"{label}: APK Signing Block declares {trailing_size} bytes, which is smaller "
            f"than the {minimum_size}-byte minimum"
        )

    start = cd_offset - SIG_BLOCK_SIZE_FIELD - trailing_size
    if start < 0:
        raise ZipValidationError(
            f"{label}: APK Signing Block declares {trailing_size} bytes, which does not fit "
            f"in front of the Central Directory at offset {cd_offset}"
        )
    leading_size = struct.unpack_from("<Q", data, start)[0]
    if leading_size != trailing_size:
        raise ZipValidationError(
            f"{label}: APK Signing Block size fields disagree ({leading_size} != "
            f"{trailing_size}); Android rejects an APK with a malformed signing block"
        )

    # Walk the ID-value pairs exactly like apksig does, so a truncated pair list is
    # reported here instead of on a user's phone. Pair values are opaque: the v2
    # padding pair (0x42726577, "werB") that apksig writes to align the block is
    # not required to be zero-filled, and unknown future schemes must not be
    # rejected just for being unknown.
    pos = start + SIG_BLOCK_SIZE_FIELD
    pair_count = 0
    while pos < trailing_size_offset:
        if pos + SIG_BLOCK_SIZE_FIELD > trailing_size_offset:
            raise ZipValidationError(
                f"{label}: APK Signing Block has a truncated ID-value pair at offset {pos}"
            )
        pair_size = struct.unpack_from("<Q", data, pos)[0]
        if pair_size < 4 or pos + SIG_BLOCK_SIZE_FIELD + pair_size > trailing_size_offset:
            raise ZipValidationError(
                f"{label}: APK Signing Block ID-value pair at offset {pos} declares "
                f"{pair_size} bytes, which overflows the block"
            )
        pos += SIG_BLOCK_SIZE_FIELD + pair_size
        pair_count += 1

    if pos != trailing_size_offset:
        raise ZipValidationError(
            f"{label}: APK Signing Block ID-value pairs overrun the block by "
            f"{pos - trailing_size_offset} bytes"
        )
    if pair_count == 0:
        raise ZipValidationError(f"{label}: APK Signing Block contains no ID-value pairs")

    return start, cd_offset


def _central_directory_offset(data: bytes, label: object) -> int:
    search_start = max(0, len(data) - (22 + 65535))
    eocd_pos = data.rfind(b"PK\x05\x06", search_start)
    if eocd_pos < 0 or eocd_pos + 22 > len(data):
        raise ZipValidationError(f"{label}: missing End of Central Directory signature")
    return struct.unpack_from("<I", data, eocd_pos + 16)[0]


def apk_has_signing_block(apk_path: Path) -> bool:
    """True when the APK carries an APK Signing Block (APK Signature Scheme v2+)."""
    data = apk_path.read_bytes()
    return (
        apk_signing_block_range(data, _central_directory_offset(data, apk_path), apk_path)
        is not None
    )


def _decode_name(raw_name: bytes, path: Path) -> str:
    try:
        return raw_name.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ZipValidationError(f"{path}: entry name is not valid UTF-8: {raw_name!r}") from exc


def parse_and_validate_zip_headers(
    archive_path: Path,
    *,
    forbid_extra_fields: bool = False,
    check_contiguous: bool = True,
    allow_data_descriptors: bool = False,
) -> list[dict[str, int | str | bytes]]:
    """Validate that every entry has complete LFH fields and no bit-3 Data Descriptor.

    ``allow_data_descriptors`` is only used by the normalization pass, which has to read
    archives that older build tools wrote in streaming mode (GPB bit 3 with zeroed LFH
    sizes) before rewriting them into canonical form.  Every other caller gets the strict
    contract that apksig and the platform expect.
    """
    data = archive_path.read_bytes()
    size = len(data)
    if size < 22:
        raise ZipValidationError(f"{archive_path}: file too small to be a valid ZIP ({size} bytes)")

    # Locate End of Central Directory (EOCD) record (allow up to 64 KiB comment)
    search_start = max(0, size - (22 + 65535))
    eocd_pos = data.rfind(b"PK\x05\x06", search_start)
    if eocd_pos < 0:
        raise ZipValidationError(f"{archive_path}: missing End of Central Directory signature")
    if eocd_pos + 22 > size:
        raise ZipValidationError(f"{archive_path}: truncated End of Central Directory")

    (
        eocd_sig,
        disk_num,
        cd_start_disk,
        entries_on_disk,
        total_entries,
        cd_size,
        cd_offset,
        comment_len,
    ) = struct.unpack_from("<IHHHHIIH", data, eocd_pos)

    if eocd_sig != EOCD_SIGNATURE:
        raise ZipValidationError(f"{archive_path}: invalid EOCD signature {eocd_sig:#x}")
    if disk_num != 0 or cd_start_disk != 0 or entries_on_disk != total_entries:
        raise ZipValidationError(f"{archive_path}: multi-disk or ZIP64 EOCD not expected")
    if eocd_pos + 22 + comment_len != size:
        raise ZipValidationError(
            f"{archive_path}: trailing bytes after EOCD ({eocd_pos + 22 + comment_len} != {size})"
        )
    if cd_offset + cd_size != eocd_pos:
        raise ZipValidationError(
            f"{archive_path}: central directory range [{cd_offset}, {cd_offset + cd_size}) "
            f"does not end at EOCD ({eocd_pos}); ZIP64 locator headers are not allowed"
        )
    if total_entries == 0:
        raise ZipValidationError(f"{archive_path}: archive contains zero entries")

    # A v2+ signed APK stores its signature in an APK Signing Block that sits
    # between the last entry and the Central Directory, so the last entry ends
    # *before* the CD.  Resolve (and validate) that block once, then treat the
    # block as the end of the entry area.
    signing_block = apk_signing_block_range(data, cd_offset, archive_path)
    content_end = signing_block[0] if signing_block else cd_offset

    entries: list[dict[str, int | str | bytes]] = []
    pos = cd_offset
    for idx in range(total_entries):
        if pos + 46 > eocd_pos:
            raise ZipValidationError(f"{archive_path}: truncated CDH at index {idx}")
        (
            cd_sig,
            ver_made,
            ver_need_cd,
            flags_cd,
            method_cd,
            mtime_cd,
            mdate_cd,
            crc_cd,
            csize_cd,
            usize_cd,
            fn_len_cd,
            ex_len_cd,
            cm_len_cd,
            _disk_start,
            int_attr_cd,
            ext_attr_cd,
            lfh_offset,
        ) = struct.unpack_from("<IHHHHHHIIIHHHHHII", data, pos)

        if cd_sig != CDH_SIGNATURE:
            raise ZipValidationError(
                f"{archive_path}: invalid CDH signature {cd_sig:#x} at entry {idx}"
            )
        name_bytes_cd = data[pos + 46 : pos + 46 + fn_len_cd]
        name = _decode_name(name_bytes_cd, archive_path)
        pos += 46 + fn_len_cd + ex_len_cd + cm_len_cd

        if flags_cd & GPB_DATA_DESCRIPTOR_MASK and not allow_data_descriptors:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} sets GPB bit 3 (Data Descriptor) in CDH "
                f"(flags=0x{flags_cd:04x})"
            )
        if forbid_extra_fields and ex_len_cd != 0:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} has unexpected CDH extra field ({ex_len_cd} bytes)"
            )
        if (ver_made & 0xFF) >= 45 or ver_need_cd >= 45:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} uses ZIP64 version headers "
                f"(ver_made=0x{ver_made:04x}, ver_need={ver_need_cd})"
            )

        if lfh_offset + 30 > content_end:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} LFH offset {lfh_offset} out of bounds"
            )
        (
            lfh_sig,
            ver_need_lfh,
            flags_lfh,
            method_lfh,
            mtime_lfh,
            mdate_lfh,
            crc_lfh,
            csize_lfh,
            usize_lfh,
            fn_len_lfh,
            ex_len_lfh,
        ) = struct.unpack_from("<IHHHHHIIIHH", data, lfh_offset)

        if lfh_sig != LFH_SIGNATURE:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} invalid LFH signature {lfh_sig:#x}"
            )
        if flags_lfh & GPB_DATA_DESCRIPTOR_MASK and not allow_data_descriptors:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} sets GPB bit 3 (Data Descriptor) in LFH "
                f"(flags=0x{flags_lfh:04x})"
            )
        if (flags_lfh & GPB_DATA_DESCRIPTOR_MASK) != (flags_cd & GPB_DATA_DESCRIPTOR_MASK):
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} Data Descriptor presence mismatch between "
                f"LFH (0x{flags_lfh:04x}) and CDH (0x{flags_cd:04x})"
            )
        if flags_lfh != flags_cd and not allow_data_descriptors:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} LFH flags 0x{flags_lfh:04x} != "
                f"CDH flags 0x{flags_cd:04x}"
            )
        if forbid_extra_fields and ex_len_lfh != 0:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} has unexpected LFH extra field ({ex_len_lfh} bytes)"
            )
        if ver_need_lfh >= 45:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} requires ZIP64 (ver_need={ver_need_lfh})"
            )

        data_start = lfh_offset + 30 + fn_len_lfh + ex_len_lfh
        name_bytes_lfh = data[lfh_offset + 30 : data_start - ex_len_lfh]
        if name_bytes_lfh != name_bytes_cd:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} LFH filename {name_bytes_lfh!r} != CDH filename"
            )
        # A reader that trusts the Local File Header (ZipInputStream, zipalign) and a
        # reader that trusts the Central Directory (apksig, Java ZipFile) must resolve
        # the same method and the same payload; anything else is the "Malformed ZIP
        # entry"/"invalid stored block lengths" class of bug.
        if method_lfh != method_cd:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} LFH method {method_lfh} != CDH method "
                f"{method_cd} (readers would decompress the same bytes differently)"
            )
        if allow_data_descriptors and flags_lfh & GPB_DATA_DESCRIPTOR_MASK:
            # Streaming writers leave these fields zeroed in the Local File Header and
            # put the real values in the trailing Data Descriptor and the CDH.
            crc_lfh, csize_lfh, usize_lfh = crc_cd, csize_cd, usize_cd
        if (crc_lfh, csize_lfh, usize_lfh) != (crc_cd, csize_cd, usize_cd):
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} LFH (crc={crc_lfh:#x}, csize={csize_lfh}, "
                f"usize={usize_lfh}) != CDH (crc={crc_cd:#x}, csize={csize_cd}, usize={usize_cd})"
            )

        data_end = data_start + csize_cd
        if data_end > content_end:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} data range [{data_start}, {data_end}) "
                f"overlaps the {'APK Signing Block at ' if signing_block else 'Central Directory at '}"
                f"{content_end}"
            )

        # Stream-verify payload directly from the Local File Header offsets
        payload = memoryview(data)[data_start:data_end]
        if method_lfh == zipfile.ZIP_STORED:
            if csize_lfh != usize_lfh:
                raise ZipValidationError(
                    f"{archive_path}: STORED entry {name!r} csize {csize_lfh} != usize {usize_lfh}"
                )
            computed_crc = zlib.crc32(payload) & 0xFFFFFFFF
            actual_usize = len(payload)
        elif method_lfh == zipfile.ZIP_DEFLATED:
            inflater = zlib.decompressobj(-zlib.MAX_WBITS)
            computed_crc = 0
            actual_usize = 0
            try:
                for offset in range(0, len(payload), STREAM_CHUNK_SIZE):
                    out = inflater.decompress(payload[offset : offset + STREAM_CHUNK_SIZE])
                    if out:
                        computed_crc = zlib.crc32(out, computed_crc)
                        actual_usize += len(out)
                tail = inflater.flush()
                if tail:
                    computed_crc = zlib.crc32(tail, computed_crc)
                    actual_usize += len(tail)
            except zlib.error as exc:
                raise ZipValidationError(
                    f"{archive_path}: DEFLATE stream error in {name!r}: {exc}"
                ) from exc
            if not inflater.eof or inflater.unused_data:
                raise ZipValidationError(
                    f"{archive_path}: incomplete or padded DEFLATE stream in {name!r} "
                    f"(eof={inflater.eof}, unused={len(inflater.unused_data)})"
                )
            computed_crc &= 0xFFFFFFFF
        else:
            raise ZipValidationError(
                f"{archive_path}: unsupported compression method {method_lfh} on {name!r}"
            )

        if actual_usize != usize_lfh:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} inflated size {actual_usize} != {usize_lfh}"
            )
        if computed_crc != crc_lfh:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} CRC {computed_crc:#010x} != {crc_lfh:#010x}"
            )

        entries.append(
            {
                "name": name,
                "name_bytes": name_bytes_cd,
                "flags": flags_cd,
                "lfh_offset": lfh_offset,
                "data_start": data_start,
                "data_end": data_end,
                "method": method_lfh,
                "csize": csize_lfh,
                "usize": usize_lfh,
                "crc": crc_lfh,
                "mtime": mtime_cd,
                "mdate": mdate_cd,
                "ext_attr": ext_attr_cd,
                "int_attr": int_attr_cd,
            }
        )

    if check_contiguous:
        # Confirm entries are contiguous up to the end of the entry area (no hidden
        # trailing Data Descriptors or gaps); for a signed APK that area ends where
        # the APK Signing Block starts.
        entries_sorted = sorted(entries, key=lambda item: int(item["lfh_offset"]))
        if int(entries_sorted[0]["lfh_offset"]) != 0:
            raise ZipValidationError(
                f"{archive_path}: first LFH does not start at offset 0 ({entries_sorted[0]['lfh_offset']})"
            )
        for cur, nxt in zip(entries_sorted, entries_sorted[1:]):
            if int(cur["data_end"]) != int(nxt["lfh_offset"]):
                raise ZipValidationError(
                    f"{archive_path}: gap/Data Descriptor of "
                    f"{int(nxt['lfh_offset']) - int(cur['data_end'])} bytes after {cur['name']!r}"
                )
        last_entry_end = int(entries_sorted[-1]["data_end"])
        if signing_block:
            # apksig pads the area in front of the APK Signing Block (it aligns the
            # block, and those pad bytes are covered by the v2/v3 digest). Any other
            # writer that pads there is equally fine for the platform, so the region
            # is accepted as long as it does not overlap an entry.
            if last_entry_end > content_end:
                raise ZipValidationError(
                    f"{archive_path}: last entry {entries_sorted[-1]['name']!r} overlaps the "
                    f"APK Signing Block at {content_end}"
                )
        elif last_entry_end != content_end:
            raise ZipValidationError(
                f"{archive_path}: gap/Data Descriptor of "
                f"{content_end - last_entry_end} bytes before the Central Directory"
            )

    try:
        with zipfile.ZipFile(archive_path, "r") as zf:
            bad_member = zf.testzip()
    except (RuntimeError, NotImplementedError, zipfile.BadZipFile, zlib.error) as exc:
        raise ZipValidationError(
            f"{archive_path}: zipfile could not read the archive: {exc}"
        ) from exc
    if bad_member is not None:
        raise ZipValidationError(f"{archive_path}: zipfile.testzip() failed on {bad_member!r}")

    return entries


def verify_entry_metadata(archive_path: Path, entries: list[dict[str, int | str | bytes]]) -> None:
    """Reject per-entry metadata that apksig or the platform would refuse or misread."""
    seen: set[str] = set()
    for entry in entries:
        name = str(entry["name"])
        flags = int(entry["flags"])
        if name in seen:
            raise ZipValidationError(f"{archive_path}: duplicate ZIP entry {name!r}")
        seen.add(name)
        if flags & GPB_ENCRYPTED_MASK or flags & GPB_STRONG_ENCRYPTION_MASK:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} is marked encrypted (flags=0x{flags:04x})"
            )
        if flags & ~SUPPORTED_GPB_FLAGS:
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} uses unsupported GPB flags 0x{flags:04x}"
            )
        if (
            not name
            or name.startswith("/")
            or "\\" in name
            or any(part in ("..", ".") for part in name.split("/"))
            or not set(bytes(entry["name_bytes"])) <= SAFE_NAME_BYTES
        ):
            raise ZipValidationError(f"{archive_path}: unsafe entry name {name!r}")
        if int(entry["method"]) not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise ZipValidationError(
                f"{archive_path}: entry {name!r} uses unsupported compression method "
                f"{entry['method']}"
            )


def verify_entry_streams(archive_path: Path, entries: list[dict[str, int | str | bytes]]) -> None:
    """Decode every payload exactly as apksig's inflater would.

    ``com.android.apksig.internal.zip.LocalFileRecord.getUncompressedData`` takes the
    method and the compressed size from the Central Directory and inflates that range.
    Reproducing it here turns "W: Malformed ZIP entry" plus
    "W: invalid stored block lengths" into a build failure with the offending entry.
    """
    data = archive_path.read_bytes()
    for entry in entries:
        name = str(entry["name"])
        method = int(entry["method"])
        payload = data[int(entry["data_start"]) : int(entry["data_end"])]
        label = f"{archive_path}: entry {name!r}"
        if len(payload) != int(entry["csize"]):
            raise ZipValidationError(
                f"{label} has {len(payload)} bytes but the Central Directory declares "
                f"{entry['csize']} compressed bytes"
            )
        if method == zipfile.ZIP_DEFLATED:
            inflate_exact(
                payload,
                expected_usize=int(entry["usize"]),
                expected_crc=int(entry["crc"]),
                label=label,
            )
        else:
            if len(payload) != int(entry["usize"]):
                raise ZipValidationError(
                    f"{label} is STORED with {len(payload)} bytes but declares "
                    f"{entry['usize']} uncompressed bytes"
                )
            actual_crc = zlib.crc32(payload) & 0xFFFFFFFF
            if actual_crc != int(entry["crc"]):
                raise ZipValidationError(
                    f"{label} STORED CRC {actual_crc:#010x} != declared {int(entry['crc']):#010x}"
                )


def verify_apk_entries(
    apk_path: Path,
    entries: list[dict[str, int | str | bytes]] | None = None,
    *,
    require_alignment: bool = True,
) -> list[dict[str, int | str | bytes]]:
    """Full apksig-equivalent and Android-runtime verification of an APK."""
    if entries is None:
        entries = parse_and_validate_zip_headers(
            apk_path,
            forbid_extra_fields=False,
            check_contiguous=True,
        )
    verify_entry_metadata(apk_path, entries)
    verify_entry_streams(apk_path, entries)
    validate_apk_store_and_alignment(apk_path, entries, require_alignment=require_alignment)
    return entries


def canonicalize_apk(apk_path: Path, entries: list[dict[str, int | str | bytes]]) -> None:
    """Rewrite an APK so every ZIP record is canonical and self-consistent.

    The rewrite keeps entry order and payload bytes, but rebuilds the Local File
    Headers and the Central Directory from a single source of truth:

    * identical flags/method/CRC-32/sizes in both headers,
    * no Data Descriptors and no ZIP64,
    * ``resources.arsc`` and ``assets/sounds/*.ogg`` stored so ``openFd`` works,
    * STORED payloads aligned with a well-formed 0xD935 alignment extra field.
    """
    source = apk_path.read_bytes()
    if apk_signing_block_range(source, _central_directory_offset(source, apk_path), apk_path):
        raise ZipValidationError(
            f"{apk_path}: refusing to rewrite a signed APK. Rebuilding the ZIP drops the "
            f"APK Signing Block and every v1 signature file, so the result is an unsigned "
            f"package that Android rejects ('The package appears to be invalid'). Sign the "
            f"canonicalized APK instead, after this step."
        )
    ordered = sorted(entries, key=lambda item: int(item["lfh_offset"]))

    out = bytearray()
    cd_records: list[dict[str, int | str | bytes]] = []
    stored_count = 0
    deflated_count = 0

    for entry in ordered:
        name = str(entry["name"])
        name_bytes = bytes(entry["name_bytes"])
        method_in = int(entry["method"])
        payload = source[int(entry["data_start"]) : int(entry["data_end"])]
        if method_in == zipfile.ZIP_DEFLATED:
            raw = inflate_exact(
                payload,
                expected_usize=int(entry["usize"]),
                expected_crc=int(entry["crc"]),
                label=f"{apk_path}: entry {name!r}",
            )
        else:
            raw = payload

        crc = zlib.crc32(raw) & 0xFFFFFFFF
        usize = len(raw)

        method = method_in
        if must_store(name) or name.endswith("/"):
            method = zipfile.ZIP_STORED
        body = raw if method == zipfile.ZIP_STORED else deflate_raw(raw)
        if method == zipfile.ZIP_STORED:
            stored_count += 1
        else:
            deflated_count += 1

        flags = GPB_EFS_MASK if any(byte > 0x7F for byte in name_bytes) else 0
        lfh_offset = len(out)
        extra = b""
        if method == zipfile.ZIP_STORED and body:
            extra = alignment_extra_field(alignment_for(name), lfh_offset + 30 + len(name_bytes))

        mdate = int(entry["mdate"]) or DEFAULT_DATE
        mtime = int(entry["mtime"]) if int(entry["mdate"]) else 0

        out += struct.pack(
            "<IHHHHHIIIHH",
            LFH_SIGNATURE,
            max(ZIP_VERSION, 10),
            flags,
            method,
            mtime,
            mdate,
            crc,
            len(body),
            usize,
            len(name_bytes),
            len(extra),
        )
        out += name_bytes
        out += extra
        out += body

        cd_records.append(
            {
                "name_bytes": name_bytes,
                "extra": extra,
                "flags": flags,
                "method": method,
                "mtime": mtime,
                "mdate": mdate,
                "crc": crc,
                "csize": len(body),
                "usize": usize,
                "lfh_offset": lfh_offset,
                "ext_attr": int(entry["ext_attr"]),
                "int_attr": int(entry["int_attr"]),
            }
        )

    cd_offset = len(out)
    for record in cd_records:
        out += struct.pack(
            "<IHHHHHHIIIHHHHHII",
            CDH_SIGNATURE,
            ZIP_VERSION,
            ZIP_VERSION,
            int(record["flags"]),
            int(record["method"]),
            int(record["mtime"]),
            int(record["mdate"]),
            int(record["crc"]),
            int(record["csize"]),
            int(record["usize"]),
            len(bytes(record["name_bytes"])),
            0,  # Central Directory extra field length: 0 (alignment extra fields belong exclusively in LFH)
            0,  # file comment length
            0,  # disk number start
            int(record["int_attr"]),
            int(record["ext_attr"]),
            int(record["lfh_offset"]),
        )
        out += bytes(record["name_bytes"])
    cd_size = len(out) - cd_offset

    out += struct.pack(
        "<IHHHHIIH",
        EOCD_SIGNATURE,
        0,
        0,
        len(cd_records),
        len(cd_records),
        cd_size,
        cd_offset,
        0,
    )

    temp_path = apk_path.with_name(f".{apk_path.name}.canonical")
    try:
        temp_path.write_bytes(bytes(out))
        os.replace(temp_path, apk_path)
    finally:
        if temp_path.exists():
            temp_path.unlink()

    print(
        f"Canonicalized APK {apk_path.name}: {len(cd_records)} entries "
        f"({stored_count} STORED, {deflated_count} DEFLATED), 0 Data Descriptors"
    )


def validate_apk_store_and_alignment(
    apk_path: Path,
    entries: list[dict[str, int | str | bytes]],
    *,
    require_alignment: bool,
) -> None:
    """Confirm resources.arsc and sound assets stay uncompressed (and 4-byte aligned)."""
    for entry in entries:
        name = str(entry["name"])
        method = int(entry["method"])
        data_start = int(entry["data_start"])
        if must_store(name):
            if method != zipfile.ZIP_STORED:
                raise ZipValidationError(
                    f"{apk_path}: {name!r} must be ZIP_STORED (0), found method={method} "
                    f"(AssetManager.openFd() cannot map compressed assets)"
                )
        if require_alignment and method == zipfile.ZIP_STORED and int(entry["usize"]) > 0:
            align = alignment_for(name)
            if data_start % align != 0:
                raise ZipValidationError(
                    f"{apk_path}: STORED entry {name!r} at offset {data_start} is not "
                    f"{align}-byte aligned"
                )


def normalize_apk(apk_path: Path, zipalign_bin: str | None) -> None:
    """Rewrite the APK into canonical form, then verify it like apksig and Android do."""
    entries = parse_and_validate_zip_headers(
        apk_path,
        forbid_extra_fields=False,
        check_contiguous=False,
        allow_data_descriptors=True,
    )
    canonicalize_apk(apk_path, entries)

    entries = parse_and_validate_zip_headers(
        apk_path,
        forbid_extra_fields=False,
        check_contiguous=True,
    )
    verify_apk_entries(apk_path, entries, require_alignment=True)

    if zipalign_bin:
        # The APK is already aligned by the canonical writer; let the Android SDK's own
        # tool confirm that instead of rewriting the file a second time.
        subprocess.run([zipalign_bin, "-c", "4", str(apk_path)], check=True)

    print(f"Verified APK {apk_path.name}: {len(entries)} entries, 0 Data Descriptors")


def build_release_zip(
    apk_paths: list[Path],
    output_zip: Path,
    zipalign_bin: str | None,
    *,
    normalize: bool = True,
) -> None:
    """Package APKs into the release ZIP.

    ``normalize=True`` canonicalizes unsigned inputs first (``build``).  The CI
    uses ``normalize=False`` (``package``) once the APKs have already been
    canonicalized and signed, so nothing downstream can rewrite a signed package.
    """
    if not apk_paths:
        raise ZipValidationError("No APK files provided to package")

    zip_bin = shutil.which("zip")
    if not zip_bin:
        raise ZipValidationError("'zip' CLI utility is required to build standard seekable archives")

    for apk in apk_paths:
        if apk_has_signing_block(apk):
            # Signed APKs are verified but never rewritten: rebuilding the ZIP would
            # strip the APK Signing Block and turn an installable package into
            # "The package appears to be invalid".
            verify_apk_entries(apk, require_alignment=True)
            print(f"Verified signed APK {apk.name}: signature block preserved, not rewritten")
            continue
        if not normalize:
            verify_apk_entries(apk, require_alignment=True)
            print(f"Verified APK {apk.name}: packaged as-is")
            continue
        normalize_apk(apk, zipalign_bin)

    if output_zip.exists():
        output_zip.unlink()
    output_zip.parent.mkdir(parents=True, exist_ok=True)

    # -j: store flat basenames without directory prefixes
    # -X: strip extra OS file attributes so LFH and CDH extra_field_length == 0
    # -0: store APK bytes verbatim, without an outer DEFLATE stream
    cmd = [zip_bin, "-j", "-X", "-0", str(output_zip), *[str(p) for p in apk_paths]]
    subprocess.run(cmd, check=True)

    verify_release_zip(output_zip, expected_apks=apk_paths)


def verify_release_zip(zip_path: Path, expected_apks: list[Path]) -> None:
    if not zip_path.is_file():
        raise ZipValidationError(f"Release ZIP not found: {zip_path}")

    entries = parse_and_validate_zip_headers(zip_path, forbid_extra_fields=True)
    verify_entry_metadata(zip_path, entries)
    verify_entry_streams(zip_path, entries)

    expected_by_name = {apk.name: sha256_file(apk) for apk in expected_apks}
    actual_names = {str(entry["name"]) for entry in entries}

    if expected_by_name and actual_names != set(expected_by_name.keys()):
        raise ZipValidationError(
            f"{zip_path}: archive members {sorted(actual_names)} != expected {sorted(expected_by_name)}"
        )

    with tempfile.TemporaryDirectory(prefix="verify-release-zip-") as tmp_dir:
        extract_dir = Path(tmp_dir)
        with zipfile.ZipFile(zip_path, "r") as zf:
            zf.extractall(extract_dir)

        for name in sorted(actual_names):
            extracted_apk = extract_dir / name
            if not extracted_apk.is_file():
                raise ZipValidationError(f"{zip_path}: extracted member missing: {name}")
            actual_sha = sha256_file(extracted_apk)
            if name in expected_by_name and actual_sha != expected_by_name[name]:
                raise ZipValidationError(
                    f"{zip_path}: SHA-256 mismatch for {name}: "
                    f"{actual_sha} != {expected_by_name[name]}"
                )
            inner_entries = verify_apk_entries(extracted_apk, require_alignment=True)
            print(
                f"Verified extracted {name} ({extracted_apk.stat().st_size} bytes, "
                f"sha256={actual_sha[:16]}..., {len(inner_entries)} inner entries)"
            )

    print(
        f"Release ZIP {zip_path.name} passed all header, LFH/CDH, and streaming DEFLATE checks "
        f"({zip_path.stat().st_size} bytes)."
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build_parser = subparsers.add_parser("build", help="Normalize APKs and build release ZIP")
    build_parser.add_argument("--output", type=Path, required=True, help="Output .zip file path")
    build_parser.add_argument("--zipalign", type=str, default=None, help="Optional zipalign binary")
    build_parser.add_argument("apks", nargs="+", type=Path, help="Input .apk files")

    verify_parser = subparsers.add_parser("verify", help="Verify release ZIP and inner APKs")
    verify_parser.add_argument("zip_path", type=Path, help="Path to .zip archive to verify")
    verify_parser.add_argument(
        "--expected-apk",
        dest="expected_apks",
        action="append",
        type=Path,
        default=[],
        help="Expected original .apk file (may be specified multiple times)",
    )

    package_parser = subparsers.add_parser(
        "package",
        help="Verify APKs as-is (no rewriting) and package them into the release ZIP",
    )
    package_parser.add_argument("--output", type=Path, required=True, help="Output .zip file path")
    package_parser.add_argument("apks", nargs="+", type=Path, help="Input .apk files")

    normalize_parser = subparsers.add_parser(
        "normalize",
        help="Canonicalize an unsigned APK in place without packaging it",
    )
    normalize_parser.add_argument("--zipalign", type=str, default=None, help="Optional zipalign binary")
    normalize_parser.add_argument("apks", nargs="+", type=Path, help="Input .apk files")

    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            build_release_zip(args.apks, args.output, args.zipalign)
        elif args.command == "verify":
            verify_release_zip(args.zip_path, args.expected_apks)
        elif args.command == "package":
            build_release_zip(args.apks, args.output, None, normalize=False)
        elif args.command == "normalize":
            for apk in args.apks:
                normalize_apk(apk, args.zipalign)
    except (ZipValidationError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
