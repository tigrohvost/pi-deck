# ADR-005: Permission gate

Status: accepted.

First-run access is `READ_ONLY`. `CONFIRM_CHANGES` disables built-in mutators
and registers gated equivalents using the exact Pi extension API. Timeouts,
disconnects and restarts deny. Relying on `--approve` was rejected: in Pi
0.82.1 it trusts project instructions and is not a per-tool authorization
mechanism.
