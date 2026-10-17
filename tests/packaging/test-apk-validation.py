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


if __name__ == "__main__":
    unittest.main()
