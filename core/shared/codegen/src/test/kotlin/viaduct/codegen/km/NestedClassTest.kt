package viaduct.codegen.km

import actualspkg.AOuter
import actualspkg.IA_1
import actualspkg.IB_1
import actualspkg.ID_1
import actualspkg.InterfaceWithDefaults1
import actualspkg.InterfaceWithDefaults2
import actualspkg.Outer
import expectedspkg.ClassExtendsInterfaceWithDefaults
import expectedspkg.ClassExtendsInterfaceWithDefaults2
import expectedspkg.ClassWithNestAndReference
import expectedspkg.ClassWithNestAndReference2
import expectedspkg.DeeplyNestedClasses
import expectedspkg.EnumHasNestedClass
import expectedspkg.HasCtorNestedRef
import expectedspkg.HasMethodNestedRef
import expectedspkg.HasNestedClassAnnotation
import expectedspkg.HasNestedCtorAnnotation
import expectedspkg.HasNestedCtorParam
import expectedspkg.HasNestedCtorParamAnnotation
import expectedspkg.HasNestedFieldAnnotation
import expectedspkg.HasNestedMethodAnnotation
import expectedspkg.HasNestedMethodParam
import expectedspkg.HasNestedMethodReturn
import expectedspkg.HasNestedProp
import expectedspkg.HasNestedPropertyGetterAnnotation
import expectedspkg.HasNestedPropertySetterAnnotation
import expectedspkg.HasNestedTypeParam
import expectedspkg.HasNestedTypeParamAnnotation
import expectedspkg.HasPropertyNestedRef
import expectedspkg.InterfaceExtendsInterfaceWithDefaults
import expectedspkg.InterfaceExtendsInterfaceWithDefaults2
import expectedspkg.InterfaceExtendsInterfaceWithDefaults3
import expectedspkg.InterfaceWithNestedClass
import expectedspkg.MultipleNestedInheritance
import expectedspkg.TransitiveNestedInheritance
import kotlin.reflect.KClass
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmAnnotationArgument
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Test
import viaduct.codegen.ct.asCtName
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.defaultImpls
import viaduct.codegen.utils.name

class NestedClassTest {
    private val nestedAnnotation = KmAnnotation(
        AOuter::class.kmName.toString(),
        mapOf(
            "inner" to KmAnnotationArgument.AnnotationValue(
                KmAnnotation(AOuter.AInner::class.kmName.toString(), emptyMap())
            )
        )
    )
    private val nestedAnnotations = setOf(nestedAnnotation to true)

    private fun assertEquals(
        expected: KClass<*>,
        buildActual: KmClassFilesBuilder.(actualName: KmName) -> Unit
    ) {
        val ctx = KmClassFilesBuilder()
        val actualName = KmName("$actualspkg/${expected.simpleName}")
        ctx.apply { buildActual(actualName) }
        ctx.assertNoDiff(expected, actualName.asJavaBinaryName.toString())
    }

    private fun CustomClassBuilder.nest(
        name: String,
        kind: ClassKind = ClassKind.CLASS,
        addEmptyCtor: Boolean = true,
        block: CustomClassBuilder.() -> Unit = {},
    ) = nestedClassBuilder(JavaIdName(name), kind = kind).also { nested ->
        if (kind == ClassKind.CLASS && addEmptyCtor) {
            nested.addEmptyCtor()
        }
        block(nested)
    }

    @Test
    fun `class nested in interface`() {
        assertEquals(InterfaceWithNestedClass::class) { actualName ->
            customClassBuilder(ClassKind.INTERFACE, actualName).apply {
                nest("NestedClass")
            }
        }
    }

