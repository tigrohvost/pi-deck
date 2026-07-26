# ADR-010: Release signing and SBOM

Status: accepted with external prerequisites.

Release builds never fall back to debug signing. Production publication
requires CI-injected secrets, a GitHub-verified signed tag, `apksigner`,
checksums, manifests and a CycloneDX SBOM. Native Termux packages remain
repository-resolved and are explicitly documented rather than presented as
byte-reproducible.
