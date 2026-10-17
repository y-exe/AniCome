import argparse
import hashlib
import json
import struct
import zipfile
import zlib
from pathlib import Path


def check_signing_block(raw, directory_offset):
    if directory_offset < 24:
        return []
    raw.seek(directory_offset - 24)
    footer = raw.read(24)
    if footer[8:] != b"APK Sig Block 42":
        return []
    size = struct.unpack_from("<Q", footer)[0]
    assert 24 <= size <= directory_offset - 8, "Invalid APK signing block size"
    position = directory_offset - size - 8
    raw.seek(position)
    assert struct.unpack("<Q", raw.read(8))[0] == size, "APK signing block size mismatch"
    position += 8
    end = directory_offset - 24
    identifiers = []
    while position < end:
        assert end - position >= 12, "Truncated APK signing block entry"
        raw.seek(position)
        length, identifier = struct.unpack("<QI", raw.read(12))
        assert 4 <= length <= end - position - 8, "Invalid APK signing block entry size"
        assert identifier != 0x504B4453, "Dependency metadata must not be embedded in release APKs"
        identifiers.append(f"0x{identifier:08x}")
        position += 8 + length
    return identifiers


def check_apk(path):
    result = {"file": str(path.resolve()), "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        result["signingBlocks"] = check_signing_block(raw, archive.start_dir)
        entries = archive.infolist()
        assert len(entries) == len({entry.filename for entry in entries}), "Duplicate ZIP entries"
        assert archive.testzip() is None, "ZIP CRC failure"
        assert archive.read("AndroidManifest.xml")[:4] == b"\x03\x00\x08\x00", "Invalid binary manifest"
        resources = archive.getinfo("resources.arsc")
        assert resources.compress_type == zipfile.ZIP_STORED, "Compressed resource table"
        dex = []
        libraries = []
        for entry in entries:
            if entry.filename.endswith(".dex"):
                data = archive.read(entry)
                assert data.startswith(b"dex\n"), entry.filename
                assert struct.unpack_from("<I", data, 32)[0] == len(data), entry.filename
                assert data[12:32] == hashlib.sha1(data[32:]).digest(), entry.filename
                assert struct.unpack_from("<I", data, 8)[0] == zlib.adler32(data[12:]) & 0xFFFFFFFF, entry.filename
                fields, methods = struct.unpack_from("<I", data, 80)[0], struct.unpack_from("<I", data, 88)[0]
                assert fields <= 65536 and methods <= 65536, entry.filename
                dex.append({"name": entry.filename, "fields": fields, "methods": methods})
            if entry.filename.startswith("lib/") and entry.filename.endswith(".so"):
                data = archive.read(entry)
                assert data[:5] == b"\x7fELF\x02", entry.filename
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                alignment = [struct.unpack_from("<Q", data, offset + i * size + 48)[0] for i in range(count) if struct.unpack_from("<I", data, offset + i * size)[0] == 1]
                assert alignment and all(value >= 16384 for value in alignment), entry.filename
                assert entry.compress_type == zipfile.ZIP_STORED, entry.filename
                raw.seek(entry.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
                assert (entry.header_offset + 30 + name_size + extra_size) % 16384 == 0, entry.filename
                libraries.append({"name": entry.filename, "loadAlignment": alignment})
        assert dex, "Missing DEX"
        result.update(zipCrc="ok", manifest="binary XML", dex=dex, nativeLibraries=libraries)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    print(json.dumps(check_apk(args.apk), ensure_ascii=False, indent=2))
