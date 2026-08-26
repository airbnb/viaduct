package viaduct.utils.collections

import kotlin.reflect.KClass
import kotlin.reflect.typeOf

/**
 * An immutable collection of typed, nullable values associated with a
 * containing object.
 *
 * Values are retrieved using identity-based [Key] instances. Keys provide one
 * level of runtime type safety: parameterized type arguments are not checked
 * because of type erasure. Use a wrapper class if more granular type safety is
 * needed.
 */
interface HMap {
    /**
     * Returns the value associated with [key], which may be null.
     *
     * @throws NoSuchElementException if [key] is not present.
     */
    operator fun <T> get(key: Key<T>): T

    /**
     * Returns whether [key] is present without retrieving its possibly-null value.
     *
     * Implementations should override this when they can test presence directly.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun contains(key: Key<*>): Boolean =
        try {
            get(key as Key<Any?>)
            true
        } catch (_: NoSuchElementException) {
            false
        }

    /**
     * Identifies a value of type [T] held by a [HMap].
     *
     * Each instance of this class is a unique key even when the name and type
     * are the same. The [name] is used only for diagnostics.
     *
     * Runtime validation checks only [klass]. Type arguments of parameterized
     * types are not checked.
     */
    class Key<T>
        @PublishedApi
        internal constructor(
            val name: String,
            val klass: KClass<T & Any>,
            val isMarkedNullable: Boolean
        ) {
            override fun toString(): String {
                val identity = super.toString().substringAfterLast('$')
                val typeName = klass.qualifiedName ?: klass.toString()
                val nullableSuffix = if (isMarkedNullable) "?" else ""
                return "$identity<$typeName$nullableSuffix>($name)"
            }

            companion object {
                val DEFAULT: Key<Any?> = of("DEFAULT")

                @Suppress("UNCHECKED_CAST")
                inline fun <reified T> of(name: String): Key<T> {
                    return Key(
                        name,
                        T::class as KClass<T & Any>,
                        typeOf<T>().isMarkedNullable
                    )
                }
            }
        }

    /**
     * Builds a [HMap] while preserving the type relationship between each
     * [Key] and its value.
     */
    class Builder {
        private var values = mutableMapOf<Key<*>, Any?>()

        /**
         * Associates [value] with [key].
         *
         * Runtime validation checks the outer [Key.klass] and nullability.
         * Type arguments of parameterized values are checked only at compile
         * time.
         *
         * @throws IllegalArgumentException if a value does not match the
         * runtime type or nullability declared by its key.
         */
        fun <T> put(
            key: Key<T>,
            value: T
        ): Builder {
            if (value == null) {
                require(key.isMarkedNullable) {
                    "$key: Unexpected null"
                }
            } else {
                require(key.klass.isInstance(value)) {
                    "$key: Unexpected type ${value.javaClass.kotlin}"
                }
            }
            values[key] = value
            return this
        }

        /** Returns a holder containing this builder's current values. */
        fun build(): HMap {
            val builtValues = values
            values = mutableMapOf()
            return object : HMap {
                private val values = builtValues

                override fun contains(key: Key<*>): Boolean = key in values

                @Suppress("UNCHECKED_CAST")
                override fun <T> get(key: Key<T>): T {
                    if (!this.values.containsKey(key)) {
                        throw NoSuchElementException("No value for '$key'")
                    }
                    return this.values[key] as T
                }
            }
        }
    }

    companion object {
        /** Creates a [HMap] containing [value] at [Key.DEFAULT]. */
        fun singleton(value: Any?): HMap =
            object : HMap {
                override fun contains(key: Key<*>): Boolean = key === Key.DEFAULT

                @Suppress("UNCHECKED_CAST")
                override fun <T> get(key: Key<T>): T {
                    if (key !== Key.DEFAULT) {
                        throw NoSuchElementException("No value for '$key'")
                    }
                    return value as T
                }
            }
    }
}
