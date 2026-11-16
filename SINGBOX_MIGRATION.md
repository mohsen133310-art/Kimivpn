# KimiVPN — sing-box migration

This branch is the isolated migration branch. The app UI and Compose screens are intentionally left unchanged.

## Target engine

- sing-box Android library: `com.github.singbox-android:libbox:1.14.4`
- Supported proxy protocols in the first migration scope: VLESS, VMess, Trojan
- Security: TLS and REALITY
- Transports retained: TCP, WebSocket, gRPC, HTTP/2 and HTTPUpgrade
- XHTTP and mKCP are intentionally excluded.

## Current state

- [x] Add the sing-box configuration builder for the supported proxy types.
- [x] Add JitPack repository and libbox dependency.
- [x] Add a GitHub Actions Android build and unit-test workflow.
- [ ] Port the Android `PlatformInterface` implementation and TUN lifecycle to libbox (required before runtime switch).
- [ ] Replace Xray start/stop/stats calls in `GozarVpnService`.
- [ ] Map existing app settings (per-app routing, kill switch, DNS, split routing and traffic counters) to sing-box.
- [ ] Remove `gozarcore.aar` after the sing-box service passes device tests.
- [ ] Produce and verify arm64-v8a and armeabi-v7a release APKs.

**Important:** The Xray runtime is intentionally still present while the service adapter is being ported. This avoids shipping a build that appears connected but does not carry traffic. The builder and dependency alone do not switch the running VPN engine.
