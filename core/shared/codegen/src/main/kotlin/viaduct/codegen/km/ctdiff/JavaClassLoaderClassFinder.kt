package viaduct.codegen.km.ctdiff

import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * ClassFinder using Java reflection only.
 * Use for Java codegen where classes are compiled to disk.
 *
 * This implementation has NO Javassist dependency.
 * Note: Cannot access RuntimeInvisibleAnnotations (only RuntimeVisibleAnnotations via reflection).
 */
class JavaClassLoaderClassFinder(
    private val loader: ClassLoader
) : ClassFinder {
    override fun find(className: String): Class<*> = loader.loadClass(className)

    override fun getClassAnnotations(cls: Class<*>): List<String> =
        cls.annotations
            .filter { it.annotationClass.qualifiedName != "kotlin.Metadata" }
            .map { "RuntimeVisibleAnnotations:$it" }

    override fun getFieldAnnotations(
        cls: Class<*>,
        fieldName: String
    ): List<String> {
        val field = cls.getDeclaredField(fieldName)
        return field.annotations.map { "RuntimeVisibleAnnotations:$it" }
    }

    override fun getMethodSignatures(cls: Class<*>): List<MethodInfo> =
        cls.declaredMethods
            .filterNot { it.name.startsWith("\$jacoco") }
            .map { method ->
                MethodInfo(
                    signature = methodSig(method),
                    modifiers = method.modifiers,
                    annotations = method.annotations.map { "RuntimeVisibleAnnotations:$it" }
                )
            }

    override fun getConstructorSignatures(cls: Class<*>): List<ConstructorInfo> =
        cls.declaredConstructors.map { ctor ->
            ConstructorInfo(
                signature = ctorSig(ctor),
                modifiers = ctor.modifiers,
                annotations = ctor.annotations.map { "RuntimeVisibleAnnotations:$it" }
            )
        }

    private fun methodSig(method: Method): String {
        val params = method.parameterTypes.joinToString(",") { typeToDescriptor(it) }
        return "${method.name} ($params)${typeToDescriptor(method.returnType)}"
    }

    private fun ctorSig(ctor: Constructor<*>): String {
        val params = ctor.parameterTypes.joinToString("") { typeToDescriptor(it) }
        return "<init> ($params)V"
    }

    /**
     * Converts a Java type to its JVM descriptor format.
     * This matches the format used by Javassist's method descriptors.
     */
    private fun typeToDescriptor(type: Class<*>): String =
        when {
            type == Void.TYPE -> "V"
            type == Boolean::class.javaPrimitiveType -> "Z"
            type == Byte::class.javaPrimitiveType -> "B"
            type == Char::class.javaPrimitiveType -> "C"
            type == Short::class.javaPrimitiveType -> "S"
            type == Int::class.javaPrimitiveType -> "I"
            type == Long::class.javaPrimitiveType -> "J"
            type == Float::class.javaPrimitiveType -> "F"
            type == Double::class.javaPrimitiveType -> "D"
            type.isArray -> "[${typeToDescriptor(type.componentType)}"
            else -> "L${type.name.replace('.', '/')};"
        }

    companion object {
        fun fromSystemClassLoader() = JavaClassLoaderClassFinder(ClassLoader.getSystemClassLoader())
    }
}
