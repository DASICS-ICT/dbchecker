DASICS Bus Checker
=======================
This is the DASICS Bus Checker (DBChecker) developed by Gwins7.

use `make` to generate verilog file.

Referred Repo:

[chisel3-axi](https://github.com/nhynes/chisel3-axi)


## Buffered-check implementation (outstanding-refill)

The address path uses a fixed request ring, independent cache/refill completion,
a combinational head check, and separate AR/AW output registers. FREE invalidates
local metadata copies and retries acquisition before commit.

Run the assertion-enabled top-level regression without git side effects:

```sh
python3 sim_env/run_buffered_check.py --output /tmp/dbchecker-buffered-review
```

The runner requires Mill, Verilator, and a C++17 compiler on `PATH`.
Use `--depth`, `--capacity`, and `--cache` to select elaboration parameters.
Defaults are D=64, Kmax=64, cache=4096; the initial runtime K is 32. Logs,
generated RTL and JSONL test results are saved in the output directory.

The regression covers burst checks, cache aliases, FREE/refill races, AXI
backpressure, malformed refill responses, ring wraparound, MMIO, and refill
credit limits. It also measures hot-cache and no-cache throughput. Configurations
D64/Kmax64/cache4096, D32/Kmax32/cache1024, and D128/Kmax64/cache4096 have passed.

This is the v1 baseline, identified by MMIO signature `BC11` at `0x7c`.
The default configuration synthesized for `xczu19eg-ffvc1760-2-e`, but failed
the 4 ns timing target (WNS -2.296 ns). Board validation remains pending.
The proposed Fetch/Range/Check pipeline and sticky FREE revocation are not yet
implemented. MMIO write acknowledgement currently precedes cache FREE completion;
their completion timing remains a separate follow-up.

In the enclosing `mpsoc-dev` workspace, the `docs/` directory contains
`dbchecker-buffered-check-implementation-results.md` and the archived
`dbchecker-buffered-check-implementation-plan-v1.md` for this implementation.
The current `dbchecker-buffered-check-pipeline.md` and
`dbchecker-buffered-check-implementation-plan.md` describe the next iteration.
These workspace documents are not included in a standalone clone of this repository.
