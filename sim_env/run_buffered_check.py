#!/usr/bin/env python3
"""Elaborate, build and run the buffered-check regression; no git side effects."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--depth", type=int, default=64)
    parser.add_argument("--capacity", type=int, default=64)
    parser.add_argument("--cache", type=int, default=4096)
    parser.add_argument("--jobs", type=int, default=8)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    dest = args.output.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update(DBCHECKER_D=str(args.depth), DBCHECKER_KMAX=str(args.capacity),
               DBCHECKER_CACHE=str(args.cache))
    config = dict(depth=args.depth, capacity=args.capacity, cache=args.cache)
    (dest / "config.json").write_text(json.dumps(config, indent=2) + "\n")

    def run(command, log):
        print(f"Running {command[0]}; log: {dest / log}", flush=True)
        with (dest / log).open("w") as stream:
            subprocess.run(command, cwd=repo, env=env, stdout=stream,
                           stderr=subprocess.STDOUT, check=True)

    run(["mill", "-i", "playground.runMain", "Elaborate", "--target-dir",
         str(dest / "rtl")], "elaborate.log")
    run(["verilator", "--cc", "--exe", "--build", "--assert", "-Wno-fatal",
         "--top-module", "DBChecker", "--Mdir", str(dest / "obj"),
         "-j", str(args.jobs), "-CFLAGS", "-O2 -std=c++17",
         str(dest / "rtl/DBChecker.v"), str(repo / "sim_env/buffered_check_tb.cpp")],
        "build.log")
    print(f"Running regression; results: {dest / 'results.jsonl'}", flush=True)
    with (dest / "results.jsonl").open("w") as output, (dest / "simulation.log").open("w") as errors:
        subprocess.run([str(dest / "obj/VDBChecker"), str(args.cache)], cwd=repo,
                       env=env, stdout=output, stderr=errors, check=True)
    print("Regression passed.", flush=True)


if __name__ == "__main__":
    main()
