#!/usr/bin/env python3
"""Verify that Paper is an isolated application profile."""

from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def make_database(profile=None):
    command = ["make", "-s", "-pn"]
    if profile:
        command.append(f"PROFILE={profile}")
    result = subprocess.run(
        command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, check=False)
    values = {}
    wanted = {
        "PROFILE", "BUILD", "STAGE", "OUT", "TITLE", "VERSION",
        "TITLE_ID", "CONTENT_ID", "APP_MAIN_CLASS", "APP_USER_DIR",
        "APP_LOG_PATH", "RUNTIME_MODULES", "PAPERCLIP_JAR",
        "PAPER_EXPANDED_ROOT",
    }
    for line in result.stdout.splitlines():
        for separator in (" := ", " = "):
            if separator in line:
                key, value = line.split(separator, 1)
                if key in wanted:
                    values[key] = value
                break
    return values


probe = make_database()
assert probe["PROFILE"] == "probe"
assert probe["BUILD"] == "build"
assert probe["STAGE"] == "build/stage"
assert probe["TITLE_ID"] == "OJDK00001"
assert probe["CONTENT_ID"] == "IV0000-OJDK00001_00-ORBISJDKAPP00001"
assert probe["APP_MAIN_CLASS"] == "demo/Main"
assert probe["APP_USER_DIR"] == "/app0"

paper = make_database("paper")
assert paper["PROFILE"] == "paper"
assert paper["BUILD"] == "build-paper"
assert paper["STAGE"] == "build-paper/stage"
assert paper["OUT"] == "build-paper/output"
assert paper["TITLE"] == "Orbis Paper 1.18.2"
assert paper["TITLE_ID"] == "OJDK00002"
assert paper["CONTENT_ID"] == "IV0000-OJDK00002_00-ORBISPAPER018020"
assert paper["APP_MAIN_CLASS"] == "paperbootstrap/Main"
assert paper["APP_USER_DIR"] == "/data/orbis-jdk/paper"
assert paper["APP_LOG_PATH"] == "/data/orbis-jdk/logs/orbis-paper.log"
assert paper["PAPERCLIP_JAR"] == "/home/wonder/paper-1.18.2-388.jar"
assert paper["PAPER_EXPANDED_ROOT"]

modules = paper["RUNTIME_MODULES"].split(",")
for module in (
    "java.datatransfer", "java.desktop", "java.management", "java.prefs",
    "jdk.crypto.ec", "jdk.jfr",
):
    assert module in modules, module
assert "jdk.sctp" not in modules
assert len(modules) == len(set(modules))

print("Paper profile isolation contract passed")
