import importlib.util
import io
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

SCRIPT = Path(__file__).resolve().parents[1] / "prepare_release_archive.py"
spec = importlib.util.spec_from_file_location("release_archive", SCRIPT)
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)

MP3_NAME = "assets/sounds/medkit.ogg"
# Bytes shaped like a real sound asset: an ID3v2.4 header followed by MPEG audio frames.
# "ID3\x04" starts with 0x49, which a DEFLATE inflater reads as a stored block whose
# LEN/NLEN pair is invalid -- the "invalid stored block lengths" seen in the field.
MP3_BYTES = b"ID3\x04\x00\x00\x00\x00\x007TPE1\x00\x00" + bytes(range(256)) * 40
ARSC_BYTES = os.urandom(2048)


def deflate(data):
    compressor = zlib.compressobj(6, zlib.DEFLATED, -zlib.MAX_WBITS)
    return compressor.compress(data) + compressor.flush()


def write_raw_zip(path, entries):
    """Write a ZIP whose Local File Header and Central Directory fields can disagree."""
    out = bytearray()
    records = []
    for entry in entries:
        name_bytes = entry["name"].encode("utf-8")
        raw = entry.get("raw", b"")
        body = entry["body"]
        fields = {
            "flags": entry.get("flags", 0),
            "method": entry.get("method", 0),
            "crc": zlib.crc32(raw) & 0xFFFFFFFF,
            "csize": len(body),
            "usize": len(raw),
        }
        local = {**fields, **entry.get("lfh", {})}
        central = {**fields, **entry.get("cd", {})}
        lfh_offset = len(out)
        out += struct.pack(
            "<IHHHHHIIIHH",
            archive.LFH_SIGNATURE,
            20,
            local["flags"],
            local["method"],
            0,
            0x0021,
            local["crc"],
            local["csize"],
            local["usize"],
            len(name_bytes),
            0,
        )
        out += name_bytes + body
        records.append((name_bytes, central, lfh_offset))

    cd_offset = len(out)
    for name_bytes, central, lfh_offset in records:
        out += struct.pack(
            "<IHHHHHHIIIHHHHHII",
            archive.CDH_SIGNATURE,
            20,
            20,
            central["flags"],
            central["method"],
            0,
            0x0021,
            central["crc"],
            central["csize"],
            central["usize"],
            len(name_bytes),
            0,
            0,
            0,
            0,
            0o100644 << 16,
            lfh_offset,
        )
        out += name_bytes
    cd_size = len(out) - cd_offset
    out += struct.pack(
        "<IHHHHIIH",
        archive.EOCD_SIGNATURE,
        0,
        0,
        len(records),
        len(records),
        cd_size,
        cd_offset,
        0,
    )
    path.write_bytes(bytes(out))


# A v2+ signature lives in an APK Signing Block between the last entry and the
# Central Directory. These tests build that layout by hand so the packaging code
# is exercised on signed APKs too (the shape produced by apksigner).
SIG_PAIR_ID = 0x7109871A  # APK Signature Scheme v2 block ID


PADDING_PAIR_ID = 0x42726577  # "werB": what apksig writes to align the block


def add_signing_block(
    apk_path,
    *,
    pair_value=b"\x00" * 32,
    corrupt_sizes=False,
    broken_pair=False,
    leading_padding=b"",
):
    data = bytearray(apk_path.read_bytes())
    eocd_pos = data.rfind(b"PK\x05\x06")
    cd_offset = struct.unpack_from("<I", data, eocd_pos + 16)[0]
    pair = struct.pack("<QI", 4 + len(pair_value), SIG_PAIR_ID) + pair_value
    # apksigner pads the block with a second pair whose value is arbitrary bytes;
    # real apksig output is not zero-filled, so it must not be rejected.
    padding_value = b"\x00\xff\x7fopen-fire"
    pair += struct.pack("<QI", 4 + len(padding_value), PADDING_PAIR_ID) + padding_value
    if broken_pair:
        pair = struct.pack("<QI", 4096, SIG_PAIR_ID)
    size = archive.SIG_BLOCK_MAGIC_SIZE + archive.SIG_BLOCK_SIZE_FIELD + len(pair)
    trailing = size + 8 if corrupt_sizes else size
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", trailing) + archive.SIG_BLOCK_MAGIC
    # apksig pads the area in front of the block (it aligns it); the platform
    # ignores those bytes, so packaging must accept them.
    block = leading_padding + block
    data[cd_offset:cd_offset] = block
    new_eocd = eocd_pos + len(block)
    struct.pack_into("<I", data, new_eocd + 16, cd_offset + len(block))
    apk_path.write_bytes(bytes(data))
    return bytes(data)


