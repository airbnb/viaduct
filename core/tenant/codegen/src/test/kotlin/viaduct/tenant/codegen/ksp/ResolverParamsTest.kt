package viaduct.tenant.codegen.ksp

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResolverParamsTest {
    @Test
    fun `Node holds implFqn and typeName`() {
        val node = ResolverParams.Node(
            implFqn = "com.example.resolvers.ExampleNodeResolver",
            typeName = "ExampleNode",
        )

        assertEquals("com.example.resolvers.ExampleNodeResolver", node.implFqn)
        assertEquals("ExampleNode", node.typeName)
    }

    @Test
    fun `Node data class equality and copy work correctly`() {
        val original = ResolverParams.Node(
            implFqn = "com.example.resolvers.ExampleNodeResolver",
            typeName = "ExampleNode",
        )
        val copy = original.copy(typeName = "OtherNode")

        assertEquals(original, original.copy())
        assertEquals("OtherNode", copy.typeName)
        assertEquals("com.example.resolvers.ExampleNodeResolver", copy.implFqn)
    }

    @Test
    fun `Field holds all required properties`() {
        val provider = VariableProviderDescriptor(
            kind = "OBJECT_VALUE",
            name = "myVar",
            path = "some.path",
        )

        val field = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            objectValueFragment = "fragment ObjectFrag on ExampleNode { name }",
            queryValueFragment = "fragment QueryFrag on Query { example { name } }",
            variableProviders = listOf(provider),
        )

        assertEquals("com.example.resolvers.ExampleNameResolver", field.implFqn)
        assertEquals("ExampleNode", field.typeName)
        assertEquals("name", field.fieldName)
        assertEquals("fragment ObjectFrag on ExampleNode { name }", field.objectValueFragment)
        assertEquals("fragment QueryFrag on Query { example { name } }", field.queryValueFragment)
        assertEquals(1, field.variableProviders.size)
        assertEquals(provider, field.variableProviders.single())
    }

    @Test
    fun `Field with null optional properties holds nulls`() {
        val field = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            objectValueFragment = null,
            queryValueFragment = null,
            variableProviders = emptyList(),
        )

        assertNull(field.objectValueFragment)
        assertNull(field.queryValueFragment)
        assertTrue(field.variableProviders.isEmpty())
    }

    @Test
    fun `Field data class equality and copy work correctly`() {
        val original = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            objectValueFragment = null,
            queryValueFragment = null,
            variableProviders = emptyList(),
        )
        val copy = original.copy(fieldName = "title")

        assertEquals(original, original.copy())
        assertEquals("title", copy.fieldName)
    }

    @Test
    fun `Node is a ResolverParams`() {
        val node: ResolverParams = ResolverParams.Node(
            implFqn = "com.example.resolvers.ExampleNodeResolver",
            typeName = "ExampleNode",
        )

        assertTrue(node is ResolverParams.Node)
    }

    @Test
    fun `Field is a ResolverParams`() {
        val field: ResolverParams = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            objectValueFragment = null,
            queryValueFragment = null,
            variableProviders = emptyList(),
        )

        assertTrue(field is ResolverParams.Field)
    }

    @Test
    fun `VariableProviderDescriptor holds kind name and path`() {
        val provider = VariableProviderDescriptor(
            kind = "QUERY_VALUE",
            name = "queryVar",
            path = "some.nested.path",
        )

        assertEquals("QUERY_VALUE", provider.kind)
        assertEquals("queryVar", provider.name)
        assertEquals("some.nested.path", provider.path)
    }

    @Test
    fun `VariableProviderDescriptor with null path holds null`() {
        val provider = VariableProviderDescriptor(
            kind = "OBJECT_VALUE",
            name = "myVar",
            path = null,
        )

        assertNull(provider.path)
    }

    @Test
    fun `VariableProviderDescriptor data class equality and copy work correctly`() {
        val original = VariableProviderDescriptor(
            kind = "OBJECT_VALUE",
            name = "myVar",
            path = "root.field",
        )
        val copy = original.copy(name = "otherVar")

        assertEquals(original, original.copy())
        assertEquals("otherVar", copy.name)
        assertEquals("OBJECT_VALUE", copy.kind)
    }

    @Test
    fun `ResolverDescriptorFile holds nodes and fields lists`() {
        val node = ResolverParams.Node(
            implFqn = "com.example.resolvers.ExampleNodeResolver",
            typeName = "ExampleNode",
        )
        val field = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            objectValueFragment = null,
            queryValueFragment = null,
            variableProviders = emptyList(),
        )

        val descriptorFile = ResolverDescriptorFile(
            nodes = listOf(node),
            fields = listOf(field),
        )

        assertEquals(listOf(node), descriptorFile.nodes)
        assertEquals(listOf(field), descriptorFile.fields)
    }

    @Test
    fun `ResolverDescriptorFile data class equality works`() {
        val a = ResolverDescriptorFile(nodes = emptyList(), fields = emptyList())
        val b = ResolverDescriptorFile(nodes = emptyList(), fields = emptyList())

        assertEquals(a, b)
    }
}
