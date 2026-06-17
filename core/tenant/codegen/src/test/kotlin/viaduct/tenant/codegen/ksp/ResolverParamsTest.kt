package viaduct.tenant.codegen.ksp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResolverParamsTest {
    @Test
    fun `Node holds all fields`() {
        val node = ResolverParams.Node(
            implFqn = "com.example.resolvers.ExampleNodeResolver",
            typeName = "ExampleNode",
            resolverBaseClass = "com.example.resolverbases.NodeResolvers.ExampleNode",
            isBatching = true,
            isSelective = false,
        )

        assertEquals("com.example.resolvers.ExampleNodeResolver", node.implFqn)
        assertEquals("ExampleNode", node.typeName)
        assertEquals("com.example.resolverbases.NodeResolvers.ExampleNode", node.resolverBaseClass)
        assertEquals("ExampleNodeResolver", node.attribution)
        assertEquals(true, node.isBatching)
        assertEquals(false, node.isSelective)
    }

    @Test
    fun `Node data class equality and copy work correctly`() {
        val original = ResolverParams.Node(
            implFqn = "com.example.resolvers.ExampleNodeResolver",
            typeName = "ExampleNode",
            resolverBaseClass = "com.example.resolverbases.NodeResolvers.ExampleNode",
            isBatching = false,
            isSelective = false,
        )
        val copy = original.copy(typeName = "OtherNode")

        assertEquals(original, original.copy())
        assertEquals("OtherNode", copy.typeName)
        assertEquals("com.example.resolvers.ExampleNodeResolver", copy.implFqn)
    }

    @Test
    fun `Field holds all required properties`() {
        val provider = VariableProviderDescriptor(
            kind = "fromObjectField",
            name = "myVar",
            path = "some.path",
        )

        val field = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            resolverBaseClass = "com.example.resolverbases.ExampleName",
            isBatching = false,
            isSelective = false,
            objectSelections = SelectionsBlock(
                selections = "fragment _ on ExampleNode { name }",
                variablesProviders = listOf(provider),
            ),
            querySelections = SelectionsBlock(
                selections = "fragment _ on Query { example { name } }",
            ),
        )

        assertEquals("com.example.resolvers.ExampleNameResolver", field.implFqn)
        assertEquals("ExampleNode", field.typeName)
        assertEquals("name", field.fieldName)
        assertEquals("com.example.resolverbases.ExampleName", field.resolverBaseClass)
        assertEquals("ExampleNameResolver", field.attribution)
        assertEquals(false, field.isBatching)
        assertEquals(false, field.isSelective)
        assertEquals("fragment _ on ExampleNode { name }", field.objectSelections?.selections)
        assertEquals(1, field.objectSelections?.variablesProviders?.size)
        assertEquals(provider, field.objectSelections?.variablesProviders?.single())
        assertEquals("fragment _ on Query { example { name } }", field.querySelections?.selections)
    }

    @Test
    fun `Field with null optional properties holds nulls`() {
        val field = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            resolverBaseClass = "com.example.resolverbases.ExampleName",
            isBatching = false,
            isSelective = false,
            objectSelections = null,
            querySelections = null,
        )

        assertNull(field.objectSelections)
        assertNull(field.querySelections)
    }

    @Test
    fun `Field data class equality and copy work correctly`() {
        val original = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            resolverBaseClass = "com.example.resolverbases.ExampleName",
            isBatching = false,
            isSelective = false,
            objectSelections = null,
            querySelections = null,
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
            resolverBaseClass = "com.example.resolverbases.NodeResolvers.ExampleNode",
            isBatching = false,
            isSelective = false,
        )

        assertTrue(node is ResolverParams.Node)
    }

    @Test
    fun `Field is a ResolverParams`() {
        val field: ResolverParams = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            resolverBaseClass = "com.example.resolverbases.ExampleName",
            isBatching = false,
            isSelective = false,
            objectSelections = null,
            querySelections = null,
        )

        assertTrue(field is ResolverParams.Field)
    }

    @Test
    fun `VariableProviderDescriptor holds kind name and path`() {
        val provider = VariableProviderDescriptor(
            kind = "fromQueryField",
            name = "queryVar",
            path = "some.nested.path",
        )

        assertEquals("fromQueryField", provider.kind)
        assertEquals("queryVar", provider.name)
        assertEquals("some.nested.path", provider.path)
    }

    @Test
    fun `VariableProviderDescriptor with null path holds null`() {
        val provider = VariableProviderDescriptor(
            kind = "fromObjectField",
            name = "myVar",
            path = null,
        )

        assertNull(provider.path)
    }

    @Test
    fun `VariableProviderDescriptor data class equality and copy work correctly`() {
        val original = VariableProviderDescriptor(
            kind = "fromObjectField",
            name = "myVar",
            path = "root.field",
        )
        val copy = original.copy(name = "otherVar")

        assertEquals(original, original.copy())
        assertEquals("otherVar", copy.name)
        assertEquals("fromObjectField", copy.kind)
    }

    @Test
    fun `ResolverDescriptorFile holds nodes and fields lists`() {
        val node = ResolverParams.Node(
            implFqn = "com.example.resolvers.ExampleNodeResolver",
            typeName = "ExampleNode",
            resolverBaseClass = "com.example.resolverbases.NodeResolvers.ExampleNode",
            isBatching = false,
            isSelective = false,
        )
        val field = ResolverParams.Field(
            implFqn = "com.example.resolvers.ExampleNameResolver",
            typeName = "ExampleNode",
            fieldName = "name",
            resolverBaseClass = "com.example.resolverbases.ExampleName",
            isBatching = false,
            isSelective = false,
            objectSelections = null,
            querySelections = null,
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
