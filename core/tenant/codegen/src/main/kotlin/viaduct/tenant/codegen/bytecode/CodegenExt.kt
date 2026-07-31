package viaduct.tenant.codegen.bytecode

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.isOperator
import kotlinx.metadata.jvm.annotations
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.checkNotNullParameterExpression
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

// Utilities expressed as extension functions go here.  Constants and
// other utility functions go in Config.kt

// *** Name-related utilities *** //

/** Translate the name of a non-scalar GraphQL type into
 *  the kotlinx-metadata name for the type that will
 *  represent it. */
fun String.kmFQN(pkg: KmName): KmName = KmName("$pkg/$this")

// *** of-factory generation *** //

/**
 * Generates the `of` factory object on a GRT class, enabling DSL-style construction:
 * ```
 *   MyType.of(ctx) { someField("value") }
 * ```
 * The `of` object exposes an `invoke` operator that creates a Builder, applies the
 * caller-supplied receiver lambda, and returns the built instance.
 *
 * @param contextType the KmType for the context parameter
 */
internal fun CustomClassBuilder.addOfObject(contextType: KmType) {
    val ofObject = nestedClassBuilder(JavaIdName("of"), kind = ClassKind.OBJECT)

    val builderKmType = this.kmName.append(".Builder").asType()
    val blockType = Km.FUNCTION1.asType().also {
        it.annotations.add(KmAnnotation("kotlin/ExtensionFunctionType", emptyMap()))
        it.arguments += KmTypeProjection(KmVariance.IN, builderKmType)
        it.arguments += KmTypeProjection(KmVariance.INVARIANT, Km.UNIT.asType())
    }

    val invokeFunction = KmFunction("invoke").also {
        it.visibility = Visibility.PUBLIC
        it.modality = Modality.FINAL
        it.isOperator = true
        it.returnType = this.kmType
        it.valueParameters.addAll(
            listOf(
                KmValueParameter("context").also { p -> p.type = contextType },
                KmValueParameter("block").also { p -> p.type = blockType }
            )
        )
    }

    val builderJavaName = this.kmName.append(".Builder").asJavaName
    val outerJavaName = this.kmName.asJavaName

    ofObject.addFunction(
        invokeFunction,
        body = buildString {
            append("{\n")
            checkNotNullParameterExpression(contextType, 1, "context")?.let { append(it).append("\n") }
            checkNotNullParameterExpression(blockType, 2, "block")?.let { append(it).append("\n") }
            append("$builderJavaName builder = new $builderJavaName($1);\n")
            append("$2.invoke(builder);\n")
            append("return ($outerJavaName) builder.build();\n")
            append("}")
        }
    )
}
