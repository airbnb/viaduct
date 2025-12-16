package viaduct.utils.invariants

import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class TypeInfo(
    val type: KType
) {
    fun checkType(instance: Any) {
        try {
            val classifierKClass = type.classifier as? KClass<*>

            // Directly check non-generic types or types without type arguments
            if (type.arguments.isEmpty()) {
                classifierKClass?.let {
                    if (it.isInstance(instance)) {
                        // The instance matches the expected type directly or is a subclass thereof
                        return
                    } else {
                        throw IllegalArgumentException("Type does not match, found ${instance::class}, expected ${type.classifier}")
                    }
                }
            }

            // Check if the instance is a Collection (or subclass thereof)
            if (Collection::class.java.isAssignableFrom(instance::class.java)) {
                val elementType = type.arguments
                    .first()
                    .type
                    ?.classifier
                (instance as? Collection<*>)?.forEach { element ->
                    if (element != null && !checkElementAgainstTypeClassifier(element, elementType)) {
                        throw IllegalArgumentException("Collection element type does not match")
                    }
                }
                return
            }

            // Check if the instance is a Map (or subclass thereof)
            if (Map::class.java.isAssignableFrom(instance::class.java)) {
                val keyType = type.arguments[0].type?.classifier
                val valueType = type.arguments[1].type?.classifier
                (instance as? Map<*, *>)?.entries?.forEach { (key, value) ->
                    if (key != null && !checkElementAgainstTypeClassifier(key, keyType)) {
                        throw IllegalArgumentException("Map key type does not match")
                    }
                    if (value != null && !checkElementAgainstTypeClassifier(value, valueType)) {
                        throw IllegalArgumentException("Map value type does not match")
                    }
                }
                return
            }

            // Check if the instance is a Lazy (or subclass thereof)
            if (Lazy::class.java.isAssignableFrom(instance::class.java)) {
                val valueType = type.arguments
                    .first()
                    .type
                    ?.classifier
                val lazyValue = (instance as? Lazy<*>)?.value
                if (lazyValue != null && !checkElementAgainstTypeClassifier(lazyValue, valueType)) {
                    throw IllegalArgumentException("Lazy value type does not match")
                }
                return
            }

            // If none of the above conditions match, throw an exception
            throw IllegalArgumentException("Unsupported type or does not match")
        } catch (e: IllegalArgumentException) {
            throw InvariantException(type.toString(), "self", e)
        }
    }

    private fun checkElementAgainstTypeClassifier(
        element: Any,
        classifier: KClassifier?
    ): Boolean {
        // Cast the classifier to KClass, as we need to work with KClass to use isSuperclassOf and isInstance
        val classifierKClass = classifier as? KClass<*>
        return classifierKClass?.isInstance(element) ?: false
    }
}

inline fun <reified T> typeInfo(): TypeInfo = TypeInfo(typeOf<T>())

open class ClassTypeInvariant private constructor(
    val klass: KClass<*>? = null,
    val typeInfo: TypeInfo? = null
) {
    constructor(typeInfo: TypeInfo) : this(null, typeInfo)
    constructor(klass: KClass<*>) : this(klass, null)

    private fun performTypeCheck(
        klass: KClass<*>?,
        typeInfo: TypeInfo?,
        instance: Any
    ) {
        klass?.let {
            if (!it.isInstance(instance)) {
                throw InvariantException(
                    it.simpleName ?: "Unknown",
                    "self",
                    IllegalArgumentException("Instance is not of the expected class, found ${instance::class.simpleName}, expected ${it.simpleName}")
                )
            }
        }
        typeInfo?.checkType(instance)
    }

    protected fun isType(
        klass: KClass<*>?,
        typeInfo: TypeInfo?,
        instance: Any?
    ): Boolean {
        if (instance == null) {
            return false
        }
        try {
            performTypeCheck(klass, typeInfo, instance)
            return true
        } catch (e: InvariantException) {
            return false
        }
    }

    open fun check(instance: Any) {
        performTypeCheck(klass, typeInfo, instance)

        // If instance implements HasInvariantAssertion, invoke assertInvariants
        if (instance is HasInvariantAssertion) {
            try {
                instance.assertInvariants()
            } catch (e: Exception) {
                throw InvariantException(typeInfo?.type.toString(), "self", e)
            }
        }
    }
}

interface HasInvariantAssertion {
    fun assertInvariants()
}
