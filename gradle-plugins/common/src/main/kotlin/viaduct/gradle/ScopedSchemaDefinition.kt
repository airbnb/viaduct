package viaduct.gradle

import java.io.Serializable

/**
 * Internal wrapper carrying a scoped schema's scope set inside a Gradle `MapProperty`.
 *
 * Gradle's `MapProperty<K, V>` cannot take a parameterized value type like `Set<String>` directly,
 * so we wrap it. Kept `internal` so the workaround does not leak into the public Gradle plugin API.
 */
internal data class ScopedSchemaDefinition(
    val scopeSet: Set<String>,
) : Serializable
