package viaduct.engine.api.fragment

import kotlin.reflect.KProperty
import kotlin.reflect.full.isSubclassOf

/**
 * Class holding variables for use in the fragment.
 *
 * Use with the Kotlin delegate pattern:
 *
 *     override fun getFragment(vars: FragmentVariables): Fragment {
 *         val first : FragmentVariable<Int?> by vars
 *         val last : FragmentVariable<Int?> by vars
 *
 * Alternatively you can use the get operator (needed if you want to set a default value):
 *
 *     override fun getFragment(vars: FragmentVariables): FragmentDefinition {
 *         val first : FragmentVariable<Int?> = vars["first", 10]
 *         val last : FragmentVariable<Int?> = vars["last"]
 *
 * For enums use:
 *     val productType : FragmentVariable<WishlistProductType?> = vars.getEnum("productType")
 *
 * Important note: Default values are not used if the argument exists and has specified "null" value.
 * If you want to use a default value make the caller not specify the variable at all.
 */
class FragmentVariables {
    val innerMap: MutableMap<String, FragmentVariable<*>>

    private constructor(map: Map<String, Any?>) {
        innerMap =
            map.map { (key, value) ->
                key to FragmentVariable(key, value)
            }.toMap().toMutableMap()
    }

    private constructor(fragmentVars: List<FragmentVariable<*>>) {
        this.innerMap = fragmentVars.associateBy { it.name }.toMutableMap()
    }

    inline operator fun <reified T> get(
        varName: String,
        default: T? = null
    ): FragmentVariable<T?> {
        @Suppress("UNCHECKED_CAST")
        val value =
            innerMap[varName] as? FragmentVariable<T?>
                ?: FragmentVariable(
                    varName,
                    default
                )
        innerMap[varName] = value
        return value
    }

    // TODO(jan.spidlen): Solve the delegate for enums. This is a quick hack to get unblocked.
    inline fun <reified E : Enum<E>> getEnum(
        varName: String,
        default: E? = null
    ): FragmentVariable<E?> {
        @Suppress("UNCHECKED_CAST")
        val retrievedValueAsString =
            innerMap[varName] as? FragmentVariable<String?>
                ?: FragmentVariable(
                    varName,
                    default?.toString()
                )
        val validatedValue = retrievedValueAsString.validateEnum<E>()
        innerMap[varName] = validatedValue
        return validatedValue
    }

    /**
     * Delegate getter implementation.
     */
    inline operator fun <reified T> getValue(
        thisRef: Any?,
        property: KProperty<*>
    ): FragmentVariable<T?> {
        require(!T::class.isSubclassOf(Enum::class)) {
            "Don't use this method for enums: ${T::class}"
        }
        return this[property.name]
    }

    fun asMap(): Map<String, Any?> =
        innerMap.map { (key, value) ->
            key to value.value
        }.toMap()

    companion object {
        fun fromMap(map: Map<String, Any?>) = FragmentVariables(map)

        fun fromVariables(vararg vars: FragmentVariable<*>) = FragmentVariables(vars.toList())

        val EMPTY: FragmentVariables
            get() = fromMap(mapOf())
    }
}

/**
 * This is effectively just a holder for the actual variable value. The variable can be null as well.
 *
 * The {@code toString()} method returns the string representation of the value or "null".
 */
class FragmentVariable<T>(val name: String, val value: T) {
    override fun toString(): String {
        return "${"$"}$name"
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Enum<E>> validateEnum(): FragmentVariable<E?> {
        value ?: return this as FragmentVariable<E?>
        if (value is E) {
            return this as FragmentVariable<E?>
        }

        val enumVal = enumValueOf<E>(value.toString())
        return FragmentVariable(name, enumVal)
    }
}
