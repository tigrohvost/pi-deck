# ADR-007: Model manifest

Status: accepted.

One strict `models-v2.json` drives Android, download, install, server and Pi
provider configuration. Parallel Java/shell catalogs were rejected because
their IDs, hashes and runtime flags drift. Promotion requires verified
provenance and a checked-in benchmark report.
