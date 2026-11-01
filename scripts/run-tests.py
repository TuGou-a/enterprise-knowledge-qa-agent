#!/usr/bin/env python3
"""获得测试指令后运行；保存同一次 Maven 调用的命令、报告与退出码。"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("unit", "postgres"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    started = datetime.now(timezone.utc)
    run_id = started.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex
    output = root / "examples" / "tests" / args.mode / run_id
    reports = output / "reports"
    command = ["mvn", "-Dstyle.color=never", "-Dtest.reports-directory=" + str(reports)]
    if args.mode == "postgres":
        command.append("-Ppostgres-it")
    command.append("test")
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                            capture_output=True, text=True).stdout.strip()
    dirty = bool(subprocess.run(["git", "status", "--porcelain"], cwd=root, check=True,
                               capture_output=True, text=True).stdout.strip())
    reports.mkdir(parents=True)
    metadata = {
        "materialType": "ACTUAL_AUTOMATED_TEST_RUN",
        "mode": "ISOLATED_UNIT" if args.mode == "unit" else "POSTGRES_WITH_MODEL_STUBS",
        "commit": commit,
        "workingTreeDirtyAtStart": dirty,
        "startedAt": started.isoformat(),
        "command": command,
        "status": "STARTED",
    }
    path = output / "run.json"
    path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print("测试证据目录：" + str(output), flush=True)
    try:
        with (output / "console.log").open("w") as log:
            completed = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        exit_code = completed.returncode
    except OSError as error:
        metadata["launchErrorType"] = type(error).__name__
        exit_code = 127
    except KeyboardInterrupt:
        metadata["interrupted"] = True
        exit_code = 130
    metadata["finishedAt"] = datetime.now(timezone.utc).isoformat()
    metadata["exitCode"] = exit_code
    metadata["status"] = "FINISHED" if exit_code == 0 else "FAILED"
    suites = []
    collection_errors = []
    for report in sorted(reports.glob("TEST-*.xml")):
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError:
            collection_errors.append(report.name)
            continue
        suites.append({"file": report.name, "tests": int(suite.get("tests", "0")),
                       "failures": int(suite.get("failures", "0")),
                       "errors": int(suite.get("errors", "0")),
                       "skipped": int(suite.get("skipped", "0"))})
    metadata["suites"] = suites
    metadata["reportCollectionErrors"] = collection_errors
    if collection_errors:
        metadata["status"] = "REPORT_COLLECTION_FAILED"
    metadata["testCounts"] = ({key: sum(suite[key] for suite in suites)
                               for key in ("tests", "failures", "errors", "skipped")}
                              if suites else None)
    path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print("Maven 退出码：" + str(exit_code))
    return exit_code or (1 if collection_errors else 0)


if __name__ == "__main__":
    sys.exit(main())