    @Test
    fun `deeply nested classes`() {
        assertEquals(DeeplyNestedClasses::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                nest("N1") {
                    nest("N2") {
                        nest("N3")
                        nest("M3")
                    }
                    nest("M2")
                }
            }
        }
    }

    @Test
    fun `class with multiply nested supers`() {
        assertEquals(MultipleNestedInheritance::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                nest("N1") {
                    addSupertype(IA_1::class.kmType)
                    addSupertype(IB_1::class.kmType)
                    nest("N2") {
                        addSupertype(IA_1.IA_2::class.kmType)
                        addSupertype(IB_1.IB_2::class.kmType)
                    }
                }
            }
        }
    }

    @Test
    fun `class with nest and reference`() {
        assertEquals(ClassWithNestAndReference::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                nest("N")
                val outerName = this.kmType.name
                addFunction(
                    KmFunction("n").apply {
                        visibility = Visibility.PUBLIC
                        returnType = outerName.append(".N").asType()
                    },
                    body = "{ return null; }"
                )
            }
        }
    }

    @Test
    fun `class with nest and reference 2`() {
        assertEquals(ClassWithNestAndReference2::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                nest("A") {
                    val aName = this.kmType.name
                    nest("B") {
                        addFunction(
                            KmFunction("a").apply {
                                visibility = Visibility.PUBLIC
                                returnType = aName.asType()
                            },
                            body = "{ return new ${aName.asJavaBinaryName}(); }"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `class with transitively nested supers`() {
        assertEquals(TransitiveNestedInheritance::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                nest("N1") {
                    addSupertype(ID_1::class.kmType)
                    nest("N2") {
                        addSupertype(ID_1.ID_2::class.kmType)
                    }
                }
            }
        }
    }

    @Test
    fun `class with nested prop`() {
        assertEquals(HasNestedProp::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                val prop = KmPropertyBuilder(
                    JavaIdName("x"),
                    Outer.Inner::class.kmType,
                    Outer.Inner::class.kmType,
                    isVariable = false,
                    constructorProperty = false
                )
                addProperty(prop)
            }
        }
    }

    @Test
    fun `class with nested type param`() {
        assertEquals(HasNestedTypeParam::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                kmType.arguments += KmTypeProjection(
                    KmVariance.INVARIANT,
                    Outer.Inner::class.kmType
                )
            }
        }
    }

    @Test
    fun `class with nested method param`() {
        assertEquals(HasNestedMethodParam::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                addFunction(
                    KmFunction("f").also {
                        it.visibility = Visibility.PUBLIC
                        it.valueParameters += KmValueParameter("x").also {
                            it.type = Outer.Inner::class.kmType
                        }
                        it.returnType = Km.UNIT.asType()
                    },
                    "{}"
                )
            }
        }
    }

    @Test
    fun `class with nested method return`() {
        assertEquals(HasNestedMethodReturn::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                addFunction(
                    KmFunction("f").also {
                        it.visibility = Visibility.PUBLIC
                        it.returnType = Outer.Inner::class.kmType
                    },
                    "{return null;}",
                )
            }
        }
    }

    @Test
    fun `class with nested ctor param`() {
        assertEquals(HasNestedCtorParam::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addConstructor(
                    KmConstructor().also {
                        it.visibility = Visibility.PUBLIC
                        it.valueParameters += KmValueParameter("x").also {
                            it.type = Outer.Inner::class.kmType
                        }
                    },
                    body = "{}"
                )
            }
        }
    }

    @Test
    fun `property nested ref`() {
        assertEquals(HasPropertyNestedRef::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                addProperty(
                    KmPropertyBuilder(
                        JavaIdName("prop"),
                        Km.ANY.asType(),
                        Km.ANY.asType(),
                        isVariable = false,
                        constructorProperty = false
                    ).also {
                        it.getterBody("{return ${Outer.Inner::class.kmName.asCtName}.class;}")
                    }
                )
            }
        }
    }

    @Test
    fun `method nested ref`() {
        assertEquals(HasMethodNestedRef::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                addFunction(
                    KmFunction("f").apply {
                        visibility = Visibility.PUBLIC
                        returnType = Km.UNIT.asType()
                    },
                    body = "{ System.out.println(${Outer.Inner::class.kmName.asJavaBinaryName}.class); }"
                )
            }
        }
    }

    @Test
    fun `ctor nested ref`() {
        assertEquals(HasCtorNestedRef::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName)
                .apply {
                    addConstructor(
                        KmConstructor().also { it.visibility = Visibility.PUBLIC },
                        body = "{ System.out.println(${Outer.Inner::class.kmName.asJavaBinaryName}.class); }"
                    )
                }
        }
    }

    @Test
    fun `class extends interface with defaults`() {
        assertEquals(ClassExtendsInterfaceWithDefaults::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName)
                .apply {
                    addEmptyCtor()
                    addSupertype(InterfaceWithDefaults1::class.kmType)
                }
        }
    }

    @Test
    fun `class extends interface with defaults 2`() {
        assertEquals(ClassExtendsInterfaceWithDefaults2::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName)
                .apply {
                    addEmptyCtor()
                    addSupertype(InterfaceWithDefaults1::class.kmType)
                    addSupertype(InterfaceWithDefaults2::class.kmType)
                }
        }
    }

    @Test
    fun `interface extends interface with defaults`() {
        assertEquals(InterfaceExtendsInterfaceWithDefaults::class) { actualName ->
            customClassBuilder(ClassKind.INTERFACE, actualName)
                .apply {
                    addSupertype(InterfaceWithDefaults1::class.kmType)
                }
        }
    }

    @Test
    fun `interface extends interface with defaults 2`() {
        assertEquals(InterfaceExtendsInterfaceWithDefaults2::class) { actualName ->
            customClassBuilder(ClassKind.INTERFACE, actualName)
                .apply {
                    addSupertype(InterfaceWithDefaults1::class.kmType)
                    addFunction(
                        KmFunction("f").apply {
                            visibility = Visibility.PUBLIC
                            returnType = Km.INT.asType()
                            modality = Modality.OPEN
                        },
                        body = "{return 2;}"
                    )
                }
        }
    }

    @Test
    fun `interface extends interface with defaults 3`() {
        assertEquals(InterfaceExtendsInterfaceWithDefaults3::class) { actualName ->
            customClassBuilder(ClassKind.INTERFACE, actualName)
                .apply {
                    addSupertype(InterfaceWithDefaults1::class.kmType)
                    addFunction(
                        KmFunction("f").apply {
                            visibility = Visibility.PUBLIC
                            returnType = Km.INT.asType()
                            modality = Modality.OPEN
                        },
                        body = """
                            {
                              return ${InterfaceWithDefaults1::class.kmName.defaultImpls.asJavaBinaryName}.f(null);
                            }
                        """.trimIndent()
                    )
                }
        }
    }

    @Test
    fun `class has nested class annotation`() {
        assertEquals(HasNestedClassAnnotation::class) { actualName ->
            customClassBuilder(
                ClassKind.CLASS,
                actualName,
                annotations = nestedAnnotations
            ).apply {
                addEmptyCtor()
            }
        }
    }

    @Test
    fun `class has nested type param annotation`() {
        assertEquals(HasNestedTypeParamAnnotation::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                this.kmType.arguments += KmTypeProjection(
                    KmVariance.INVARIANT,
                    AOuter.AInner::class.kmType
                )
            }
        }
    }

    @Test
    fun `class has nested ctor annotation`() {
        assertEquals(HasNestedCtorAnnotation::class) { actualName ->
            customClassBuilder(
                ClassKind.CLASS,
                actualName,
            ).apply {
                addConstructor(
                    KmConstructor().apply {
                        visibility = Visibility.PUBLIC
                    },
                    body = "{}",
                    annotations = nestedAnnotations
                )
            }
        }
    }

    @Test
    fun `class has nested ctor param annotation`() {
        assertEquals(HasNestedCtorParamAnnotation::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addConstructor(
                    KmConstructor().apply {
                        valueParameters += KmValueParameter("x").apply {
                            type = Km.INT.asType()
                        }
                        visibility = Visibility.PUBLIC
                    },
                    body = "{}",
                    visibleParameterAnnotations = mapOf(JavaIdName("x") to listOf(nestedAnnotation))
                )
            }
        }
    }

    @Test
    fun `class has nested function annotation`() {
        assertEquals(HasNestedMethodAnnotation::class) { actualName ->
            customClassBuilder(
                ClassKind.CLASS,
                actualName,
            ).apply {
                addEmptyCtor()
                addFunction(
                    KmFunction("x").apply {
                        returnType = Km.UNIT.asType()
                        visibility = Visibility.PUBLIC
                    },
                    body = "{}",
                    annotations = nestedAnnotations
                )
            }
        }
    }

    @Test
    fun `class has nested field annotation`() {
        assertEquals(HasNestedFieldAnnotation::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                addProperty(
                    KmPropertyBuilder(
                        JavaIdName("x"),
                        Km.INT.asType(),
                        Km.INT.asType(),
                        isVariable = false,
                        constructorProperty = false
                    ).apply {
                        hasConstantValue(true)
                        addFieldAnnotations(nestedAnnotations)
                    }
                )
            }
        }
    }

    @Test
    fun `class has nested property getter annotation`() {
        assertEquals(HasNestedPropertyGetterAnnotation::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                addProperty(
                    KmPropertyBuilder(
                        JavaIdName("x"),
                        Km.INT.asType(),
                        Km.INT.asType(),
                        isVariable = false,
                        constructorProperty = false
                    ).apply {
                        addGetterAnnotations(nestedAnnotations)
                        getterBody("{ return 0; }")
                    }
                )
            }
        }
    }

    @Test
    fun `class has nested property setter annotation`() {
        assertEquals(HasNestedPropertySetterAnnotation::class) { actualName ->
            customClassBuilder(ClassKind.CLASS, actualName).apply {
                addEmptyCtor()
                addProperty(
                    KmPropertyBuilder(
                        JavaIdName("x"),
                        Km.INT.asType(),
                        Km.INT.asType(),
                        isVariable = true,
                        constructorProperty = false
                    ).apply {
                        getterBody("{ return 0; }")
                        setterBody("{ return; }")
                        addSetterAnnotations(nestedAnnotations)
                    }
                )
            }
        }
    }

    @Test
    fun `enum has nested class`() {
        assertEquals(EnumHasNestedClass::class) { actualName ->
            enumClassBuilder(actualName, listOf("Value")).apply {
                nestedClassBuilder(JavaIdName("Nested")).apply {
                    addEmptyCtor()
                }
            }
        }
    }
}
