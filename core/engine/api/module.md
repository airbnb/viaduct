# Module api

This is the Viaduct Engine API, which is used to build and run Viaduct modules.

# Package viaduct.engine.api

Contains the core interfaces and classes for building Viaduct modules.

# Package viaduct.engine.api.context

Contains local context that stores the dispatcher to be used for the current GraphQL execution.

# Package viaduct.engine.api.coroutines

Contains coroutine utilities for Java/Kotlin interoperability.

# Package viaduct.engine.api.derived

Contains utilities for defining derived fields in Viaduct modules.

# Package viaduct.engine.api.execution

Contains interfaces for classifying and reporting errors within resolvers.

# Package viaduct.engine.api.fragment

Contains interfaces and classes for defining and working with GraphQL fragments in Viaduct modules.

# Package viaduct.engine.api.fragment.errors

Contains error types related to fragment resolution in Viaduct resolvers.

# Package viaduct.engine.api.instrumentation

Contains interfaces and classes for instrumenting the execution of GraphQL queries in Viaduct modules.

# Package viaduct.engine.api.observability

Contains classes that provide information about the execution that can be used by instrumentations.

# Package viaduct.engine.api.parse

Contains utilities for parsing text into GraphQL queries.

# Package viaduct.engine.api.select

Contains utilities parsing GraphQL selections.

# Package viaduct.engine.runtime

Contains `QueryPlanExecutionCondition`, a runtime-specific value type co-located in the engine api module.

# Package viaduct.engine.runtime.dfe

Contains `ViaductDataFetchingEnvironment` — Viaduct's bridge between GraphQL-Java's `DataFetchingEnvironment` and Viaduct's `EngineExecutionContext`, plus the `engineExecutionContext` and `requireViaductDataFetchingEnvironment` extension functions. This package lives under `engine/api/` (not `engine/runtime/`) so its Bazel target can use `associates` to access internal members of `viaduct.engine.api` without creating a compilation cycle.
