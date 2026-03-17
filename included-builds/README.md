# Included Builds

This directory contains Viaduct's core library modules, structured as a Gradle *included build* rather than direct subprojects of the root build.

## Why `core` must be an included build

Gradle distinguishes between the root build's own subprojects (`RootBuildState`) and subprojects of a proper included build (`IncludedBuildState`). The key difference is in **plugin-classpath resolution**: explicit `dependencySubstitution` rules declared in `includeBuild(".")` do not propagate into the plugin-classpath resolution context, whereas substitutions from a proper included build do.

This matters because the demoapps declare `gradle-plugins` in their `pluginManagement` block. For Gradle to compile `application-plugin` as part of that plugin classpath, it must resolve `application-plugin`'s compile-time dependencies (`tenant-codegen`, `shared-graphql`, and other core libraries) against local projects rather than Maven. That substitution only works when those libraries come from a proper included build (`IncludedBuildState`). If the core modules were root subprojects instead, the substitution would silently fail for plugin-classpath resolution, even if explicit `includeBuild(".")` rules were present.
