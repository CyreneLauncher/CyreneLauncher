#!/usr/bin/env python3
"""Compile-time check for the signal constants consumed by HotSpot on Orbis."""

import argparse
from pathlib import Path
import subprocess
import tempfile


EXPECTED = {
    "SIGHUP": 1,
    "SIGINT": 2,
    "SIGQUIT": 3,
    "SIGILL": 4,
    "SIGTRAP": 5,
    "SIGABRT": 6,
    "SIGEMT": 7,
    "SIGFPE": 8,
    "SIGKILL": 9,
    "SIGBUS": 10,
    "SIGSEGV": 11,
    "SIGSYS": 12,
    "SIGPIPE": 13,
    "SIGALRM": 14,
    "SIGTERM": 15,
    "SIGURG": 16,
    "SIGSTOP": 17,
    "SIGTSTP": 18,
    "SIGCONT": 19,
    "SIGCHLD": 20,
    "SIGTTIN": 21,
    "SIGTTOU": 22,
    "SIGIO": 23,
    "SIGPOLL": 23,
    "SIGXCPU": 24,
    "SIGXFSZ": 25,
    "SIGVTALRM": 26,
    "SIGPROF": 27,
    "SIGWINCH": 28,
    "SIGINFO": 29,
    "SIGUSR1": 30,
    "SIGUSR2": 31,
    "SA_ONSTACK": 0x0001,
    "SA_RESTART": 0x0002,
    "SA_RESETHAND": 0x0004,
    "SA_NODEFER": 0x0010,
    "SA_SIGINFO": 0x0040,
    "SIG_BLOCK": 1,
    "SIG_UNBLOCK": 2,
    "SIG_SETMASK": 3,
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cc", required=True, type=Path)
    parser.add_argument("--toolchain", required=True, type=Path)
    parser.add_argument("--jdk", required=True, type=Path)
    args = parser.parse_args()

    checks = ["#include <signal.h>"]
    checks.extend(
        f"static_assert({name} == {value}, \"{name} has the wrong Orbis ABI value\");"
        for name, value in EXPECTED.items()
    )
    checks.append("int main() { return 0; }")

    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / "signal_abi.cpp"
        source.write_text("\n".join(checks) + "\n")
        subprocess.run(
            [
                args.cc,
                "--target=x86_64-pc-freebsd12-elf",
                "-std=c++14",
                "-fsyntax-only",
                "-isysroot",
                args.toolchain,
                "-isystem",
                args.toolchain / "include/c++/v1",
                "-isystem",
                args.toolchain / "include",
                "-I",
                args.jdk / "src/hotspot/os/orbis",
                source,
            ],
            check=True,
        )
    print(f"Orbis signal ABI contract passed: {len(EXPECTED)} constants")


if __name__ == "__main__":
    main()
