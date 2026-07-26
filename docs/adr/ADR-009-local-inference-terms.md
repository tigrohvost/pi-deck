# ADR-009: Local inference terminology

Status: accepted.

UI and docs separately state local inference, tool network capability and OS
network isolation. `PI_OFFLINE` only suppresses Pi startup network work; it is
not a sandbox. “Never leaves the device” was rejected while shell/network tools
can be enabled.
