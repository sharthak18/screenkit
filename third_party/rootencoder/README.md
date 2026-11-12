# RootEncoder transport subset

Upstream: https://github.com/pedroSG94/RootEncoder
Tag: **2.5.3**, commit `d0257886099c4e8165e5d10252d4d2760f92e6dd`
License: Apache-2.0; see LICENSE.txt and individual source headers.

Only `common/src/main` and `rtmp/src/main` are included (not the encoder, camera,
RTSP, SRT, UDP, UI, or sample applications). This avoids a runtime/download dependency
on JitPack and lets security fixes be reviewed and built from pinned source.
Gradle files and manifests are adapted to ScreenKit's toolchain and minSdk 24.

## ScreenKit changes

- All upstream Android Log calls route to a no-op StreamLog. `setLogs(false)` alone
  only suppresses packet logs; command logs include private stream names/keys.
- `TcpStreamSocketImp` sets TLS `serverName`. Ktor 3.0.0 uses this for SNI **and
  certificate hostname verification**; without it only the certificate chain is checked.
  A strict SAN-based trust wrapper additionally rejects missing-name/IP SANs, which
  Ktor 3.0.0 otherwise accepts. Platform/system trust remains enabled; no trust-all
  manager is installed. Wildcards match exactly one leftmost label.
- Own the raw TCP socket before TLS, ensuring failed/cancelled handshakes can close it.
  A null socket is no longer reported as connected.
- `RtmpClient` starts its send queue before the success callback so the first keyframe
  cannot be discarded by a startup race. It also reports read-side server failures to its connection callback, so the
  capture service shuts down instead of showing a stale LIVE status.
- `UrlParser` preserves raw path/query encoding, preventing percent-escaped stream
  tokens from being decoded or duplicated. Transport supports one app/stream endpoint.

- H.264 packetization splits multi-NAL access units into individual AVCC lengths,
  handling SEI, multiple slices, repeated parameter sets, and three/four-byte start codes.
  Pure-JVM tests cover packet structure and percent-escaped URL tokens.

Keep these patches when upgrading. Changes to transport must preserve the license
and be checked with both a private RTMP receiver and RTMPS platform endpoints.

`common/src/test/resources/localhost-test-only.p12` contains a synthetic self-signed
localhost certificate and test-only private key (password `test-only`), used exclusively
by JVM TLS tests. It is never bundled in the app or installed in a production trust store.
TLS tests cover trusted/matching, trusted/wrong-host, and untrusted-server connections.
