# ADR-011: App-owned foreground inference

Status: accepted.

Android schedules a Termux process independently from the visible PI//DECK
Activity. On the reference SM-S918B this moved Termux `llama-server` to the
background CPU-set while the deck was visible and reduced generation from
roughly 16 tokens/s to 0.058 tokens/s.

PI//DECK therefore ships the pinned official Android arm64 `llama.cpp b10092`
runtime and owns its process in a `specialUse` foreground service. Termux keeps
Pi, sessions, tools and the authenticated RPC bridge. The bridge may adopt the
native server only after an exact build, model hash, API key, CPU profile and
live health check match.

The default 2B profile allocates a real 10240-token context for Pi's safety
reserve and uses the measured decode/batch affinity. Speculative MTP remains
disabled because reference-device measurements were slower than ordinary
decode. The notification opens the deck; stopping remains a coordinated deck
operation so the bridge cannot be left pointing at a dead server.
