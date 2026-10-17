import argparse
import json
import re
import subprocess
from pathlib import Path
from xml.etree import ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--label", required=True)
parser.add_argument("--adb", default=".tools/adb36/platform-tools/adb.exe")
args = parser.parse_args()
adb = [str(Path(args.adb).resolve()), "-s", args.serial]


def run(*parts):
    return subprocess.check_output(adb + list(parts), text=True, encoding="utf-8", timeout=25).strip()


def require_foreground():
    if not re.search(r"mCurrentFocus=.*net\.yexe\.anicome/", run("shell", "dumpsys", "window")):
        raise RuntimeError("AniCome must remain in the foreground during measurement")


def nodes():
    require_foreground()
    run("shell", "uiautomator", "dump", "/data/local/tmp/anicome-benchmark.xml")
    return list(ET.fromstring(run("shell", "cat", "/data/local/tmp/anicome-benchmark.xml")).iter("node"))


def click(label, items):
    node = [item for item in items if item.get("text") == label][-1]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


run("shell", "am", "start", "-W", "-n", "net.yexe.anicome/.MainActivity")
for _ in range(5):
    items = nodes()
    labels = {item.get("text") for item in items}
    if {"NGワード", "色と書体", "設定"} <= labels:
        break
    if "設定" in labels:
        click("設定", items)
    else:
        run("shell", "input", "keyevent", "KEYCODE_BACK")
else:
    raise RuntimeError("Settings screen is unavailable")

size = list(map(int, re.findall(r"\d+", run("shell", "wm", "size"))))
width, height = size[-2:]
run("shell", "dumpsys", "gfxinfo", "net.yexe.anicome", "reset")
for _ in range(4):
    require_foreground()
    run("shell", "input", "swipe", str(width // 2), str(int(height * .8)), str(width // 2), str(int(height * .35)), "300")
    run("shell", "input", "swipe", str(width // 2), str(int(height * .35)), str(width // 2), str(int(height * .8)), "300")
require_foreground()
report = run("shell", "dumpsys", "gfxinfo", "net.yexe.anicome")
result = {"label": args.label}
for line in report.splitlines():
    if re.search(r"Total frames rendered|Janky frames:|percentile:|Missed Vsync|Slow UI thread|Slow bitmap uploads|Slow issue draw commands", line):
        key, value = line.strip().split(":", 1)
        result[key] = value.strip()
Path("artifacts").mkdir(exist_ok=True)
Path(f"artifacts/frame-{args.label}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
run("shell", "rm", "-f", "/data/local/tmp/anicome-benchmark.xml")
print(json.dumps(result, ensure_ascii=False, indent=2))
