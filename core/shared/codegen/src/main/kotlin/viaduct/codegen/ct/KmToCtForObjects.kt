package viaduct.codegen.ct

import javassist.CtClass
import javassist.CtField
import javassist.bytecode.AnnotationsAttribute
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.Visibility
import kotlinx.metadata.visibility
import viaduct.codegen.utils.Km

/**
 * Given this kotlin:
 * ```
 *   object AnObject {
 *     val field = 1
 *   }
 * ```
 *
 * kotlinc generates a class file for AnObject that is equivalent to this java definition:
 *
 * ```
 * public final class AnObject {
 *   @NotNull
 *   public static final AnObject INSTANCE = new AnObject();
 *   private static final int field = 1;
 *
 *   private AnObject() {}
 *   public final int getField() { return field }
 * }
 * ```
 */
internal fun CtGenContext.kmToCtObject(kmClassWrapper: KmClassWrapper): CtClass {
    kmClassWrapper.constructors.toList().let { ctors ->
        require(ctors.size <= 1) {
            "objects may define 0 or 1 constructors"
        }
        require(ctors.all { it.constructor.visibility == Visibility.PRIVATE }) {
            "object constructors must be private"
        }
    }

    val kmName = kmClassWrapper.kmClass.kmName
    val javaBinaryName = kmName.asJavaBinaryName

    val result = getClass(javaBinaryName)
    result.classFile.accessFlags = kmClassWrapper.kmClass.jvmAccessFlags
    result.applySupers(this, kmClassWrapper)

    // INSTANCE backing field
    CtField
        .make("public static final $javaBinaryName INSTANCE = new $javaBinaryName();", result)
        .also { field ->
            // add @NotNull
            val notNull = result.classFile.constPool.asCtAnnotation(
                KmAnnotation(Km.NOT_NULL.toString(), emptyMap())
            )
            field.fieldInfo.addAttribute(
                AnnotationsAttribute(result.classFile.constPool, AnnotationsAttribute.invisibleTag)
                    .also {
                        it.addAnnotation(notNull)
                    }
            )
        }.let {
            result.addField(it)
        }

    kmClassWrapper.constructors.forEach { ctor ->
        result.addConstructorFromKm(this, ctor)
    }
    kmClassWrapper.properties.forEach { prop ->
        result.addPropertyFromKm(this, prop)
    }
    kmClassWrapper.functions.forEach { fn ->
        result.addClassFunctionFromKm(this, fn)
    }

    return result
}
