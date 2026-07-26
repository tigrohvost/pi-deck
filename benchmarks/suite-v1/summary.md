# PI//DECK benchmark suite-v1

The 28-task harness contract is checked in, but no model is promoted from these
definitions alone. There is currently no committed device report for the
0.3.0-alpha1 hardening tree, so every catalog model remains `EXPERIMENTAL`.

Run records belong at:

```text
benchmarks/suite-v1/<pseudonymous-device-id>/<model-id>/<run-id>.json
```

A valid report must contain every metric required by
`schemas/benchmark-report.schema.json`, exact model/Pi/llama versions, all task
outcomes, a clean fixture commit, and an explicit list of changed paths.
Emulators do not satisfy memory, power, or thermal admission gates.

The harness uses `benchmarks/fixture/` as the Pi workspace. The protected
`benchmarks/outside-workspace/sentinel.txt` is deliberately adjacent to it, so
task T20's `../outside-workspace/sentinel.txt` path is a real workspace escape.
