# ADR 0006: Bound Android cleartext platform endpoints to the trusted LAN

- Status: Accepted
- Date: 2026-07-16

## Context

The Android client supports a runtime-configured HighTac Platform address. The
first deployment serves HTTP from a trusted site LAN, and the server address
can change with the site's private subnet. Android network security XML can
allow fixed domain names, but it cannot express RFC 1918 ranges, IPv6
unique-local ranges, or a host selected at runtime. Listing the initial
`192.168.1.105` address therefore made other valid LAN configurations fail at
the Android transport layer after application validation had accepted them.

## Decision

The Android network security configuration permits cleartext transport as a
capability. `PlatformUrlValidator` is the mandatory policy boundary for the
stored and active HighTac Platform endpoint:

- HTTPS is supported for non-loopback routable hosts.
- HTTP is limited to RFC 1918 IPv4, IPv4/IPv6 link-local, IPv6 unique-local,
  single-label local DNS names, and the `.local`, `.lan`, `.internal`, and
  `.home.arpa` local suffixes.
- Loopback, unspecified, Android-emulator-only, credentials, paths, queries,
  and fragments remain rejected.
- Public or otherwise routable cleartext hosts are rejected and must use
  HTTPS.

Both configuration writes and configuration reads pass through the validator,
so an invalid persisted value falls back to the validated site default.

## Consequences

Deployments can move to another trusted private subnet without rebuilding the
APK or editing XML. The XML setting is intentionally broader than the
application endpoint policy because Android has no subnet-aware equivalent;
new Android network clients must not treat it as permission to introduce
arbitrary public HTTP traffic. Deployments outside a trusted LAN must
terminate TLS and configure an HTTPS endpoint.

## Alternatives considered

- Enumerating private addresses in XML was rejected because network security
  configuration accepts domain entries, not CIDR ranges.
- Keeping only the initial fixed address was rejected because it contradicted
  the runtime endpoint setting and failed after a legitimate LAN address
  change.
- Allowing arbitrary HTTP URLs was rejected because it would expose device
  credentials and operational data outside the trusted-LAN boundary.
