# ScreenKit 2.2.0 DNS filtering (experimental)

## Scope and consent

Explicit in-app disclosure precedes `VpnService.prepare`. Preference defaults off. After
agreement/OS approval it starts with `FloatingOverlayService`, not with boot or a hidden
background task. The bubble can stay up over other apps; ordinary device DNS is filtered
then, not just ScreenKit's DNS. Closing the bubble removes the VPN route; the remembered
preference allows the next bubble session to start it again. Turning off via card or
notification disables that preference. Other VPNs/strict Private DNS are not silently
replaced or reconfigured. Revocation disables automatic filtering until another opt-in.

Always-on is explicitly unsupported in the manifest. Do not use Android VPN lockdown:
this split-route tool is not a privacy VPN, and lockdown conflicts with bypass traffic
and fail-open behavior. It does not conceal IP/location or encrypt general app traffic.

## Data path

- TUN address 192.0.2.1/32, DNS 192.0.2.2/32, **only** 192.0.2.2/32 routed. IPv6 family
  explicitly allowed outside the tunnel; no default IPv4 or IPv6 route is installed.
- UDP DNS plus a bounded TCP DNS endpoint for large/TC responses. IPv4 headers and
  UDP/TCP checksums validated; fragments dropped. EDNS replies capped to 1232 bytes
  (512 without EDNS); larger replies set TC and rely on TCP. Ordinary stub questions
  only: one question, no compressed question name, max query 4096 bytes. These are
  deliberate experimental compatibility limits, not a full general-purpose IP stack.
- TCP: 32 connections, 8 pending queries per connection, 8KiB inbound window, 96KiB
  queued/unacknowledged output limit, 1200-byte segments, ACKs, flow control, bounded
  retransmission, FIN/RST, 30s idle eviction. No TCP options beyond advertised MSS.
  The endpoint rejects port 853 probes; it never pretends to terminate app TLS.
- Bundled `app/src/main/assets/ad-domains.txt`: small hand-maintained starter list of
  advertising endpoints, not an imported/subscription list. Case-insensitive exact
  domain/suffix boundaries, not substring matching. Returns NXDOMAIN, no fake DNSSEC AD.
  No inspection of CNAME targets in allowed replies: CNAME-only aliases can evade it.
- Allowed DNS messages use protected sockets to Cloudflare 1.1.1.1 (or
  2606:4700:4700::1111 on IPv6-only networks), TCP 853, TLS SNI/hostname cloudflare-dns.com.
  System certificate trust and HTTPS endpoint identification enforced. No cleartext
  fallback, no user-installed trust-all verifier, no general app TLS interception.
- Cloudflare receives unblocked query names and public IP. ScreenKit retains no query
  history/names, saves only enable preference, and maintains aggregate session counters.
  Small DNS/TCP buffers and outstanding requests exist in memory until reply/cleanup.
- Four resolver workers, 32 queued tasks, per-socket timeouts and 8s absolute lookup
  deadline. A fresh TLS connection per lookup favors simple cleanup over efficiency;
  latency/battery overhead needs device measurement. No application-level DNS cache.
- Saturation returns SERVFAIL; three consecutive transport/invalid-reply failures stop
  filtering and remove the route (fail open). Ordinary networking returns, so ads may
  return too. A single transient error returns SERVFAIL without ending the VPN. Explicit
  retry/reopening controls can start another session; no watchdog restart loop.
- Nonblocking TUN reads via bounded poll; serialized nonblocking output, with packet loss
  under backpressure handled by DNS/TCP retransmission. Closing releases descriptor,
  sockets, queued work and transport state; no abandoned blocking TUN reader.

## Automated coverage

Pure Kotlin tests: suffix boundary/case/trailing dot, malformed lists/questions, A/AAAA/
HTTPS questions, ID/type/question validation, NXDOMAIN header flags, EDNS bounds, IPv4/
UDP/TCP checksums and malformed/fragments rejection, UDP truncation to TCP fallback,
queue saturation, TCP segmented query/response/duplicate/FIN, handshake retransmission,
port 853 RST, invalid length reset, shutdown callbacks, and deterministic malformed input.

These tests do **not** exercise Android's VPN routing, platform TLS, OEM lifecycle, or a
live social app. Existing capture/automation/streaming tests remain unchanged.

## Required physical-device matrix (NOT run in the sandbox)

Android 7/10/13/14/15; Wi-Fi and mobile data; dual-stack and IPv6-only/NAT64:

1. Enable/deny/cancel VPN consent and notification permission; rotate during disclosure
   and OS consent. No network route before consent, no blank consent screen after rotation.
2. After opt-in: open bubble, background app, verify persistent VPN/notification and status;
   close bubble, reopen, Turn off, force-stop, revoke VPN, process restart. Confirm descriptor,
   worker and socket teardown and network restoration. No disabled preference auto-restart.
3. Other VPN active: no silent replacement. Starting another VPN revokes this one. Strict
   Private DNS active/changed mid-session: stop with a clear message, not a black-hole DNS route.
4. Controlled test page: blocked host and subdomain return NXDOMAIN; lookalike suffix and
   normal content hosts resolve. Confirm A, AAAA, HTTPS, large DNS responses/TCP fallback,
   TCP segmentation/retransmission/window close/reopen, and allowed live streaming.
5. Capture underlying traffic: only DNS routed locally, upstream DNS encrypted to named
   provider, no plaintext fallback; ordinary IPv4/IPv6 video/Web/Go Live unaffected. A
   mismatched/untrusted/expired TLS certificate must fail, never resolve over plaintext.
6. Switch Wi-Fi/mobile; captive portal; airplane mode; block outbound 853; resolver outage;
   burst queries/large replies. Failure must remove the tunnel and visibly report filtering
   off. Retry after connectivity returns. Measure initial DNS latency, battery, memory.
7. Verify expected bypasses: app DoH/custom resolver, cached ads, same-host video ads,
   CNAME-only alias, IPv6 custom DNS. Never display a claim that all ads were removed.
8. Site false positive: Turn off restores normal DNS (device/app caches may require retry).
   Confirm zero stored DNS logs or domain-bearing diagnostics after a session.

Do not market as production-tested before these checks. No signed store release or
Google Play VPN/foreground-service declarations have been prepared.
