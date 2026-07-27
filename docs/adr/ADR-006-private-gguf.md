# ADR-006: Private GGUF

Status: accepted.

Shared Downloads is incoming transport only. Android verifies the incoming
artifact, then PI//DECK copies it into its own app sandbox while hashing a
second time. The temporary file is fsynced, atomically renamed, changed to exact
mode `0400`, and its directory is fsynced. Termux tools run under another UID
and cannot modify the installed model.
