# Release license inputs

This directory contains notes and placeholders only. It must never contain
downloaded vendor binaries or an unapproved product license.

`New-ReleasePayload.ps1` requires these external, reviewed build inputs:

- An approved HighTac product EULA/license text, if the release owner requires an
  installer acceptance page. No product terms are invented when this is omitted.
- The WinSW license text shipped with the exact WinSW binary being packaged.
- The Microsoft Visual C++ x64 redistributable. The build verifies its Microsoft
  signature and records exact version/hash provenance; it does not fabricate a
  Microsoft license or a redistribution-rights assertion.
- Eclipse Mosquitto's `epl-v20`, `edl-v10`, and `NOTICE.md` files from the exact
  official Windows distribution being packaged.

The release payload copies those files into its `licenses` directory and
generates `THIRD-PARTY-NOTICES.md`. The Inno Setup build fails if the product
license or required third-party notices are absent.
