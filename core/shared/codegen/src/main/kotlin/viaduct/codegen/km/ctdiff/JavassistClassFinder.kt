package viaduct.codegen.km.ctdiff

import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.AttributeInfo
import javassist.bytecode.InnerClassesAttribute
import javassist.bytecode.ParameterAnnotationsAttribute

/**
 * ClassFinder backed by a Javassist ClassPool.
 * Use for Kotlin codegen where bytecode is generated in-memory.
 * Provides access to RuntimeInvisibleAnnotations via bytecode analysis.
 */
class JavassistClassFinder(
    private val classPool: ClassPool,
    private val loader: ClassLoader
) : ClassFinder {
    constructor() : this(ClassPool.getDefault(), ClassLoader.getSystemClassLoader())

    override fun find(className: String): Class<*> = loader.loadClass(className)

    override fun getClassAnnotations(cls: Class<*>): List<String> {
        val ctClass = classPool.get(cls.name)
        // Use classFile2 to get read-only access without defrosting frozen classes
        return ctClass.classFile2.attributes.comparableAnnotations()
    }

    override fun getFieldAnnotations(
        cls: Class<*>,
        fieldName: String
    ): List<String> {
        val ctClass = classPool.getCtClass(cls.name)
        val ctField = ctClass.declaredFields.first { it.name == fieldName }
        return ctField.fieldInfo2.attributes.comparableAnnotations()
    }

    override fun getMethodSignatures(cls: Class<*>): List<MethodInfo> {
        val ctClass = classPool.get(cls.name)
        return ctClass.declaredMethods
            .filterNot { it.name.startsWith("\$jacoco") }
            .map { method ->
                MethodInfo(
                    signature = "${method.name} ${method.genericSignature ?: method.methodInfo2.descriptor}",
                    modifiers = method.modifiers,
                    annotations = method.methodInfo2.attributes.comparableAnnotations()
                )
            }
    }

    override fun getConstructorSignatures(cls: Class<*>): List<ConstructorInfo> {
        val ctClass = classPool.get(cls.name)
        return ctClass.declaredConstructors.map { ctor ->
            ConstructorInfo(
                signature = ctorSig(ctor, ctClass),
                modifiers = ctor.modifiers,
                annotations = ctor.methodInfo2.attributes.comparableAnnotations()
            )
        }
    }

    private fun ctorSig(
        ctor: CtConstructor,
        ctClass: CtClass
    ): String =
        when {
            // Kotlin compiler (like javac) does not generate signature attributes for enum constructors
            ctClass.isEnum -> "${ctor.name} ${ctor.methodInfo2.descriptor}"
            else -> "${ctor.name} ${ctor.genericSignature ?: ctor.methodInfo2.descriptor}"
        }

    private fun List<AttributeInfo>.comparableAnnotations(): List<String> =
        this.flatMap { attribute ->
            when (attribute) {
                is AnnotationsAttribute ->
                    attribute.annotations
                        .filter { it.typeName != "kotlin.Metadata" }
                        .map { "${attribute.name}:$it" }
                is ParameterAnnotationsAttribute ->
                    attribute.annotations.flatMapIndexed { index, paramAnnotations ->
                        paramAnnotations.map { annotation ->
                            "${attribute.name}[$index]:$annotation"
                        }
                    }
                is InnerClassesAttribute ->
                    (0 until attribute.tableLength()).map { i ->
                        listOf(
                            "attr=${attribute.name}",
                            "outer=${attribute.outerClass(i) ?: "<null>"}",
                            "inner=${attribute.innerClass(i) ?: "<null>"}",
                            "name=${attribute.innerName(i)}",
                            "flags=${attribute.accessFlags(i)}"
                        ).joinToString("  ")
                    }.sorted()
                else -> emptyList()
            }
        }
}
