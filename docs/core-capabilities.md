# Embedded core capabilities

`Androidcore.version()` returns build-info JSON; About displays its `version` string.
`Androidcore.capabilities()` returns Android embedded-backend JSON. Boolean fields
are accepted only as JSON booleans: missing, malformed and wrong-type values remain
unknown, distinct from false. System IKEv2 uses `ikev2.supported`. No protocol
support is inferred from version numbers. These describe build support, not server
availability or permissions.

The host installs a dual-stack TUN, so session creation requires `platform` exactly
`android` and `vpn`, `ipv4`, `ipv6` explicitly true. A missing Java/JNI capability
method produces unknown capabilities and fails closed through localized ProfileError.
Other errors propagate. The existing lifecycle cleanup remains responsible for failures.

JVM tests cover the gate and linkage fallback. Instrumentation calls the installed
AAR directly and tests JSON parsing without starting VPN or modifying profiles.
Repeat runtime validation whenever the AAR changes.
