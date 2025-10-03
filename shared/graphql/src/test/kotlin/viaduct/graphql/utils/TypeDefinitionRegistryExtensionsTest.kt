package viaduct.graphql.utils

import graphql.language.EnumTypeDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.InterfaceTypeExtensionDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.ScalarTypeExtensionDefinition
import graphql.language.SchemaExtensionDefinition
import graphql.language.UnionTypeDefinition
import graphql.language.UnionTypeExtensionDefinition
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeDefinitionRegistryExtensionsTest {
    @Test
    fun `toSDL -- empty`() {
        val tdr = TypeDefinitionRegistry().toSDL().toTDR()
        assertEquals(
            setOf("Boolean", "Float", "ID", "Int", "String"),
            tdr.typeDefinitions().map { it.name }.toSet(),
        )
        assertEquals(emptyMap<String, Any>(), tdr.directiveDefinitions)
        assertEquals(emptyList<Any>(), tdr.extensionDefinitions())
    }

    @Test
    fun `toSDL -- object without fields`() {
        val tdr = TypeDefinitionRegistry()
            .also { it.add(ObjectTypeDefinition("Obj")) }
            .toSDL()
            .toTDR()

        val obj = tdr.getType("Obj", ObjectTypeDefinition::class.java).get()
        assertEquals(0, obj.fieldDefinitions.size)
    }

    @Test
    fun `toSDL -- roundtrips a full schema`() {
        val sdl = """
            "@dir comment"
            directive @dir on INLINE_FRAGMENT
            "Scalar comment"
            type Scalar
            type Obj {
              "field comment"
              x: Int
            }
            type Obj2 {
              x: Int
            }
            input Inp {
              x: Int
            }
            union U = Obj
            enum E {
              A
            }
            interface I {
              x: Int
            }
            scalar Int
            scalar Float
            scalar String
            scalar Boolean
            scalar ID
            extend schema {
              mutation: Obj
            }
            extend scalar Scalar @specifiedBy(url: "")
            extend type Obj {
              y: Int
            }
            extend interface I {
              y: Int
            }
            extend union U = Obj2
            extend enum E {
              B
            }
            extend input Inp {
              y: Int
            }

        """.trimIndent()

        assertEquals(sdl, sdl.toTDR().toSDL())
    }

    @Test
    fun `toSDL -- type predicate`() {
        val sdl = """
            scalar Scalar
            type Obj { x:Int }
        """.trimIndent()
        val sdl2 = sdl.toTDR().toSDL(typePredicate = { it.name == "Obj" })
        assertEquals(
            """
                type Obj {
                  x: Int
                }

            """.trimIndent(),
            sdl2
        )
    }

    @Test
    fun `extensionDefinitions -- empty`() {
        assertEquals(emptyList<Any>(), TypeDefinitionRegistry().extensionDefinitions())
    }

    @Test
    fun `extensionDefinitions -- full`() {
        val exts = listOf(
            EnumTypeExtensionDefinition.newEnumTypeExtensionDefinition().name("Enum").build(),
            InputObjectTypeExtensionDefinition.newInputObjectTypeExtensionDefinition().name("Input").build(),
            InterfaceTypeExtensionDefinition.newInterfaceTypeExtensionDefinition().name("Interface").build(),
            ObjectTypeExtensionDefinition.newObjectTypeExtensionDefinition().name("Obj").build(),
            ScalarTypeExtensionDefinition.newScalarTypeExtensionDefinition().name("Int").build(),
            SchemaExtensionDefinition.newSchemaExtensionDefinition().build(),
            UnionTypeExtensionDefinition.newUnionTypeExtensionDefinition().name("Union").build(),
        )
        val tdr = TypeDefinitionRegistry()
            .apply { addAll(exts) }
        assertEquals(exts.toSet(), tdr.extensionDefinitions().toSet())
    }

    @Test
    fun `typeDefinitions -- empty`() {
        assertEquals(
            ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.values,
            TypeDefinitionRegistry().typeDefinitions()
        )
    }

    @Test
    fun `typeDefinitions -- full`() {
        val defs = listOf(
            EnumTypeDefinition.newEnumTypeDefinition().name("Enum").build(),
            InputObjectTypeDefinition.newInputObjectDefinition().name("Input").build(),
            InterfaceTypeDefinition.newInterfaceTypeDefinition().name("Interface").build(),
            ObjectTypeDefinition.newObjectTypeDefinition().name("Obj").build(),
            ScalarTypeDefinition.newScalarTypeDefinition().name("Scalar").build(),
            UnionTypeDefinition.newUnionTypeDefinition().name("Union").build(),
        ) + ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.values

        val tdr = TypeDefinitionRegistry().apply { addAll(defs) }

        assertEquals(defs.toSet(), tdr.typeDefinitions().toSet())
    }

    @Test
    fun `Predicates`() {
        assertTrue(Predicates.alwaysTrue<Any>().test(Unit))
        assertFalse(Predicates.alwaysFalse<Any>().test(Unit))

        assertTrue(Predicates.const<Any>(true).test(Unit))
        assertFalse(Predicates.const<Any>(false).test(Unit))
    }

    private fun String.toTDR(): TypeDefinitionRegistry = SchemaParser().parse(this)
}
