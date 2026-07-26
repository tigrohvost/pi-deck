# ADR-008: Compatibility and rollback

Status: accepted.

Pi package/version/integrity, Node minimum and llama build range are activated
as one compatibility set. Runtime updates stage and smoke-test before an atomic
link switch, restoring an old target on failure. Floating `@latest` and
substring version matching were rejected.
