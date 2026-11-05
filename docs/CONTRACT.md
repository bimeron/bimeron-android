# Android contract

The embedded core artifact and its source revision are documented in [CORE-AAR.md](CORE-AAR.md). This document describes how the app uses that artifact.

- Every successful save passes local document validation and `ProfileStore.validateCore`: `Androidcore.newSession` followed by `stop`, without `setNetwork` or `start`. Invalid local documents can fail before reaching the adapter. Each disabled rule is also temporarily enabled for core validation. The current save path rejects proxy mode and `KillSwitch=true`.
- Existing unsupported profiles that pass local `ConfigDocument` validation can be loaded and corrected in Source config. This does not promise loading arbitrary invalid profiles. Failed validation leaves the previous private file intact; valid saves sync a temporary file before rename.
- Profile Name is optional in the core format. Import supplies a filename fallback in the private copy; the original document is unchanged. Android requires a valid display name.
- The document editor preserves unrepresented fields, comments and sections. Preservation does not guarantee acceptance: core schema validation rejects unknown fields. See [PROFILE-EDITOR.md](PROFILE-EDITOR.md) for source/form synchronization and disabled-rule envelopes.
- Android uses the generated Java AAR API and `VpnService`; desktop RPC instructions are not Android integration instructions.
- Starting a session is not proof of connection. `BimeronVpnService` polls status and reports connected only after the core reports `connected`.
- CIDR rules require an explicit prefix, including `/32` for a single IPv4 address or `/128` for a single IPv6 address. Android validation and the audited routing compiler require prefixes.
- Core kill switch and Android per-app routing are not promised by this integration. OS always-on/lockdown settings are separate from profile fields.
- Proxy profiles cannot be saved without adapter validation.
- Process (per-app) routing is not a profile feature: the rule model exposes CIDR and domain rules only. GeoIP/GeoSite datasets are stored privately by `GeoDataStore` (see [ARCHITECTURE.md](ARCHITECTURE.md)) but are not yet passed to the core session.
- IKEv2 is not a supported Android connection path; the Android adapter rejects it.
