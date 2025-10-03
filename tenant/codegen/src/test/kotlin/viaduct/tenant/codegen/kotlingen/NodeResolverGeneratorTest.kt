package viaduct.tenant.codegen.kotlingen

import java.io.File
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper

class NodeResolverGeneratorTest {
    private fun gen(vararg types: String): String? {
        val contents = genNodeResolvers(types.toList(), "pkg.tenant", "pkg.grts")
        return contents?.toString()
    }

    @Test
    fun `empty`() {
        assertNull(gen())
    }

    @Test
    fun `generates node resolvers`() {
        val contents = gen("Foo", "Bar")

        assertNotNull(contents)
        contents!!

        assertTrue(contents.contains("package pkg.tenant"))
        assertTrue(contents.contains("NodeResolverFor(\"Foo\")"))
        assertTrue(contents.contains("abstract class Foo : NodeResolverBase"))
        assertTrue(contents.contains("NodeResolverFor(\"Bar\")"))
        assertTrue(contents.contains("abstract class Bar : NodeResolverBase"))
    }

    @Test
    fun `generateNodeResolvers generates correct output`() {
        val schema = object : ViaductExtendedSchema {
            override val types = mapOf(
                "Foo" to mockTypeDef("Foo"),
                "Bar" to mockTypeDef("Bar")
            )
            override val directives = emptyMap<String, ViaductExtendedSchema.Directive>()
            override val queryTypeDef = null
            override val mutationTypeDef = null
            override val subscriptionTypeDef = null
        }

        val args = Args(
            tenantPackage = "pkg.tenant",
            tenantPackagePrefix = "pkg",
            tenantName = "tenant_name",
            grtPackage = "pkg.grts",
            modernModuleGeneratedDir = File(""),
            metainfGeneratedDir = File(""),
            resolverGeneratedDir = File(""),
            baseTypeMapper = ViaductBaseTypeMapper(schema)
        )

        schema.generateNodeResolvers(args)

        val contents = gen("Foo", "Bar")
        assertNotNull(contents)
        contents!!

        assertTrue(contents.contains("package pkg.tenant"))
        assertTrue(contents.contains("NodeResolverFor(\"Foo\")"))
        assertTrue(contents.contains("abstract class Foo : NodeResolverBase"))
        assertTrue(contents.contains("NodeResolverFor(\"Bar\")"))
        assertTrue(contents.contains("abstract class Bar : NodeResolverBase"))
    }

    private fun mockTypeDef(name: String): ViaductExtendedSchema.TypeDef {
        return object : ViaductExtendedSchema.TypeDef {
            override val name = name
            override val kind = ViaductExtendedSchema.TypeDefKind.OBJECT
            override val appliedDirectives = listOf(mockAppliedDirective())

            override fun describe(): String {
                TODO("Not yet implemented")
            }

            override val sourceLocation = ViaductExtendedSchema.SourceLocation("source")

            override fun asTypeExpr() = TODO()

            override val possibleObjectTypes = emptySet<ViaductExtendedSchema.Object>()

            override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }
        }
    }

    private fun mockDirective(): ViaductExtendedSchema.Directive {
        return object : ViaductExtendedSchema.Directive {
            override val name = "resolver"

            override fun hasAppliedDirective(name: String): Boolean {
                TODO("Not yet implemented")
            }

            override val appliedDirectives: Collection<ViaductSchema.AppliedDirective>
                get() = TODO("Not yet implemented")
            override val sourceLocation: ViaductExtendedSchema.SourceLocation?
                get() = TODO("Not yet implemented")
            override val args = emptyList<ViaductExtendedSchema.DirectiveArg>()
            override val allowedLocations = emptySet<ViaductSchema.Directive.Location>()
            override val isRepeatable: Boolean
                get() = TODO("Not yet implemented")
        }
    }

    private fun mockAppliedDirective(): ViaductSchema.AppliedDirective {
        return object : ViaductSchema.AppliedDirective {
            override val name = "mockDirective"
            override val arguments = emptyMap<String, String>()
        }
    }
}
