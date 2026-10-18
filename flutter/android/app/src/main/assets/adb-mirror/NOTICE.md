# scrcpy server

Unmodified official scrcpy-server v5.0.1, copyright Genymobile and contributors.
Licensed under Apache License 2.0; see LICENSE.scrcpy in this directory.

- Source: https://github.com/Genymobile/scrcpy/tree/v5.0.1/server
- Release: https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1
- Artifact: https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1
- Size: 733930 bytes
- SHA-256: 764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536
- Acquisition: 2026-10-14; file digest matched official GitHub release asset metadata.

AShell's Java/Rust/Dart adapters are separate implementations. The bundled server
is not modified. It is pushed through an already authorized, device-local ADB
connection and launched as shell; it is never executed by the build host.
Builds package this asset offline. No desktop scrcpy, adb executable, SDL, or new
native codec library is bundled by this integration. OpenPGP signature verification
and clean-room reproduction of the upstream binary have not been performed.
