package viaduct.codegen.km

import kotlin.reflect.KClass
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Visibility
import kotlinx.metadata.isNullable
import kotlinx.metadata.visibility
import org.junit.jupiter.api.TestInfo
import viaduct.codegen.km.ctdiff.ClassDiff
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

val TestInfo.asJavaBinaryName: JavaBinaryName get() =
    JavaBinaryName(testMethod.get().name.replaceFirstChar { it.uppercaseChar() })

val TestInfo.asKmName: KmName get() = this.asJavaBinaryName.asKmName

val KClass<*>.kmName: KmName get() {
    val packageName = "${java.`package`.name}."
    val classNamesPath = java.canonicalName.removePrefix(packageName)
    return KmName("${packageName.replace('.', '/')}$classNamesPath")
}

val KClass<*>.kmType: KmType get() =
    this.kmName.asType()

fun arrayKmTypeOf(
    elementType: KmType,
    nullable: Boolean = false
): KmType =
    Km.ARRAY.asType().also {
        it.arguments.add(KmTypeProjection(KmVariance.INVARIANT, elementType))
        it.isNullable = nullable
    }

fun mapKmTypeOf(
    keyType: KmType,
    valueType: KmType,
    nullable: Boolean = false
): KmType =
    KmName("kotlin/collections/Map")
        .let {
            if (nullable) it.asNullableType() else it.asType()
        }.also {
            it.arguments.add(KmTypeProjection(KmVariance.INVARIANT, keyType))
            it.arguments.add(KmTypeProjection(KmVariance.OUT, valueType))
        }

/** Assumes exactly one class has been added to [this].
 *  Generates bytecode for that class, loads the bytecode,
 *  and returns the generated class.
 */
internal fun KmClassFilesBuilder.loadClass(javaFQN: JavaBinaryName): Class<*> = buildClassLoader().loadClass(javaFQN.toString())

fun KmClassFilesBuilder.assertNoDiff(
    expected: KClass<*>,
    actualName: String,
    expectedPkg: String = expected.java.`package`.name,
    actualsPkg: String = actualName.split(".").dropLast(1).joinToString("."),
) {
    val actual = this.buildClassLoader().loadClass(actualName)
    val classDiff = ClassDiff(expectedPkg, actualsPkg, javassistPool = this.classPool)
    classDiff.compare(expected.java, actual)

    if (!classDiff.diffs.isEmpty) {
        val result = StringBuilder("Violations found:\n")
        classDiff.diffs.toMultilineString(result)
        result.append("\n\nTotal errors: ${classDiff.diffs.count()}")
        throw AssertionError(result.toString())
    }
}

fun CustomClassBuilder.addEmptyCtor() {
    this.addConstructor(
        KmConstructor().also { it.visibility = Visibility.PUBLIC },
        body = "{}"
    )
}
