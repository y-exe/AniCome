import argparse
import json
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as archive:
    names = archive.namelist()
    assert not any(name.startswith('assets/preview/') for name in names), 'Downloaded preview comments must not be bundled'
    assert not any('welcome_cover_' in name or '/service_' in name or 'niconico_logo' in name for name in names), 'Third-party artwork must be loaded at runtime'
print(json.dumps({'previewCommentsBundled': False, 'welcomeArtworkBundled': False}))
