# ADR-001: Durable operation store

Status: accepted.

Each UUIDv4 operation is an independent bounded JSON file in app-private
storage, committed with fsync plus rename. A SharedPreferences result slot was
rejected because process death, late callbacks and two equal operation kinds
cannot be correlated safely. Corrupt records are isolated and terminal history
is bounded to 100 records/20 MiB without deleting unresolved work.
