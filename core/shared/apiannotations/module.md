# Module Viaduct Shared Annotation API

This module contains the `viaduct.apiannotations` package. (See the readme for more details)

# Package viaduct.apiannotations

This is the main and only package for the Viaduct Shared Annotation API, containing the annotations that are used to signal the state of the API methods.

API stability annotations: `@StableApi`, `@ExperimentalApi`, `@InternalApi`, `@VisibleForTest`, `@Deprecated`.

Execution-context annotations: `@Attribution(AttributionContext.FRAMEWORK)`, `@Attribution(AttributionContext.TENANT)`, `@Attribution(AttributionContext.INHERIT_CALLER)`. See the README for details.
