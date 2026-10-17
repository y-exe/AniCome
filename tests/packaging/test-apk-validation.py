import importlib.util
import io
import struct
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location("check_apk", Path(__file__).resolve().parents[2] / "scripts/checks/check-apk.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def signing_block(*identifiers):
    entries = b"".join(struct.pack("<QI", 7, identifier) + b"abc" for identifier in identifiers)
    size = len(entries) + 24
    return struct.pack("<Q", size) + entries + struct.pack("<Q", size) + b"APK Sig Block 42"


class SigningBlockTest(unittest.TestCase):
    def test_unsigned_archive(self):
        self.assertEqual([], validator.check_signing_block(io.BytesIO(b"x" * 80), 80))

    def test_signature_and_alignment_padding(self):
        data = b"zip-data" + signing_block(0x7109871A, 0x42726577)
        self.assertEqual(["0x7109871a", "0x42726577"], validator.check_signing_block(io.BytesIO(data), len(data)))

    def test_dependency_metadata_is_rejected(self):
        data = signing_block(0x7109871A, 0x504B4453)
        with self.assertRaisesRegex(AssertionError, "Dependency metadata"):
            validator.check_signing_block(io.BytesIO(data), len(data))

    def test_inconsistent_size_is_rejected(self):
        data = bytearray(signing_block(0x7109871A))
        data[0] ^= 1
        with self.assertRaisesRegex(AssertionError, "size mismatch"):
            validator.check_signing_block(io.BytesIO(data), len(data))

    def test_entry_cannot_extend_beyond_block(self):
        data = bytearray(signing_block(0x7109871A))
        struct.pack_into("<Q", data, 8, 9999)
        with self.assertRaisesRegex(AssertionError, "entry size"):
            validator.check_signing_block(io.BytesIO(data), len(data))


class NativeLibraryTest(unittest.TestCase):
    def library(self, bits, alignment):
        data = bytearray(128)
        data[:6] = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1])
        if bits == 64:
            struct.pack_into("<Q", data, 32, 64)
            struct.pack_into("<HH", data, 54, 56, 1)
            struct.pack_into("<Q", data, 112, alignment)
        else:
            struct.pack_into("<I", data, 28, 64)
            struct.pack_into("<HH", data, 42, 32, 1)
            struct.pack_into("<I", data, 92, alignment)
        struct.pack_into("<I", data, 64, 1)
        return bytes(data)

    def test_32bit_library_uses_its_native_header_and_alignment(self):
        self.assertEqual((32, [4096], 4096), validator.check_elf(self.library(32, 4096), "lib/x86/test.so"))

    def test_64bit_library_requires_16kb_alignment(self):
        self.assertEqual((64, [16384], 16384), validator.check_elf(self.library(64, 16384), "lib/arm64-v8a/test.so"))
        with self.assertRaises(AssertionError):
            validator.check_elf(self.library(64, 4096), "lib/arm64-v8a/test.so")


if __name__ == "__main__":
    unittest.main()
