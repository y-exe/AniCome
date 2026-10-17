import argparse
import base64
import json
import os
import re
import subprocess
from pathlib import Path


def github(*args, payload=None):
    result = subprocess.run(["gh", "api", *args], input=json.dumps(payload) if payload else None, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout)


parser = argparse.ArgumentParser()
parser.add_argument("version")
args = parser.parse_args()
assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", args.version), "Invalid version"
repository = os.environ["GITHUB_REPOSITORY"]
assert os.environ["GITHUB_REF"] == "refs/heads/main", "Run releases from main"
current = dict(line.split("=", 1) for line in Path("version.properties").read_text().splitlines() if line)
version_code = int(current["versionCode"]) + 1
assert tuple(map(int, args.version.split("-")[0].split("."))) >= tuple(map(int, current["versionName"].split("-")[0].split("."))), "Version must not go backwards"
head = github(f"repos/{repository}/git/ref/heads/main")["object"]["sha"]
assert head == os.environ["GITHUB_SHA"], "main changed; start a fresh release"
if current["versionName"] == args.version:
    sha = head
    version_code = int(current["versionCode"])
else:
    query = """mutation($input: CreateCommitOnBranchInput!) {
      createCommitOnBranch(input: $input) { commit { oid } }
    }"""
    contents = f"versionName={args.version}\nversionCode={version_code}\n"
    result = github("graphql", "--input", "-", payload={"query": query, "variables": {"input": {
        "branch": {"repositoryNameWithOwner": repository, "branchName": "main"},
        "expectedHeadOid": head,
        "message": {"headline": f"v{args.version}"},
        "fileChanges": {"additions": [{"path": "version.properties", "contents": base64.b64encode(contents.encode()).decode()}]}
    }}})
    assert not result.get("errors"), result.get("errors")
    sha = result["data"]["createCommitOnBranch"]["commit"]["oid"]
commit = github(f"repos/{repository}/commits/{sha}")
assert commit["commit"]["verification"]["verified"], "Release commit must have a verified signature"
with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
    output.write(f"sha={sha}\nversion={args.version}\n")
print(f"Prepared v{args.version} ({version_code}), verified commit {sha}")
