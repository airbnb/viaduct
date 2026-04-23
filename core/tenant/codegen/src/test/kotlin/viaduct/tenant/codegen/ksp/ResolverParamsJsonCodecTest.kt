package viaduct.tenant.codegen.ksp

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResolverParamsJsonCodecTest {
    @Test
    fun `encode writes expected node descriptor`() {
        val codec = ResolverParamsJsonCodec()

        val json = codec.encode(
            ResolverDescriptorFile(
                nodes = listOf(
                    ResolverParams.Node(
                        implFqn = "com.example.feature.resolvers.ExampleNodeResolver",
                        typeName = "ExampleNode",
                        resolverBaseClass = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
                        isBatching = false,
                        isSelective = false,
                    ),
                ),
                fields = emptyList(),
            ),
        )

        assertEquals(
            """
                {
                  "fields" : [ ],
                  "nodes" : [ {
                    "attribution" : "ExampleNodeResolver",
                    "implFqn" : "com.example.feature.resolvers.ExampleNodeResolver",
                    "isBatching" : false,
                    "isSelective" : false,
                    "resolverBaseClass" : "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
                    "typeName" : "ExampleNode"
                  } ]
                }

            """.trimIndent(),
            json,
        )
    }

    @Test
    fun `decode reads encoded descriptor file`() {
        val codec = ResolverParamsJsonCodec()

        val descriptorFile = codec.decode(
            """
            {
              "fields" : [ ],
              "nodes" : [ {
                "implFqn" : "com.example.feature.resolvers.ExampleNodeResolver",
                "typeName" : "ExampleNode",
                "resolverBaseClass" : "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
                "attribution" : "ExampleNodeResolver",
                "isBatching" : false,
                "isSelective" : false
              } ]
            }
            """.trimIndent(),
        )

        assertEquals(1, descriptorFile.nodes.size)
        assertTrue(descriptorFile.fields.isEmpty())

        val node = descriptorFile.nodes.single()
        assertEquals("com.example.feature.resolvers.ExampleNodeResolver", node.implFqn)
        assertEquals("ExampleNode", node.typeName)
        assertEquals("com.example.feature.resolverbases.NodeResolvers.ExampleNode", node.resolverBaseClass)
        assertEquals("ExampleNodeResolver", node.attribution)
        assertEquals(false, node.isBatching)
        assertEquals(false, node.isSelective)
    }

    @Test
    fun `encode and decode round trip preserves descriptor file`() {
        val codec = ResolverParamsJsonCodec()

        val original = ResolverDescriptorFile(
            nodes = listOf(
                ResolverParams.Node(
                    implFqn = "com.example.feature.resolvers.ExampleNodeResolver",
                    typeName = "ExampleNode",
                    resolverBaseClass = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
                    isBatching = false,
                    isSelective = false,
                ),
            ),
            fields = emptyList(),
        )

        val decoded = codec.decode(codec.encode(original))

        assertEquals(original, decoded)
    }
}
