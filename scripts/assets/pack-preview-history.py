import argparse
import datetime
import gzip
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--allow-partial', action='store_true', help='Package the available checkpoint when explicitly accepted by the user')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
report_file = root / 'artifacts/preview-max-progress.json'
report = json.loads((report_file if report_file.exists() else root / '.tools/preview-max/state.json').read_text())
if 'uniqueComments' not in report:
    report['uniqueComments'] = len(json.loads(gzip.decompress((root / '.tools/preview-max/ids.json.gz').read_bytes())))
assert (report.get('complete') and not report.get('error')) or args.allow_partial, 'History retrieval is not complete'
assert report.get('boundaryWarnings', 0) == 0, 'Pagination boundary requires review'
rows = {}
with (root / '.tools/preview-max/window.jsonl').open(encoding='utf-8') as stream:
    for line in stream:
        comment = json.loads(line)
        assert 128000 <= comment['at'] <= 228000
        raw = json.loads(comment['raw'])
        identity = (comment['fork'], raw.get('id', ''), raw['no'])
        raw.pop('body', None)
        raw.pop('vposMs', None)
        comment['raw'] = raw
        rows.setdefault(identity, comment)
comments = sorted(rows.values(), key=lambda row: (datetime.datetime.fromisoformat(row['raw']['postedAt'].replace('Z', '+00:00')).timestamp(), row['raw'].get('no', 0)), reverse=True)
assert len(comments) == report['intervalCount'], 'Unexpected duplicate or missing comments'
comments = comments[:30000]
header = {'version': 3, 'order': 'postedAtDescending', 'videoId': 'so23335421', 'count': len(comments), 'startMs': 128000, 'endMs': 228000, 'retrievedTotal': report['uniqueComments']}
path = root / '.tools/preview-history/preview-sample.bin'
with path.open('wb') as output, gzip.GzipFile(filename='', mode='wb', fileobj=output, mtime=0) as compressed:
    compressed.write((json.dumps(header, separators=(',', ':')) + '\n').encode())
    for comment in comments:
        compressed.write((json.dumps(comment, ensure_ascii=False, separators=(',', ':')) + '\n').encode())
manifest = dict(header, bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), complete=True, historyComplete=report.get('complete', False), acceptedPartial=args.allow_partial, boundaryWarnings=report['boundaryWarnings'])
(root / 'artifacts/bundled-preview-data.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(json.dumps(manifest, indent=2))
