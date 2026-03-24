package viaduct.utils.invariants

import kotlin.reflect.KClass

/**
 * This checks to see if there exists a field, map value, or collection element that matches a given type.
 * It checks if *one exists*, not if *all* of them match the type.  Thus far, we do not need a check for all.
 */
class FieldTypeInvariant : ClassTypeInvariant {
    private val subinvariants: List<ClassTypeInvariant>

    constructor(klass: KClass<*>, subinvariants: List<ClassTypeInvariant> = emptyList()) : super(klass) {
        this.subinvariants = subinvariants
    }

    constructor(typeInfo: TypeInfo, subinvariants: List<ClassTypeInvariant> = emptyList()) : super(typeInfo) {
        this.subinvariants = subinvariants
    }

    override fun check(instance: Any) {
        super.check(instance)

        for (invariant in subinvariants) {
            val passed = when (instance) {
                is Collection<*> -> instance.any {
                    it != null &&
                        isType(invariant.klass, invariant.typeInfo, it) &&
                        runCatching { invariant.check(it) }.isSuccess
                }
                else -> {
                    val value = getValue(instance, invariant.klass, invariant.typeInfo)
                    value != null && runCatching { invariant.check(value) }.isSuccess
                }
            }
            if (!passed) {
                throw InvariantException(
                    klass?.simpleName ?: typeInfo?.type.toString(),
                    invariant.klass?.simpleName ?: invariant.typeInfo?.type.toString(),
                    NullPointerException("No value found that passes subinvariant")
                )
            }
        }
    }

    private fun getValue(
        instance: Any,
        klass: KClass<*>?,
        typeInfo: TypeInfo?
    ): Any? {
        // Use performTypeCheck to find a matching field, map value, or collection element
        // This is a simplified example. You might need to adjust it based on your actual requirements.
        when (instance) {
            is Collection<*> -> return instance.find { isType(klass, typeInfo, it) }
            is Map<*, *> -> return instance.values.find { isType(klass, typeInfo, it) }
            else -> {
                val javaClass = instance::class.java
                javaClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    val value = field.get(instance)
                    try {
                        if (isType(klass, typeInfo, value)) return value
                    } catch (e: IllegalArgumentException) {
                        // This catch block is necessary because performTypeCheck now throws an exception
                        // when the check fails. You might want to handle this differently.
                    }
                }
            }
        }
        return null
    }
}
