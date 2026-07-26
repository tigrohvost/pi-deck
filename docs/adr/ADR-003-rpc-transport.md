# ADR-003: RPC transport

Status: accepted.

Termux `RUN_COMMAND` remains bootstrap-only. Interactive turns use a persistent
Python-stdlib bridge and Pi 0.82.1 JSONL RPC. This supports streaming, precise
abort, approvals and reconnect without placing prompts in argv. Direct
one-command Pi execution remains recovery code, not the UI turn path.
