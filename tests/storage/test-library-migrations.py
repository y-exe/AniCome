import json
import re
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "app/schemas/net.yexe.anicome.LibraryDatabase"
source = (ROOT / "app/src/main/java/net/yexe/anicome/core/storage/LibraryDatabase.kt").read_text(encoding="utf-8")

def schema(version):
    return json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]

def create(db, version):
    for entity in schema(version)["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

def seed(db, table, key):
    columns = [row[1] for row in db.execute(f"PRAGMA table_info({table})")]
    values = {"key": key, "series": "preserved title", "episode": 13, "sourceId": "so123", "sourceTitle": "episode 13", "savedAt": 1234, "count": 123, "bytes": 456, "amount": 20, "thumbnailUrl": "", "ownerName": "channel", "ownerUrl": "", "sources": "OFFICIAL,DANIME,LIVE_ARCHIVE"}
    db.execute(f"INSERT INTO {table} ({','.join('`' + name + '`' for name in columns)}) VALUES ({','.join('?' for _ in columns)})", [values[c] for c in columns])

def migrate(db, start):
    for version in range(start, 6):
        block = source.split(f"val MIGRATION_{version}_{version+1} =", 1)[1].split("val MIGRATION_", 1)[0]
        for sql in re.findall(r'db.execSQL\("([^"\n]+)"\)', block):
            db.execute(sql)

results = []
for version in [1, 2, 3, 4, 5]:
    db = sqlite3.connect(":memory:")
    create(db, version)
    seed(db, "saved_episodes", "manual")
    if version >= 3:
        seed(db, "cached_episodes", "cache")
    if version >= 4:
        db.execute("INSERT INTO comment_history(time,kind,series,episode,query,sourceId,sourceTitle,count,previousId,previousCount,sources,storage) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (1, "VIDEO_SEARCH", "old work", 13, "preserved search", "", "", 3, "", 0, "", "manual"))
    before = db.execute("SELECT `key`, series, episode, sourceId, count, amount FROM saved_episodes").fetchall()
    migrate(db, version)
    assert before == db.execute("SELECT `key`, series, episode, sourceId, count, amount FROM saved_episodes").fetchall()
    if version >= 3:
        assert db.execute("SELECT sources FROM cached_episodes WHERE `key`='cache'").fetchone()[0] == "OFFICIAL,DANIME,LIVE_ARCHIVE"
    if version >= 4:
        assert db.execute("SELECT query, episode FROM comment_history").fetchone() == ("preserved search", 13)
    expected = sqlite3.connect(":memory:")
    create(expected, 6)
    for entity in schema(6)["entities"]:
        table = entity["tableName"]
        assert db.execute(f"PRAGMA table_info({table})").fetchall() == expected.execute(f"PRAGMA table_info({table})").fetchall(), table
        assert sorted(db.execute(f"PRAGMA index_list({table})").fetchall()) == sorted(expected.execute(f"PRAGMA index_list({table})").fetchall()), table
    for n in range(510):
        db.execute("INSERT INTO comment_history(time,kind,series,episode,query,sourceId,sourceTitle,count,previousId,previousCount,sources,storage) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (n, "WORK_SEARCH", "", -1, f"query{n}", "", "", 1, "", 0, "", "manual"))
    dao = (ROOT / "app/src/main/java/net/yexe/anicome/feature/history/CommentHistory.kt").read_text(encoding="utf-8")
    prune = re.search(r'@Query\("(DELETE FROM comment_history WHERE id NOT IN[^"\n]+)"\)', dao).group(1)
    db.execute(prune)
    assert db.execute("SELECT COUNT(*), MIN(time), MAX(time) FROM comment_history").fetchone() == (500, 10, 509)
    db.execute("INSERT INTO comment_history(time,kind,series,episode,query,sourceId,sourceTitle,count,previousId,previousCount,sources,storage) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (-1, "WORK_SEARCH", "", -1, "clock-back", "", "", 1, "", 0, "", "manual"))
    db.execute(prune)
    observe = re.search(r'@Query\("(SELECT \* FROM comment_history ORDER BY[^"\n]+ LIMIT 500)"\)', dao).group(1)
    cursor = db.execute(observe)
    names = [item[0] for item in cursor.description]
    assert cursor.fetchone()[names.index("query")] == "clock-back"
    assert db.execute("SELECT COUNT(*) FROM comment_history").fetchone()[0] == 500
    db.execute("INSERT INTO download_reservations VALUES (?,?,?,?,?,?,?,?,?,?)", ("reservation-id", "comments-key", "preserved title", 13, '{"tags":[]}', "PAUSED", 1, 1000, "work-id", ""))
    assert db.execute("SELECT state, episode, workId FROM download_reservations").fetchone() == ("PAUSED", 13, "work-id")
    results.append({"from": version, "to": 6, "manualPreserved": True, "schemaMatchesRoom": True, "historyBound": 500, "pausedReservationPersisted": True})
with tempfile.TemporaryDirectory(prefix="anicome-queue-sqlite-") as folder:
    path = str(Path(folder) / "library.db")
    db = sqlite3.connect(path)
    create(db, 6)
    payload = json.dumps({"series": "work cour 2", "episode": 1, "origin": "manual", "wifi": True, "charging": True, "tags": ["manual", "series:work cour 2", "episode:1", "reviewSeries:work", "reviewNumber:13", "reviewSubtitle:subtitle", "reviewVideo:so13", "sources:OFFICIAL,DANIME", "channel:123"]})
    snapshot = ("reservation-id", "comments-key", "work cour 2", 1, payload, "PAUSED", 2, 1000, "work-id", "")
    db.execute("INSERT INTO download_reservations VALUES (?,?,?,?,?,?,?,?,?,?)", snapshot)
    db.execute("INSERT INTO traffic_usage VALUES (?,?,?)", ("2026-10-15", 1048576, 2))
    db.commit()
    db.close()
    reopened = sqlite3.connect(path)
    assert reopened.execute("SELECT * FROM download_reservations").fetchone() == snapshot
    assert reopened.execute("SELECT bytes,generation FROM traffic_usage").fetchone() == (1048576, 2)
    reopened.close()
    for result in results:
        result["queuePauseSurvivesDatabaseReopen"] = True
        result["trafficUsageSurvivesDatabaseReopen"] = True
print(json.dumps(results))
