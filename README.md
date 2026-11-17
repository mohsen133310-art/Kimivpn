<div align="center">

# pix

### Private connection. Clear control.

**Pix is an Android VPN client built with Kotlin, Jetpack Compose and the sing-box engine.**

A clean violet-and-white interface, Persian and English support, and tools to manage your own VPN configurations.

</div>

## Features

- VLESS, VMess and Trojan configuration support
- TLS and REALITY security options
- Import configuration links, QR codes, files and subscriptions
- Android VPN Service integration
- Persian right-to-left and English interfaces
- Light, dark and AMOLED themes
- Server selection, diagnostics and connection tools

## Build

- Kotlin
- Jetpack Compose / Material 3
- Android SDK 36
- JDK 17
- sing-box libbox 1.14.4

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

The application ID remains `net.gozar.app` so existing installations can receive updates without changing their Android package identity. The user-facing app and Gradle project name are Pix.

## Engine migration status

The sing-box runtime is integrated into the Android VPN service. Device-level testing of real VLESS, VMess, Trojan and REALITY connections is still required. Some advanced telemetry and routing settings remain in progress; see [SINGBOX_MIGRATION.md](SINGBOX_MIGRATION.md).

## Privacy and security

Pix does not provide VPN servers. Import configurations only from providers you trust. The security and logging practices of the selected VPN endpoint are controlled by that provider.

## License

This repository contains code derived from upstream open-source projects. Preserve all upstream copyright notices and comply with the applicable licenses in the repository, including GPL-3.0 and the licenses of bundled dependencies.
