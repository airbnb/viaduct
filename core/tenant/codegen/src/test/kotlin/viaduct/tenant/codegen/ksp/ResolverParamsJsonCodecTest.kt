package viaduct.tenant.codegen.ksp

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResolverParamsJsonCodecTest {
    @Test
    fun `encode writes expected node descriptor`() {
        val codec = ResolverParamsJsonCodec()

        val json = codec.encode(
            PerSourceDescriptorFile(
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
                grtPackagePrefix = "viaduct.api.grts",
            ),
        )

        json.replace("\r\n", "\n") shouldBe """
                {
                  "fields" : [ ],
                  "grtPackagePrefix" : "viaduct.api.grts",
                  "namedFragments" : [ ],
                  "namedOperations" : [ ],
                  "nodes" : [ {
                    "attribution" : "ExampleNodeResolver",
                    "implFqn" : "com.example.feature.resolvers.ExampleNodeResolver",
                    "isBatching" : false,
                    "isSelective" : false,
                    "resolverBaseClass" : "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
                    "typeName" : "ExampleNode"
                  } ]
                }

        """.trimIndent().replace("\r\n", "\n")
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

        val original = PerSourceDescriptorFile(
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

    @Test
    fun `encode writes expected field descriptor without selections`() {
        val codec = ResolverParamsJsonCodec()

        val json = codec.encode(
            PerSourceDescriptorFile(
                nodes = emptyList(),
                fields = listOf(
                    ResolverParams.Field(
                        implFqn = "com.example.feature.resolvers.ExampleNameResolver",
                        typeName = "ExampleNode",
                        fieldName = "name",
                        resolverBaseClass = "com.example.feature.resolverbases.ExampleName",
                        isBatching = false,
                        isSelective = false,
                        objectSelections = null,
                        querySelections = null,
                    ),
                ),
                grtPackagePrefix = "viaduct.api.grts",
            ),
        )

        json.replace("\r\n", "\n") shouldBe """
                {
                  "fields" : [ {
                    "attribution" : "ExampleNameResolver",
                    "fieldName" : "name",
                    "hasArguments" : false,
                    "implFqn" : "com.example.feature.resolvers.ExampleNameResolver",
                    "isBatching" : false,
                    "isSelective" : false,
                    "queryTypeName" : "Query",
                    "resolverBaseClass" : "com.example.feature.resolverbases.ExampleName",
                    "typeName" : "ExampleNode"
                  } ],
                  "grtPackagePrefix" : "viaduct.api.grts",
                  "namedFragments" : [ ],
                  "namedOperations" : [ ],
                  "nodes" : [ ]
                }

        """.trimIndent().replace("\r\n", "\n")
    }

    @Test
    fun `encode writes expected field descriptor with objectSelections and variablesProviders`() {
        val codec = ResolverParamsJsonCodec()

        val json = codec.encode(
            PerSourceDescriptorFile(
                nodes = emptyList(),
                fields = listOf(
                    ResolverParams.Field(
                        implFqn = "com.example.feature.resolvers.ExampleNameResolver",
                        typeName = "ExampleNode",
                        fieldName = "name",
                        resolverBaseClass = "com.example.feature.resolverbases.ExampleName",
                        isBatching = false,
                        isSelective = false,
                        objectSelections = SelectionsBlock(
                            selections = "fragment _ on ExampleNode { firstName lastName }",
                            variablesProviders = listOf(
                                VariableProviderDescriptor(
                                    kind = "fromArgument",
                                    name = "includeDetails",
                                    path = "includeDetails",
                                ),
                            ),
                        ),
                        querySelections = null,
                    ),
                ),
                grtPackagePrefix = "viaduct.api.grts",
            ),
        )

        json.replace("\r\n", "\n") shouldBe """
                {
                  "fields" : [ {
                    "attribution" : "ExampleNameResolver",
                    "fieldName" : "name",
                    "hasArguments" : false,
                    "implFqn" : "com.example.feature.resolvers.ExampleNameResolver",
                    "isBatching" : false,
                    "isSelective" : false,
                    "objectSelections" : {
                      "selections" : "fragment _ on ExampleNode { firstName lastName }",
                      "variablesProviders" : [ {
                        "kind" : "fromArgument",
                        "name" : "includeDetails",
                        "path" : "includeDetails",
                        "providedVariables" : { }
                      } ]
                    },
                    "queryTypeName" : "Query",
                    "resolverBaseClass" : "com.example.feature.resolverbases.ExampleName",
                    "typeName" : "ExampleNode"
                  } ],
                  "grtPackagePrefix" : "viaduct.api.grts",
                  "namedFragments" : [ ],
                  "namedOperations" : [ ],
                  "nodes" : [ ]
                }

        """.trimIndent().replace("\r\n", "\n")
    }

    @Test
    fun `encode writes expected field descriptor with both objectSelections and querySelections`() {
        val codec = ResolverParamsJsonCodec()

        val json = codec.encode(
            PerSourceDescriptorFile(
                nodes = emptyList(),
                fields = listOf(
                    ResolverParams.Field(
                        implFqn = "com.example.feature.resolvers.GreetingResolver",
                        typeName = "Character",
                        fieldName = "greeting",
                        resolverBaseClass = "com.example.feature.resolverbases.CharacterResolvers.Greeting",
                        isBatching = false,
                        isSelective = false,
                        objectSelections = SelectionsBlock(
                            selections = "fragment _ on Character { name }",
                        ),
                        querySelections = SelectionsBlock(
                            selections = "fragment _ on Query { viewer { displayName } }",
                        ),
                    ),
                ),
                grtPackagePrefix = "viaduct.api.grts",
            ),
        )

        json.replace("\r\n", "\n") shouldBe """
                {
                  "fields" : [ {
                    "attribution" : "GreetingResolver",
                    "fieldName" : "greeting",
                    "hasArguments" : false,
                    "implFqn" : "com.example.feature.resolvers.GreetingResolver",
                    "isBatching" : false,
                    "isSelective" : false,
                    "objectSelections" : {
                      "selections" : "fragment _ on Character { name }",
                      "variablesProviders" : [ ]
                    },
                    "querySelections" : {
                      "selections" : "fragment _ on Query { viewer { displayName } }",
                      "variablesProviders" : [ ]
                    },
                    "queryTypeName" : "Query",
                    "resolverBaseClass" : "com.example.feature.resolverbases.CharacterResolvers.Greeting",
                    "typeName" : "Character"
                  } ],
                  "grtPackagePrefix" : "viaduct.api.grts",
                  "namedFragments" : [ ],
                  "namedOperations" : [ ],
                  "nodes" : [ ]
                }

        """.trimIndent().replace("\r\n", "\n")
    }

    @Test
    fun `decode reads encoded field descriptor`() {
        val codec = ResolverParamsJsonCodec()

        val descriptorFile = codec.decode(
            """
            {
              "fields" : [ {
                "implFqn" : "com.example.feature.resolvers.ExampleNameResolver",
                "typeName" : "ExampleNode",
                "fieldName" : "name",
                "resolverBaseClass" : "com.example.feature.resolverbases.ExampleName",
                "attribution" : "ExampleNameResolver",
                "isBatching" : false,
                "isSelective" : false,
                "objectSelections" : {
                  "selections" : "fragment _ on ExampleNode { firstName lastName }",
                  "variablesProviders" : [ {
                    "kind" : "fromArgument",
                    "name" : "includeDetails",
                    "path" : "includeDetails",
                    "providedVariables" : { }
                  } ]
                }
              } ],
              "nodes" : [ ]
            }
            """.trimIndent(),
        )

        assertTrue(descriptorFile.nodes.isEmpty())
        assertEquals(1, descriptorFile.fields.size)

        val field = descriptorFile.fields.single()
        assertEquals("com.example.feature.resolvers.ExampleNameResolver", field.implFqn)
        assertEquals("ExampleNode", field.typeName)
        assertEquals("name", field.fieldName)
        assertEquals("com.example.feature.resolverbases.ExampleName", field.resolverBaseClass)
        assertEquals("ExampleNameResolver", field.attribution)
        assertEquals(false, field.isBatching)
        assertEquals(false, field.isSelective)
        assertEquals("fragment _ on ExampleNode { firstName lastName }", field.objectSelections?.selections)
        assertEquals(1, field.objectSelections?.variablesProviders?.size)
        val provider = field.objectSelections?.variablesProviders?.single()
        assertEquals("fromArgument", provider?.kind)
        assertEquals("includeDetails", provider?.name)
        assertEquals("includeDetails", provider?.path)
        assertNull(field.querySelections)
    }

    @Test
    fun `encode and decode round trip preserves field descriptor`() {
        val codec = ResolverParamsJsonCodec()

        val original = PerSourceDescriptorFile(
            nodes = emptyList(),
            fields = listOf(
                ResolverParams.Field(
                    implFqn = "com.example.feature.resolvers.ExampleNameResolver",
                    typeName = "ExampleNode",
                    fieldName = "name",
                    resolverBaseClass = "com.example.feature.resolverbases.ExampleName",
                    isBatching = false,
                    isSelective = false,
                    objectSelections = SelectionsBlock(
                        selections = "fragment _ on ExampleNode { firstName lastName }",
                        variablesProviders = listOf(
                            VariableProviderDescriptor(kind = "fromArgument", name = "x", path = "x"),
                        ),
                    ),
                    querySelections = null,
                ),
            ),
        )

        val decoded = codec.decode(codec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `encode and decode round trip preserves named operations`() {
        val codec = ResolverParamsJsonCodec()

        val original = PerSourceDescriptorFile(
            namedOperations = listOf(
                OperationDescriptor("{ echo }", OperationKind.QUERY, "com.example.feature.EchoQuery"),
                OperationDescriptor("mutation { record }", OperationKind.MUTATION, "com.example.feature.RecordMutation"),
            ),
        )

        val decoded = codec.decode(codec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `encode and decode round trip preserves providedVariables`() {
        val codec = ResolverParamsJsonCodec()

        val original = PerSourceDescriptorFile(
            nodes = emptyList(),
            fields = listOf(
                ResolverParams.Field(
                    implFqn = "com.example.feature.resolvers.ExampleNameResolver",
                    typeName = "ExampleNode",
                    fieldName = "name",
                    resolverBaseClass = "com.example.feature.resolverbases.ExampleName",
                    isBatching = false,
                    isSelective = false,
                    objectSelections = SelectionsBlock(
                        selections = "fragment _ on ExampleNode { name }",
                        variablesProviders = listOf(
                            VariableProviderDescriptor(
                                kind = "fromArgument",
                                name = "experiment",
                                path = "experiment",
                                providedVariables = mapOf("experiment" to "Boolean!"),
                            ),
                        ),
                    ),
                    querySelections = null,
                ),
            ),
        )

        val decoded = codec.decode(codec.encode(original))

        assertEquals(original, decoded)
        assertEquals(
            mapOf("experiment" to "Boolean!"),
            decoded.fields.single().objectSelections?.variablesProviders?.single()?.providedVariables,
        )
    }
}
