# ADR-006: Private GGUF

Status: accepted.

Shared Downloads is incoming transport only. A model is copied while hashing to
a same-filesystem random temp file, fsynced, atomically renamed, made read-only
and fully rehashed before each server start. Size/mtime caching was rejected
because autonomous tools share the Termux UID.
