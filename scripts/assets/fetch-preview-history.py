import datetime
import gzip
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUTH = ROOT / '.tools/so23335421.json'
WORK = ROOT / '.tools/preview-max'
WORK.mkdir(exist_ok=True)
REPORT = ROOT / 'artifacts/preview-max-progress.json'
state_file = WORK / 'state.json'
ids_file = WORK / 'ids.json.gz'
window_file = WORK / 'window.jsonl'
headers = {'Content-Type': 'application/json', 'User-Agent': 'AniCome/preview-material', 'Origin': 'https://www.nicovideo.jp', 'Referer': 'https://www.nicovideo.jp/', 'X-Frontend-Id': '6', 'X-Frontend-Version': '0'}

def request(target, when):
    watch = json.loads(AUTH.read_text(encoding='utf-8-sig'))['data']['response']
    assert watch['video']['id'] == 'so23335421'
    nv = watch['comment']['nvComment']
    params = dict(nv['params'], targets=[target])
    body = json.dumps({'params': params, 'threadKey': nv['threadKey'], 'additionals': {'when': when, 'res_from': -1000}}).encode()
    address = nv['server'].rstrip('/') + '/v1/threads'
    assert address.startswith('https://') and urllib.parse.urlparse(address).hostname.endswith('.nicovideo.jp')
    for attempt in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(address, data=body, headers=headers), timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504):
                meta = json.loads(error.read()).get('meta', {})
                raise RuntimeError(f"Comment API {error.code}: {meta.get('errorCode', 'unknown')}") from None
        except (TimeoutError, urllib.error.URLError):
            pass
        time.sleep(min(60, 3 * 2**attempt))
    raise RuntimeError('Comment API retry limit reached')

initial = json.loads((ROOT / '.tools/preview-auth-initial.json').read_text())
watch = json.loads(AUTH.read_text(encoding='utf-8-sig'))['data']['response']
targets = [t for t in watch['comment']['nvComment']['params']['targets'] if t['fork'] not in ('easy', 'ai')]
targets = [t for t in targets if any(str(row['id']) == str(t['id']) and row['fork'] == t['fork'] and row.get('commentCount', 0) > 0 for row in initial['data']['threads'])]
state = json.loads(state_file.read_text()) if state_file.exists() else {'target': 0, 'cursor': int(time.time()), 'pages': 0, 'intervalCount': 0, 'complete': False, 'boundaryWarnings': 0}
seen = set(json.loads(gzip.decompress(ids_file.read_bytes()))) if ids_file.exists() else set()
state.pop('error', None)

def checkpoint():
    ids_file.write_bytes(gzip.compress(json.dumps(list(seen)).encode()))
    state_file.write_text(json.dumps(state), encoding='utf-8')
    REPORT.write_text(json.dumps(dict(state, uniqueComments=len(seen), advertisedComments=sum(row.get('commentCount', 0) for row in initial['data']['threads'] if row['fork'] not in ('easy', 'ai'))), indent=2), encoding='utf-8')

try:
    while state['target'] < len(targets):
        target = targets[state['target']]
        result = request(target, state['cursor'])
        assert result['meta']['status'] == 200
        thread = next((t for t in result['data']['threads'] if str(t['id']) == str(target['id']) and t['fork'] == target['fork']), None)
        comments = thread.get('comments', []) if thread else []
        if not comments:
            state['target'] += 1
            state['cursor'] = int(time.time())
            checkpoint()
            continue
        fresh = []
        for comment in comments:
            identity = f"{target['fork']}:{target['id']}:{comment['no']}"
            if identity in seen:
                continue
            seen.add(identity)
            if 128000 <= comment.get('vposMs', -1) <= 228000 and comment.get('body', '').strip():
                fresh.append({'at': comment['vposMs'], 'body': comment['body'], 'raw': json.dumps(comment, ensure_ascii=False, separators=(',', ':')), 'fork': target['fork']})
        with window_file.open('a', encoding='utf-8') as out:
            for row in fresh:
                out.write(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n')
        state['intervalCount'] += len(fresh)
        state['pages'] += 1
        oldest = min(datetime.datetime.fromisoformat(c['postedAt'].replace('Z', '+00:00')).timestamp() for c in comments)
        cursor = int(oldest)
        if min(c['no'] for c in comments) <= 1:
            state['target'] += 1
            state['cursor'] = int(time.time())
        elif cursor >= state['cursor']:
            state['boundaryWarnings'] += 1
            state['cursor'] -= 1
        else:
            state['cursor'] = cursor
        if state['pages'] % 10 == 0:
            checkpoint()
            print(json.dumps(dict(pages=state['pages'], unique=len(seen), interval=state['intervalCount'], oldest=datetime.datetime.fromtimestamp(oldest, datetime.timezone.utc).isoformat())), flush=True)
        time.sleep(1.05)
    state['complete'] = True
    checkpoint()
    print('COMPLETE ' + json.dumps(state), flush=True)
except Exception as error:
    state['error'] = str(error)
    checkpoint()
    print('STOPPED ' + str(error), flush=True)
    raise
