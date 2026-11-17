# Contributing

Thanks for your interest in Dnsly. The project is Kotlin + Rust and **the whole toolchain
lives in Docker**: do not install the Android SDK, NDK, Rust or Gradle on your machine.

## Before opening a PR

```bash
docker/test-core.sh                    # Rust: cargo fmt --check, clippy -D warnings, tests
docker/gradle.sh testDebugUnitTest     # Android: unit tests
```

Both must pass. Read [ARCHITECTURE.md](ARCHITECTURE.md) before changing the design: the
technical decisions (DNS-only VPN, pure engine, fail-open to the network DNS) are not up for
grabs for convenience.

## Conventions

- Commits in [Conventional Commits](https://www.conventionalcommits.org/) format
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`...).
- Code and identifiers in English.
- Short functions, single responsibility, no dead code or speculative abstractions.
- Every non-trivial new logic path gets a test. In Rust: no `unwrap()` in production code,
  and `cargo fmt` + `clippy -D warnings` clean.
- Never commit signing keys (`*.jks`, `*.keystore`, `keystore.properties`).

## Reporting bugs

Open an issue with: Android version, what you were doing, what you expected and what
happened. If you can, `adb logcat -s Dnsly` logs help a lot (the `Log.w` error paths stay in
release builds).
