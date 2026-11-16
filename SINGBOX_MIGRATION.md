# KimiVPN — sing-box migration

The migration is being performed on the `migration/sing-box-core` branch. The Compose UI has been kept unchanged.

## Engine

- sing-box Android library: `com.github.singbox-android:libbox:1.14.4`
- First supported proxy protocols: VLESS, VMess and Trojan
- TLS and REALITY security
- TCP, WebSocket, gRPC, HTTP/2 and HTTPUpgrade transports
- XHTTP and mKCP are intentionally excluded.

## Implemented

- [x] sing-box configuration builder for VLESS, VMess, Trojan and REALITY.
- [x] Initialize `Libbox.setup` from the Android Application.
- [x] Add a `CommandServer` runtime adapter and Android `PlatformInterface`.
- [x] Establish the Android VPN TUN from sing-box `TunOptions`.
- [x] Route the main connect flow, auto-selector reconnect and Quick Settings tile through sing-box.
- [x] Replace the active VPN service's Xray start/stop path with sing-box.
- [x] Remove the legacy `gozarcore.aar` dependency and binary.
- [x] Remove remaining compile-time references to the Xray Java binding.
- [x] Build and unit-test a debug APK successfully on CI (workflow run 22).

## Remaining work / limitations

- [ ] Verify the newest commits with a clean CI build and artifact.
- [ ] Test real VLESS, VMess, Trojan and REALITY connections on Android devices.
- [ ] Restore traffic counters and speed telemetry using sing-box statistics.
- [ ] Map the previous per-app, split-routing, sniffing, fake-DNS and encrypted-DNS settings into sing-box configuration.
- [ ] Reconnect the stability test to sing-box statistics.
- [ ] Complete graceful handling for network changes and service reload callbacks.
- [ ] Produce and verify release APKs for arm64-v8a and armeabi-v7a.

The successful CI build proves compilation and unit tests only; it does not replace device-level connectivity testing. Some features previously supplied by Xray are intentionally not represented as complete until they are mapped to sing-box.
