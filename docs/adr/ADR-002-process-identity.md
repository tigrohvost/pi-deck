# ADR-002: Process identity and abort

Status: accepted.

Managed processes record PID, process group, `/proc` start ticks, command hash,
expected executable and operation token. Signals use verified process groups in
SIGINT/TERM/KILL order. Name-based `pkill` was rejected because PID reuse and
unrelated manual Pi processes make it unsafe.