def signed_apk(path):
    """An APK layout like apksigner produces: v1 files, entries, signing block, CD."""
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("AndroidManifest.xml", b"manifest" * 50, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("resources.arsc", ARSC_BYTES, compress_type=zipfile.ZIP_STORED)
        zf.writestr(MP3_NAME, MP3_BYTES, compress_type=zipfile.ZIP_STORED)
        zf.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n", compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("META-INF/OPENFIRCI.SF", b"Signature-Version: 1.0\r\n\r\n", compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("META-INF/OPENFIRCI.RSA", b"\x30\x82" + b"\x00" * 200, compress_type=zipfile.ZIP_DEFLATED)
    # Entry data must be 4-byte aligned for the alignment check, which is what
    # normalize_apk() would normally arrange. Canonicalize first, then sign.
    entries = archive.parse_and_validate_zip_headers(path)
    archive.canonicalize_apk(path, entries)
    return add_signing_block(path)


class SignedApkTests(unittest.TestCase):
    """A signed APK must be validated and packaged verbatim, never rewritten."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "open-fire-release.apk"
        self.signed_bytes = signed_apk(self.apk)

    def test_padding_before_the_signing_block_is_accepted(self):
        """apksig aligns the block and pads the gap in front of it."""
        apk = self.root / "padded.apk"
        signed_apk(apk)
        data = bytearray(apk.read_bytes())
        eocd_pos = data.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack_from("<I", data, eocd_pos + 16)[0]
        trailing_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
        start = cd_offset - 8 - trailing_size
        padded = data[:start] + b"\x00" * 850 + data[start:]
        struct.pack_into("<I", padded, eocd_pos + 850 + 16, cd_offset + 850)
        apk.write_bytes(bytes(padded))
        archive.verify_apk_entries(apk)

    def test_signing_block_is_detected_and_validated(self):
        self.assertTrue(archive.apk_has_signing_block(self.apk))
        entries = archive.verify_apk_entries(self.apk)
        self.assertIn("META-INF/MANIFEST.MF", {str(entry["name"]) for entry in entries})

    def test_signed_apk_is_not_rewritten_by_build(self):
        output = self.root / "release.zip"
        archive.build_release_zip([self.apk], output, None)
        self.assertEqual(self.apk.read_bytes(), self.signed_bytes)
        with zipfile.ZipFile(output) as zf:
            self.assertEqual(zf.read(self.apk.name), self.signed_bytes)

    def test_canonicalizer_refuses_to_strip_a_signature(self):
        entries = archive.parse_and_validate_zip_headers(self.apk)
        with self.assertRaises(archive.ZipValidationError) as raised:
            archive.canonicalize_apk(self.apk, entries)
        self.assertIn("signed APK", str(raised.exception))
        self.assertEqual(self.apk.read_bytes(), self.signed_bytes)

    def test_mismatched_signing_block_sizes_are_rejected(self):
        apk = self.root / "broken.apk"
        signed_apk(apk)
        data = bytearray(apk.read_bytes())
        eocd_pos = data.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack_from("<I", data, eocd_pos + 16)[0]
        struct.pack_into("<Q", data, cd_offset - 24, 12345)
        apk.write_bytes(bytes(data))
        with self.assertRaises(archive.ZipValidationError) as raised:
            archive.parse_and_validate_zip_headers(apk)
        self.assertIn("signing block", str(raised.exception).lower())

    def test_non_zero_padding_pair_is_accepted(self):
        """apksig's alignment padding pair carries opaque, non-zero bytes."""
        self.assertTrue(archive.apk_has_signing_block(self.apk))
        archive.verify_apk_entries(self.apk)

    def test_pairs_that_do_not_fill_the_block_are_rejected(self):
        apk = self.root / "overrun.apk"
        signed_apk(apk)
        data = bytearray(apk.read_bytes())
        eocd_pos = data.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack_from("<I", data, eocd_pos + 16)[0]
        trailing_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
        start = cd_offset - 8 - trailing_size
        # Shrink the first pair's declared length: the pair walk can no longer end
        # exactly at the end of the block, which is a malformed signing block.
        struct.pack_into("<Q", data, start + 8, 32)
        apk.write_bytes(bytes(data))
        with self.assertRaises(archive.ZipValidationError) as raised:
            archive.parse_and_validate_zip_headers(apk)
        self.assertIn("signing block", str(raised.exception).lower())

    def test_broken_pair_inside_signing_block_is_rejected(self):
        apk = self.root / "broken-pair.apk"
        signed_apk(apk)
        data = bytearray(apk.read_bytes())
        eocd_pos = data.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack_from("<I", data, eocd_pos + 16)[0]
        trailing_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
        start = cd_offset - 8 - trailing_size
        struct.pack_into("<Q", data, start + 8, 4096)  # pair length overflowing the block
        apk.write_bytes(bytes(data))
        with self.assertRaises(archive.ZipValidationError) as raised:
            archive.parse_and_validate_zip_headers(apk)
        self.assertIn("pair", str(raised.exception))

    def test_unsigned_apk_reports_no_signing_block(self):
        apk = self.root / "unsigned.apk"
        with zipfile.ZipFile(apk, "w") as zf:
            zf.writestr("AndroidManifest.xml", b"manifest" * 10)
        self.assertFalse(archive.apk_has_signing_block(apk))


class ReleaseArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "open-fire-unsigned.apk"
        with zipfile.ZipFile(self.apk, "w") as apk:
            apk.writestr("AndroidManifest.xml", b"manifest" * 100, compress_type=zipfile.ZIP_DEFLATED)
            apk.writestr("resources.arsc", os.urandom(1024), compress_type=zipfile.ZIP_STORED)
            apk.writestr("assets/sounds/example.ogg", os.urandom(10000), compress_type=zipfile.ZIP_STORED)
        self.output = self.root / "release.zip"

    def build(self):
        archive.build_release_zip([self.apk], self.output, None)

    def test_archive_stores_exact_apk_and_extracts_with_unzip(self):
        self.build()
        with zipfile.ZipFile(self.output) as zf:
            self.assertEqual(zf.namelist(), [self.apk.name])
            self.assertEqual(zf.infolist()[0].compress_type, zipfile.ZIP_STORED)
            self.assertEqual(zf.read(self.apk.name), self.apk.read_bytes())
        subprocess.run(["unzip", "-tq", str(self.output)], check=True)
        archive.verify_release_zip(self.output, [self.apk])

    def test_corruption_is_rejected(self):
        self.build()
        entries = archive.parse_and_validate_zip_headers(self.output)
        data = bytearray(self.output.read_bytes())
        data[int(entries[0]["data_start"]) + 50] ^= 1
        self.output.write_bytes(data)
        with self.assertRaises(archive.ZipValidationError):
            archive.verify_release_zip(self.output, [self.apk])

    def test_truncated_archive_is_rejected(self):
        self.build()
        self.output.write_bytes(self.output.read_bytes()[:-10])
        with self.assertRaises(archive.ZipValidationError):
            archive.verify_release_zip(self.output, [self.apk])


class ApkCanonicalizationTests(unittest.TestCase):
    """Guards against "Malformed ZIP entry" / "invalid stored block lengths"."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def apk_path(self, name="open-fire-unsigned.apk"):
        return self.root / name

    def test_sound_assets_are_stored_and_aligned(self):
        apk = self.apk_path()
        with zipfile.ZipFile(apk, "w") as zf:
            zf.writestr("AndroidManifest.xml", b"manifest" * 50, compress_type=zipfile.ZIP_DEFLATED)
            zf.writestr("resources.arsc", ARSC_BYTES, compress_type=zipfile.ZIP_STORED)
            # AGP/aapt2 could compress an asset; openFd() would then fail on the device.
            zf.writestr(MP3_NAME, MP3_BYTES, compress_type=zipfile.ZIP_DEFLATED)

        archive.normalize_apk(apk, None)
        entries = archive.parse_and_validate_zip_headers(apk)
        by_name = {str(entry["name"]): entry for entry in entries}

        self.assertEqual(by_name[MP3_NAME]["method"], zipfile.ZIP_STORED)
        self.assertEqual(by_name["resources.arsc"]["method"], zipfile.ZIP_STORED)
        self.assertEqual(int(by_name[MP3_NAME]["data_start"]) % 4, 0)
        with zipfile.ZipFile(apk) as zf:
            self.assertEqual(zf.read(MP3_NAME), MP3_BYTES)
        subprocess.run(["unzip", "-tq", str(apk)], check=True)

    def test_native_libraries_are_stored_and_16kb_aligned(self):
        apk = self.apk_path("open-fire-native.apk")
        so_name = "lib/arm64-v8a/libtest.so"
        so_bytes = b"\x7fELF" + os.urandom(1024)
        with zipfile.ZipFile(apk, "w") as zf:
            zf.writestr("AndroidManifest.xml", b"manifest" * 50, compress_type=zipfile.ZIP_DEFLATED)
            zf.writestr("resources.arsc", ARSC_BYTES, compress_type=zipfile.ZIP_STORED)
            zf.writestr(so_name, so_bytes, compress_type=zipfile.ZIP_DEFLATED)

        archive.normalize_apk(apk, None)
        entries = archive.parse_and_validate_zip_headers(apk)
        by_name = {str(entry["name"]): entry for entry in entries}

        self.assertEqual(by_name[so_name]["method"], zipfile.ZIP_STORED)
        self.assertEqual(int(by_name[so_name]["data_start"]) % 16384, 0)
        with zipfile.ZipFile(apk) as zf:
            self.assertEqual(zf.read(so_name), so_bytes)
        subprocess.run(["unzip", "-tq", str(apk)], check=True)

    def test_data_descriptor_apk_is_normalized(self):
        apk = self.apk_path()
        sink = io.BytesIO()

        class Unseekable(io.RawIOBase):
            def writable(self):
                return True

            def write(self, chunk):
                sink.write(chunk)
                return len(chunk)

        with zipfile.ZipFile(Unseekable(), "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("AndroidManifest.xml", b"manifest" * 50)
            zf.writestr(MP3_NAME, MP3_BYTES)
        apk.write_bytes(sink.getvalue())

        with zipfile.ZipFile(apk) as zf:
            self.assertTrue(any(info.flag_bits & 0x0008 for info in zf.infolist()))
        with self.assertRaises(archive.ZipValidationError):
            archive.parse_and_validate_zip_headers(apk)

        archive.normalize_apk(apk, None)
        entries = archive.parse_and_validate_zip_headers(apk)
        for entry in entries:
            self.assertEqual(int(entry["flags"]) & 0x0008, 0)
        archive.verify_apk_entries(apk)

    def test_declared_deflate_with_stored_sound_payload_is_rejected(self):
        """The reported failure: inflating raw MP3 bytes yields 'invalid stored block lengths'."""
        apk = self.apk_path()
        write_raw_zip(
            apk,
            [
                {
                    "name": MP3_NAME,
                    "body": MP3_BYTES,
                    "raw": MP3_BYTES,
                    "method": zipfile.ZIP_DEFLATED,
                }
            ],
        )
        with self.assertRaises(archive.ZipValidationError) as raised:
            archive.verify_apk_entries(apk)
        self.assertIn(MP3_NAME, str(raised.exception))
        with self.assertRaises(archive.ZipValidationError):
            archive.build_release_zip([apk], self.root / "release.zip", None)

    def test_method_mismatch_between_headers_is_rejected(self):
        apk = self.apk_path()
        write_raw_zip(
            apk,
            [
                {
                    "name": MP3_NAME,
                    "body": MP3_BYTES,
                    "raw": MP3_BYTES,
                    "lfh": {"method": zipfile.ZIP_STORED},
                    "cd": {"method": zipfile.ZIP_DEFLATED},
                }
            ],
        )
        with self.assertRaises(archive.ZipValidationError) as raised:
            archive.parse_and_validate_zip_headers(apk)
        self.assertIn("LFH method", str(raised.exception))

    def test_truncated_deflate_stream_is_rejected(self):
        apk = self.apk_path()
        raw = b"manifest" * 50
        body = deflate(raw)[:-6]
        write_raw_zip(
            apk,
            [
                {
                    "name": "AndroidManifest.xml",
                    "body": body,
                    "raw": raw,
                    "method": zipfile.ZIP_DEFLATED,
                }
            ],
        )
        with self.assertRaises(archive.ZipValidationError):
            archive.verify_apk_entries(apk)

    def test_stored_blocks_inside_deflate_are_accepted(self):
        """Incompressible data makes zlib emit stored blocks; that is valid DEFLATE."""
        apk = self.apk_path()
        raw = os.urandom(40000)
        write_raw_zip(
            apk,
            [
                {
                    "name": "classes.dex",
                    "body": deflate(raw),
                    "raw": raw,
                    "method": zipfile.ZIP_DEFLATED,
                }
            ],
        )
        entries = archive.verify_apk_entries(apk)
        self.assertEqual(len(entries), 1)

    def test_encrypted_entries_are_rejected(self):
        apk = self.apk_path()
        write_raw_zip(
            apk,
            [
                {
                    "name": "classes.dex",
                    "body": b"payload",
                    "raw": b"payload",
                    "flags": 0x0001,
                }
            ],
        )
        with self.assertRaises(archive.ZipValidationError) as raised:
            archive.verify_apk_entries(apk)
        self.assertIn("encrypted", str(raised.exception))

    def test_normalize_command_is_available(self):
        apk = self.apk_path()
        with zipfile.ZipFile(apk, "w") as zf:
            zf.writestr(MP3_NAME, MP3_BYTES, compress_type=zipfile.ZIP_DEFLATED)
        self.assertEqual(archive.main(["normalize", str(apk)]), 0)
        entries = archive.parse_and_validate_zip_headers(apk)
        self.assertEqual(int(entries[0]["method"]), zipfile.ZIP_STORED)


if __name__ == "__main__":
    unittest.main()
