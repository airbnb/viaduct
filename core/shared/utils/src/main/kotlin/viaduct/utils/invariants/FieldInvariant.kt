package viaduct.utils.invariants

import kotlin.reflect.KClass

/**
 * This is for matching field invariants based upon property name or map key in addition to class type of those fields.
 */
class FieldInvariant : ClassTypeInvariant {
    private val subinvariants: Map<String, ClassTypeInvariant>

    constructor(klass: KClass<*>, subinvariants: Map<String, ClassTypeInvariant> = emptyMap()) : super(klass) {
        this.subinvariants = subinvariants
    }

    constructor(typeInfo: TypeInfo, subinvariants: Map<String, ClassTypeInvariant> = emptyMap()) : super(typeInfo) {
        this.subinvariants = subinvariants
    }

    override fun check(instance: Any) {
        super.check(instance)

        // Iterate over each subinvariant
        for ((fieldName, invariant) in subinvariants) {
            val fieldValue = getFieldValue(instance, fieldName)
                ?: throw InvariantException(klass?.simpleName ?: typeInfo?.type.toString(), fieldName, NullPointerException("Required field is null"))

            // Recursively check invariants on the field value if it is not null
            fieldValue.let {
                invariant.check(it)
            }
        }
    }

    private fun getFieldValue(
        instance: Any,
        fieldName: String
    ): Any? {
        return when {
            klass == Map::class || typeInfo?.type == Map::class -> {
                val mapInstance = instance as Map<*, *>
                mapInstance[fieldName]
            }
            else -> {
                val actualInstance = try {
                    (instance as Lazy<*>).value
                } catch (e: ClassCastException) {
                    instance
                }

                if (actualInstance == null) {
                    return null
                }
                val javaClass = actualInstance::class.java
                val field = try {
                    javaClass.getDeclaredField(fieldName)
                } catch (e: Exception) {
                    when (e) {
                        is NoSuchFieldException, is SecurityException -> throw InvariantException(klass?.simpleName ?: typeInfo?.type.toString(), fieldName, e)
                        else -> throw e
                    }
                }
                field.isAccessible = true
                field.get(actualInstance)
            }
        }
    }
}
