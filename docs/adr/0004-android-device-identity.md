# ADR 0004: Identify approved Android installations without user login

- Status: Accepted
- Date: 2026-07-16

## Context

Field operators should not enter a shared administrator password on each
phone. Hardware MAC addresses are unavailable or unstable on modern Android,
and Wi-Fi MAC randomization makes them unsuitable as durable identity. A copied
identifier alone must not grant API access.

## Decision

Identity is an administrator-approved application installation:

1. The app computes `SHA-256(namespace || 0x00 || ANDROID_ID || 0x00 ||
   signing_cert_sha256)` locally, where `namespace` is the UTF-8 string
   `hightac.android.installation.identity.v1`, `ANDROID_ID` is used exactly as
   returned without case normalization, and `signing_cert_sha256` is 64
   lowercase ASCII hexadecimal characters. The app sends only the resulting
   lowercase `fingerprint_hash`.
2. The app generates a random 256-bit per-installation secret and sends only
   its SHA-256 hash as `installation_key_hash`.
3. Enrollment returns an opaque, short-lived poll secret once. The server
   stores its hash.
4. An administrator reviews manufacturer, model, app version, and fingerprint,
   assigns a field-facing name, and approves the installation.
   Every fresh, non-idempotent enrollment creates a distinct pending
   installation even when its fingerprint matches an approved device. It
   never inherits approval or rotates the existing device token.
5. Approval makes one opaque bearer token available through the authenticated
   enrollment poll exactly once. The server stores only its Argon2id or keyed
   cryptographic hash.
6. Subsequent REST and WebSocket requests use the bearer token. Revocation
   takes effect on the next authenticated request or socket revalidation.

The installation secret, poll secret, enrollment idempotency key, and bearer
token are encrypted by an AES-GCM key held in Android Keystore. The encrypted
envelopes, not the plaintext values, are stored in SharedPreferences
(`hightac_platform_protected_credentials.xml`). That preferences file and the
legacy `light_station_config.xml` file are excluded from cloud backup and
device-to-device transfer; other non-sensitive app data remains eligible.

Raw `ANDROID_ID`, the per-install key, poll secret, bearer token, and token hash
must not appear in logs, WebSocket events, exports, fixtures, or Git. API
transport is limited to the trusted local network in the first version; TLS is
required before extending the trust boundary.

## Consequences

Phones require no human account, while each installation can be named,
audited, and revoked independently. Reinstalling, clearing app data, changing
the signing certificate, or losing Keystore state changes the installation
identity and requires approval again. In particular, clearing app data or
reinstalling removes or invalidates the Keystore key and protected
SharedPreferences state, so the app creates new installation credentials and
must be approved again.

`ANDROID_ID` is not treated as a secret or as sole authentication. The scheme
does not prove a physical handset identity and does not survive every reset;
administrator approval and possession of the issued bearer token are the
authorization controls.

## Alternatives considered

- Hardware or Wi-Fi MAC identity was rejected as inaccessible and unstable.
- A shared Android username/password was rejected because it prevents
  per-device revocation and attribution.
- Shipping MQTT credentials to Android was rejected because it bypasses the
  backend authorization and audit boundary.
