# Serialization follows the upstream dependency contract

The canonical template contains no Kotlinx serialization compiler plugin alias,
library aliases, or version pin. Projects use `serde-kotlin` and
`serde-json-kotlin` when their selected features require serialization or JSON.
The template adds no automatic Serde dependency. Datetime remains an explicit
opt-in: the currently pinned published kotlinx-datetime 0.8.0 declares Kotlinx
serialization core transitively. Its catalog alias/version are retained, while it
is excluded from the default bundle so that indirect serialization is not injected.

Before selecting serialization code, inspect the pinned upstream Cargo.toml and
all relevant workspace member manifests. Resolve workspace-inherited dependencies,
renamed packages, optional/default features, target conditions, and normal/build/dev
scopes. Record exact upstream revision, manifest hashes, selected features and the
Kotlin source sets, public API and tests that need each dependency. Use the existing
`verification/RustParityScanner.java` manifest-index/mapping workflow to inventory this
contract; its declaration report does not establish resolved or behavioral parity.

| Upstream dependency | Kotlin sibling to inspect and verify |
| --- | --- |
| serde | serde-kotlin |
| serde_json | serde-json-kotlin, plus its actual Serde dependency |
| serde_yaml | serde-yaml-kotlin, plus its actual Serde dependency |
| Other formats or codecs | The corresponding Cargo dependency's Kotlin sibling |

Use the corresponding sibling's canonical API and a verified published version.
Check the required features, target variants, artifact payloads and real consumer
behavior. Kotlinx serialization is not an automatic substitute for Serde traits,
derive behavior or a format's wire contract. A matching dependency name, a catalog
alias or a successful source scan does not establish those capabilities.

Declare only dependencies required by the reviewed upstream contract or a documented,
tested Kotlin/platform accommodation. Preserve commonMain inheritance and justified
platform-specific scopes. Use `api` for types exposed in public signatures; keep
implementation details private. Publication guards must check the selected versions
and actual POM target modules without making every private dependency public.

## Existing libraries

Do not bulk-remove existing serialization annotations, generated serializers,
public interfaces or dependencies. Inspect their actual use and upstream mapping.
A replacement must preserve wire-format fixtures, error behavior, public types,
feature combinations and platform behavior, with real downstream consumers rebuilt
for any API/ABI changes. Use RUST_CALLERS.md to identify affected callers. Keep
required functionality until that migration is tested; do not hide or delete APIs
or weaken warnings/tests to bypass it. Shared template synchronization must preserve
explicit project choices and never perform a blanket Kotlinx-to-Serde replacement.
