# ADR-004: Localhost authentication

Status: accepted.

Loopback can be reached by other Android applications, so it is not sufficient
authentication. The bridge uses a random 256-bit Android-owned token; the
llama-server uses a separate random API key. Unix sockets were not selected
because cross-app filesystem/socket access is less portable across supported
Termux/Android versions.
