import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LANGUAGES = ("en", "ko", "fr", "zh", "es", "pt")
DATA_FILES = {"EpisodeMatcher.kt", "PlaybackMetadata.kt", "PrimePlaybackRepository.kt", "NetflixEpisodeRepository.kt", "DanimePlaybackRepository.kt", "ReferenceClover.kt", "AnimeWorkIdentity.kt", "SettingSearchTerms.kt"}
DATA_LITERALS = {"日本語", "简体中文", "バンダイチャンネル", "dアニメ", "アニメ", "コメント専用動画", "SZBH方式", "振り返り上映会.xml", "一挙放送.xml", "_raw.xml", "ご注文はうさぎですか", "ゆるキャン", "ぼっち・ざ・ろっく", "ひだまりスケッチ"}


def literals(code):
    found = []

    def character(start):
        cursor = start + 1
        while cursor < len(code):
            if code[cursor] == "\\":
                cursor += 2
            elif code[cursor] == "'":
                return cursor + 1
            else:
                cursor += 1
        raise ValueError("Unterminated Kotlin character")

    def quoted(start):
        triple = code.startswith('"""', start)
        delimiter = '"""' if triple else '"'
        cursor = start + len(delimiter)
        value = []
        tokens = 0
        while cursor < len(code):
            if code.startswith(delimiter, cursor):
                text = "".join(value)
                found.append((start, text))
                return cursor + len(delimiter)
            if not triple and code[cursor] == "\\":
                escaped = code[cursor + 1:cursor + 2]
                value.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$"}.get(escaped, "\\" + escaped))
                cursor += 2
                continue
            if code.startswith("${", cursor):
                cursor += 2
                depth = 1
                while cursor < len(code) and depth:
                    if code[cursor] == "'":
                        cursor = character(cursor)
                        continue
                    if code[cursor] == '"':
                        cursor = quoted(cursor)
                        continue
                    if code[cursor] == "{":
                        depth += 1
                    elif code[cursor] == "}":
                        depth -= 1
                    cursor += 1
                value.append("{" + str(tokens) + "}")
                tokens += 1
                continue
            variable = re.match(r"\$[A-Za-z_][A-Za-z_0-9]*", code[cursor:])
            if variable:
                value.append("{" + str(tokens) + "}")
                tokens += 1
                cursor += len(variable.group())
                continue
            value.append(code[cursor])
            cursor += 1
        raise ValueError("Unterminated Kotlin string")

    cursor = 0
    while cursor < len(code):
        if code.startswith("//", cursor):
            end = code.find("\n", cursor)
            cursor = len(code) if end < 0 else end + 1
        elif code.startswith("/*", cursor):
            end = code.find("*/", cursor + 2)
            cursor = len(code) if end < 0 else end + 2
        elif code[cursor] == '"':
            cursor = quoted(cursor)
        elif code[cursor] == "'":
            cursor = character(cursor)
        else:
            cursor += 1
    return found


def inventory():
    result = {}
    for path in (ROOT / "app/src/main/java/net/yexe/anicome").rglob("*.kt"):
        if path.name in DATA_FILES:
            continue
        code = path.read_text(encoding="utf-8")
        for position, source in literals(code):
            if path.name == "Comments.kt" and code.find("fun buildSearchQuery") < position < code.find("suspend fun searchSnapshot"):
                continue
            if not re.search(r"[ぁ-龯]", source) or source in DATA_LITERALS:
                continue
            before = code[max(code.rfind("\n", 0, position) + 1, position - 120):position]
            if re.search(r"(?:Regex|EpisodeMatcher\.extract|EpisodeMatcher\.normalize|contains|startsWith|endsWith|replace)\(\s*$", before):
                continue
            format_index = len(re.findall(r"\{\d+\}", source))
            def format_token(match):
                nonlocal format_index
                token = "{" + str(format_index) + "}"
                format_index += 1
                return token
            source = re.sub(r"%(?:\d+\$)?[-+\d.]*[dfs]", format_token, source)
            result.setdefault(source, []).append(f"{path.name}:{code.count(chr(10), 0, position) + 1}")
    return result


def pattern(source):
    chunks = re.split(r"(\{\d+\})", source)
    return re.compile("^" + "".join(".+?" if re.fullmatch(r"\{\d+\}", chunk) else re.escape(chunk) for chunk in chunks) + "$")


def main():
    sources = inventory()
    report = {}
    tables = {}
    for lang in LANGUAGES:
        tables[lang] = json.loads((ROOT / f"app/src/main/assets/i18n/{lang}.json").read_text(encoding="utf-8"))
        templates = [pattern(key) for key in tables[lang] if "{0}" in key]
        missing = {source: locations for source, locations in sources.items() if source not in tables[lang] and not any(p.fullmatch(source) for p in templates)}
        report[lang] = missing
        for source, translated in tables[lang].items():
            if not translated.strip() or set(re.findall(r"\{\d+\}", source)) != set(re.findall(r"\{\d+\}", translated)):
                raise ValueError(f"Invalid translation: {lang}: {source}")
    if any(set(tables[lang]) != set(tables["en"]) for lang in LANGUAGES):
        raise ValueError("Language catalogs have different keys")
    output = ROOT / "artifacts/translation-audit.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({"scanned": len(sources), "catalogEntries": len(tables["en"]), "missing": report}, ensure_ascii=False, indent=2), encoding="utf-8")
    if "--list" in sys.argv:
        for source, places in report["en"].items():
            print(json.dumps(source, ensure_ascii=False), " ", places[0])
    print(json.dumps({"scanned": len(sources), "catalogEntries": len(tables["en"]), "missing": {lang: len(value) for lang, value in report.items()}}))
    return int(any(report.values()))


if __name__ == "__main__":
    sys.exit(main())
