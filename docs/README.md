# CdrBounty Documentation

This directory is the source of truth for CdrBounty design and release planning.

## Roadmap Specifications

| Version | Document |
|---|---|
| `beta.1` | [`roadmap/beta-1-core-foundation.md`](roadmap/beta-1-core-foundation.md) |
| `beta.2` | [`roadmap/beta-2-contract-engine.md`](roadmap/beta-2-contract-engine.md) |
| `v0.3.0` | [`roadmap/v0.3.0-hunter-system.md`](roadmap/v0.3.0-hunter-system.md) |
| `v0.4.0` | [`roadmap/v0.4.0-intelligence-tracking.md`](roadmap/v0.4.0-intelligence-tracking.md) |
| `v0.5.0` | [`roadmap/v0.5.0-heat-wanted.md`](roadmap/v0.5.0-heat-wanted.md) |
| `v0.6.0` | [`roadmap/v0.6.0-capture-counterplay.md`](roadmap/v0.6.0-capture-counterplay.md) |
| `v0.7.0` | [`roadmap/v0.7.0-social-warfare.md`](roadmap/v0.7.0-social-warfare.md) |
| `v0.8.0` | [`roadmap/v0.8.0-integrations-api.md`](roadmap/v0.8.0-integrations-api.md) |
| `v0.9.0` | [`roadmap/v0.9.0-production-hardening.md`](roadmap/v0.9.0-production-hardening.md) |
| `v1.0.0` | [`roadmap/v1.0.0-production-release.md`](roadmap/v1.0.0-production-release.md) |

## Documentation Rules

Every roadmap document should answer five questions:

1. **Why does this phase exist?**
2. **What is explicitly in scope?**
3. **What is explicitly out of scope?**
4. **What persistent state/API behavior does it introduce or modify?**
5. **How do we know the phase is complete?**

When implementation differs from the roadmap, update the documentation in the same development cycle. Documentation must describe shipped behavior, not abandoned intent.

## Stability Levels

- **Planned** — design may still change substantially.
- **In Development** — implementation has started; data/API changes are still possible.
- **Feature Complete** — scope implemented; regression and migration work may remain.
- **Release Candidate** — no planned functional changes except fixes.
- **Stable** — tagged production release.

## Versioning Principle

Before `v1.0.0`, breaking configuration or API changes are allowed when documented. Starting at `v1.0.0`, public API/config compatibility should follow semantic versioning and documented migration rules.

## Future Documentation Areas

As implementation begins, this directory should gain dedicated sections for:

- Architecture
- Commands
- Permissions
- Configuration
- Storage schema
- Public API
- Integrations
- Migration guides
- Administrator operations
- Troubleshooting
- Security / abuse model
- Release changelogs
