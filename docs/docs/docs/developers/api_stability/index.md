---
title: API Stability
description: Stability annotations for Viaduct APIs.
---

Viaduct uses stability annotations to communicate which APIs are ready for broad adoption, which ones are still evolving, and which ones should be treated as implementation detail.

| Annotation | Explanation |
|---|---|
| `@StableApi` | Public API intended for general use and maintained with backward-compatibility guarantees. This is the safest surface to build on. |
| `@ExperimentalApi` | Public API that is still evolving. You can use it, but expect churn and watch upgrade notes closely. |
| `@Deprecated` | API that is being retired. Follow the migration guidance and plan to remove usage. |
| `@InternalApi` | Viaduct-internal API. Avoid depending on it from application or service code. |
| `@TypeInferenceApi` | Framework-internal API used to support generated code and Kotlin type inference. It is not meant to be referenced directly. |
| `@VisibleForTest` | API exposed to support tests and fixtures. It is not intended for normal production use. |

For the detailed rules contributors follow when applying these annotations, see [Contributors: API Stability](../../contributors/api_stability/index.md).
