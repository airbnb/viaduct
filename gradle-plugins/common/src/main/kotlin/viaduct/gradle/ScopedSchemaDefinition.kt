package viaduct.gradle

import java.io.Serializable

/**
 * Internal wrapper to satisfy Gradle's `MapProperty<K, V>` value-type constraint
 * (which can't take a parameterized value type like `Set<String>` directly).
 * Kept internal so it doesn't pollute the public OSS API.
 */
internal data class ScopedSchemaDefinition(
    val scopeSet: Set<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
