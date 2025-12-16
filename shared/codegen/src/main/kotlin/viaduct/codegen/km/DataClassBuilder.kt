package viaduct.codegen.km

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmType
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.MemberKind
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.isOperator
import kotlinx.metadata.kind
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.ct.KmClassTree
import viaduct.codegen.ct.KmPropertyWrapper
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

class DataClassBuilder internal constructor(
    private val kmFQN: KmName,
    tier: Int = 1
) : ClassBuilder() {
    private val builder =
        CustomClassBuilder(
            ClassKind.CLASS,
            kmFQN,
            isDataClass = true,
            tier = tier
        )

    fun addSupertype(kmType: KmType): DataClassBuilder {
        builder.addSupertype(kmType)
        return this
    }

    private val constructorProperties = mutableListOf<KmPropertyWrapper>()

    fun addProperty(propertyBuilder: KmPropertyBuilder): DataClassBuilder {
        builder.addPropertyInternal(propertyBuilder).also {
            if (it.constructorProperty) constructorProperties.add(it)
        }
        return this
    }

    internal override fun build(): KmClassTree {
        mkConstructor()
        mkComponentN()
        mkEquals()
        mkHashCode()
        mkToString()
        mkCopy()
        return builder.build()
    }

    private fun mkConstructor() {
        val kmConstructor =
            KmConstructor().apply {
                visibility = Visibility.PUBLIC
                valueParameters.addAll(
                    constructorProperties.map { propWrapper ->
                        val hasDefault = (propWrapper.defaultValue != null)
                        KmValueParameter(
                            name = propWrapper.property.name
                        ).apply {
                            type = propWrapper.inputType
                            declaresDefaultValue = hasDefault
                        }
                    }
                )
            }

        val defaultParamValues =
            constructorProperties.filter { it.defaultValue != null }.associate {
                JavaIdName(it.property.name) to it.defaultValue!!
            }

        val body =
            buildString {
                append("{\n")
                constructorProperties.forEachIndexed { index, propWrapper ->
                    val position = index + 1
                    checkNotNullParameterExpression(
                        propWrapper.property.returnType,
                        position,
                        propWrapper.property.name
                    )?.let {
                        append(it)
                    }
                    append("  this.${propWrapper.setterName}($$position);\n")
                }
                append("}")
            }

        builder.addConstructor(kmConstructor, null, body, defaultParamValues)
    }

    private fun mkComponentN() {
        constructorProperties.forEachIndexed { index, propWrapper ->
            val componentFn =
                KmFunction("component${index + 1}").apply {
                    visibility = Visibility.PUBLIC
                    modality = Modality.FINAL
                    returnType = propWrapper.property.returnType
                    isOperator = true
                    kind = MemberKind.SYNTHESIZED
                }
            builder.addFunction(componentFn, "return this.${propWrapper.getterName}();")
        }
    }

    private fun mkEquals() {
        builder.addEqualsFun(constructorProperties)
    }

    private fun mkHashCode() {
        builder.addHashcodeFun(constructorProperties)
    }

    // For the following data class:
    //     data class MyClass(val a: String, val b: Int)
    //
    // The toString() method body is:
    //     StringBuilder result = new StringBuilder().append("MyClass(");
    //     result.append("a=").append(this.a);
    //     result.append(", b=").append(this.b);
    //     return result.append(")").toString();
    private fun mkToString() {
        val toStringFn =
            KmFunction("toString").apply {
                visibility = Visibility.PUBLIC
                modality = Modality.OPEN
                returnType = Km.STRING.asType()
                kind = MemberKind.SYNTHESIZED
            }

        val body =
            run {
                val javaName = kmFQN.asJavaName
                val toStringProps =
                    constructorProperties
                        .mapIndexed { index, propWrapper ->
                            val n = propWrapper.property.name
                            val delimiter = if (index == 0) "" else ", "
                            """result.append("$delimiter$n=").append(this.${propWrapper.getterName}());"""
                        }.joinToString("\n")

                """
                {
                    StringBuilder result = new StringBuilder().append("$javaName(");
                    $toStringProps
                    return result.append(")").toString();
                }
                """.trimIndent()
            }

        builder.addFunction(toStringFn, body)
    }

    private fun mkCopy() {
        builder.addCopyFun(
            constructorProperties.map { propWrapper ->
                KmValueParameter(
                    name = propWrapper.property.name
                ).apply {
                    type = propWrapper.inputType
                    declaresDefaultValue = true
                }
            }
        )
    }
}
