package viaduct.tenant.codegen.kotlingen

import java.io.File
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper

class NodeResolverGeneratorTest {
    private fun gen(vararg types: Triple<String, Boolean, Boolean>): String? {
        val contents = genNodeResolvers(
            types.map { (typeName, isSelective, isBatching) -> NodeResolverConfig(typeName, isSelective, isBatching) },
            "pkg.tenant",
            "pkg.grts"
        )
        return contents?.toString()
    }

    @Test
    fun `empty`() {
        assertNull(gen())
    }

    @Test
    fun `generates node resolvers`() {
        val contents = gen(Triple("Foo", false, false), Triple("Bar", true, false))

        assertNotNull(contents)
        contents!!

        assertTrue(contents.contains("package pkg.tenant.resolverbases"))
        assertTrue(contents.contains("NodeResolverFor(typeName = \"Foo\", isSelective = false, isBatching = false)"))
        assertTrue(contents.contains("abstract class Foo : viaduct.api.ResolverBase<pkg.grts.Foo>, NodeResolverBase<pkg.grts.Foo>, viaduct.api.internal.BaseUnbatchedNodeResolver"))
        assertTrue(contents.contains("final override suspend fun invokeNodeResolver("))
        assertTrue(contents.contains("Context(context as viaduct.api.context.NodeExecutionContext<pkg.grts.Foo>)"))
        assertTrue(
            contents.contains(
                "@InternalApi internal val inner: viaduct.api.context.NodeExecutionContext<pkg.grts.Foo>"
            )
        )
        assertTrue(contents.contains("NodeResolverFor(typeName = \"Bar\", isSelective = true, isBatching = false)"))
        assertTrue(contents.contains("abstract class Bar : viaduct.api.ResolverBase<pkg.grts.Bar>, NodeResolverBase<pkg.grts.Bar>, viaduct.api.internal.BaseUnbatchedNodeResolver"))
        assertTrue(contents.contains("viaduct.api.context.SelectiveNodeExecutionContext<pkg.grts.Bar>"))
        assertTrue(contents.contains("override fun selections(): SelectionSet<pkg.grts.Bar> = inner.selections()"))
    }

    @Test
    fun `generates typed delegating adapter for batching node resolver`() {
        val contents = gen(Triple("Foo", false, true))

        assertNotNull(contents)
        contents!!

        assertTrue(
            contents.contains(
                "viaduct.api.internal.BaseBatchedNodeResolver"
            )
        )
        assertTrue(
            contents.contains(
                "abstract suspend fun batchResolve(contexts: List<Context>): Map<Context, FieldValue<pkg.grts.Foo>>"
            )
        )
        assertTrue(contents.contains("final override suspend fun invokeNodeBatchResolver("))
        assertTrue(contents.contains("contexts: List<viaduct.api.context.NodeExecutionContext<*>>"))
        assertTrue(contents.contains("Context(it as viaduct.api.context.NodeExecutionContext<pkg.grts.Foo>)"))
        assertTrue(contents.contains("return batchResolve(wrappedContexts).mapKeys { it.key.inner }"))
        assertTrue(
            contents.contains(
                "@InternalApi internal val inner: viaduct.api.context.NodeExecutionContext<pkg.grts.Foo>"
            )
        )
    }

    @Test
    fun `generateNodeResolvers generates correct output`() {
        val schema = object : ViaductSchema {
            override val types = mapOf(
                "Foo" to mockTypeDef("Foo"),
                "Bar" to mockTypeDef("Bar")
            )
            override val directives = emptyMap<String, ViaductSchema.Directive>()
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

        val contents = gen(Triple("Foo", false, false), Triple("Bar", false, false))
        assertNotNull(contents)
        contents!!

        assertTrue(contents.contains("package pkg.tenant.resolverbases"))
        assertTrue(contents.contains("NodeResolverFor(typeName = \"Foo\", isSelective = false, isBatching = false)"))
        assertTrue(contents.contains("abstract class Foo : viaduct.api.ResolverBase<pkg.grts.Foo>, NodeResolverBase<pkg.grts.Foo>, viaduct.api.internal.BaseUnbatchedNodeResolver"))
        assertTrue(contents.contains("NodeResolverFor(typeName = \"Bar\", isSelective = false, isBatching = false)"))
        assertTrue(contents.contains("abstract class Bar : viaduct.api.ResolverBase<pkg.grts.Bar>, NodeResolverBase<pkg.grts.Bar>, viaduct.api.internal.BaseUnbatchedNodeResolver"))
    }

    private fun mockTypeDef(name: String): ViaductSchema.TypeDef {
        return object : ViaductSchema.TypeDef {
            override val containingSchema: ViaductSchema = ViaductSchema.Empty
            override val name = name
            override val kind = ViaductSchema.TypeDefKind.OBJECT
            override val appliedDirectives = listOf(mockAppliedDirective())

            override fun describe(): String {
                TODO("Not yet implemented")
            }

            override val sourceLocation = ViaductSchema.SourceLocation("source")

            override fun asTypeExpr() = TODO()

            override val possibleObjectTypes = emptySet<ViaductSchema.Object>()

            override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

            override val extensions: Collection<ViaductSchema.Extension<ViaductSchema.TypeDef, ViaductSchema.Def>>
                get() = TODO("Not yet implemented")
            override val description: String? = null
        }
    }

    private fun mockDirective(): ViaductSchema.Directive {
        return object : ViaductSchema.Directive {
            override val containingSchema: ViaductSchema = ViaductSchema.Empty
            override val name = "resolver"

            override fun hasAppliedDirective(name: String): Boolean {
                TODO("Not yet implemented")
            }

            override val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>
                get() = TODO("Not yet implemented")
            override val sourceLocation: ViaductSchema.SourceLocation?
                get() = TODO("Not yet implemented")
            override val description: String? = null
            override val args = emptyList<ViaductSchema.DirectiveArg>()
            override val allowedLocations = emptySet<ViaductSchema.Directive.Location>()
            override val isRepeatable: Boolean
                get() = TODO("Not yet implemented")
        }
    }

    private fun mockAppliedDirective(): ViaductSchema.AppliedDirective<*> {
        return ViaductSchema.AppliedDirective.of(mockDirective(), mapOf("isSelective" to ViaductSchema.FALSE))
    }
}
