<p align="center">
  <img src="dnsly-logo.png" alt="Dnsly" width="128">
</p>

<h1 align="center">Dnsly</h1>

<p align="center">
  Use your own DNS server (e.g. a home Pi-hole) <strong>only when it makes sense</strong>:
  inside the configured area and while the server responds. Leave the area or the server
  goes down, and the phone falls back to the network DNS automatically — no root, no
  external service, no DNS leaks.
</p>

## How it works

```
Inside the area + server OK   →  local DNS-only VPN → your queries go to your server
Outside the area / server down → VPN torn down       → Android uses the network DNS
```

- **Rust engine** (`core/`): a pure state machine — probes the DNS server, decides when to
  activate/deactivate, with consecutive-failure thresholds, recovery backoff and an optional
  auto-reactivation switch.
- **Local VPN** (`VpnService`): routes only the virtual DNS addresses into a Rust forwarder
  that relays UDP/53 queries to the configured server. All other traffic never touches the VPN.
- **Geofencing**: Play Services wakes the app on ENTER/EXIT transitions of the configured
  area (works with the app closed and the screen off).
- **Fail-safe**: any unmet condition (server down, outside the area, VPN revoked, permissions
  removed) returns the phone to the network DNS. You are never left without DNS.

## Features

- Manual and automatic activation, persistent across reboots.
- Server configuration: IP or hostname, port, UDP/TCP, test domain and timeout, check
  interval and consecutive-failure count.
- Geographic area with "use my current location" and configurable radius.
- Persistent history (Room) of relevant events: server down/recovered, area enter/exit,
  VPN error.
- Live dashboard: state, average RTT, last and next check.
- Warning when strict Private DNS is active (it bypasses the VPN).

## Requirements

- Android 8.0+ (minSdk 26), built with targetSdk 36.
- Permissions: location (including background for geofencing), VPN, notifications.

## Development

Everything builds and tests inside Docker — no need to install toolchains on the host:

```bash
docker/test-core.sh                    # Rust: fmt, clippy -D warnings, cargo test
docker/gradle.sh testDebugUnitTest     # Android: unit tests
docker/build-apk.sh                    # tests + release APK → dist/dnsly.apk
docker/install-apk.sh [ip:port]        # install over USB, or Wi-Fi debugging with an argument
```

Layout:

```
core/       Rust: DNS probe, TUN forwarder, state machine, JNI
android/    Kotlin app + Jetpack Compose (package app.dnsly)
docker/     Image and build/test scripts
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the technical decisions and
[PLAN.md](PLAN.md) for requirements and stages. [AGENTS.md](AGENTS.md) has the repo rules
for agents/contributors, and [CONTRIBUTING.md](CONTRIBUTING.md) covers how to contribute.

## License

MIT — see [LICENSE](LICENSE).
