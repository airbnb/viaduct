package viaduct.tenant.codegen.kotlingen

import graphql.schema.idl.SchemaParser
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.mutationNamespaceTypeNames

// This test suite is useful for inspecting the results of resolver generation.
// While each test case makes only a small number of assertions, they are useful places
// for setting a breakpoint to inspect the generated output before it gets compiled.
class FieldResolverGeneratorTest {
    private fun mkSchema(sdl: String): ViaductSchema {
        val tdr = SchemaParser().parse(sdl)
        return ViaductSchema.fromTypeDefinitionRegistry(tdr)
    }

    private fun gen(
        sdl: String,
        typeName: String
    ): String {
        val schema = mkSchema(sdl)
        val type = schema.types[typeName] as ViaductSchema.Record
        val contents = genResolver(typeName, type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "Query", "Mutation")
        return contents.toString().replace("\r\n", "\n")
    }

    @Test
    fun `verifies that fieldResolvergenerator function runs succesfully`() {
        val sdl = """
                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Subject {
                    field: Int
                }
        """.trimIndent()

        val schema = mkSchema(sdl)
        assertDoesNotThrow {
            schema.generateFieldResolvers(
                Args(
                    "viaduct.tenant",
                    "fooo",
                    "tenant_name",
                    "bar",
                    File.createTempFile("temp", null).also { it.deleteOnExit() },
                    File.createTempFile("temp1", null).also { it.deleteOnExit() },
                    File.createTempFile("temp2", null).also { it.deleteOnExit() },
                    baseTypeMapper = ViaductBaseTypeMapper(schema)
                )
            )
        }
    }

    @Test
    fun `generates resolver classes`() {
        val contents = gen(
            """
                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Subject {
                    field: Int
                }
            """.trimIndent(),
            "Subject"
        )

        assertTrue(contents.startsWith("package pkg.tenant.resolverbases\n"))
        assertFalse(contents.contains("MutationExecutionContext"))
        assertTrue(contents.contains("object SubjectResolvers "))
        assertTrue(contents.contains("class Field "))
        assertTrue(contents.contains("viaduct.api.internal.BaseUnbatchedFieldResolver"))
        assertTrue(contents.contains("abstract suspend fun resolve(ctx: Context)"))
        assertTrue(contents.contains("final override suspend fun invokeFieldResolver("))
        assertTrue(contents.contains("resolve(Context(context as viaduct.api.context.FieldExecutionContext"))
        assertFalse(contents.contains("batchResolve"))
    }

    @Test
    fun `generates batch resolver when isBatching is true`() {
        val contents = gen(
            """
                directive @resolver(isSelective: Boolean! = false, isBatching: Boolean! = false) on FIELD_DEFINITION | OBJECT
                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Subject {
                    field: Int @resolver(isBatching: true)
                }
            """.trimIndent(),
            "Subject"
        )

        assertTrue(contents.contains("@ResolverFor(typeName = \"Subject\", fieldName = \"field\", isSelective = false, isBatching = true)"))
        assertTrue(contents.contains("viaduct.api.internal.BaseBatchedFieldResolver"))
        assertTrue(contents.contains("abstract suspend fun batchResolve(contexts: List<Context>)"))
        assertTrue(contents.contains("final override suspend fun invokeFieldBatchResolver("))
        assertTrue(contents.contains("contexts.map { Context(it as viaduct.api.context.FieldExecutionContext"))
        assertFalse(contents.contains("abstract suspend fun resolve(ctx: Context)"))
    }

    @Test
    fun `generates mutation resolvers`() {
        val contents = gen(
            """
                type Query { placeholder: Int }
                type Mutation { field(x: Int!): Int! }
                type Subscription { placeholder: Int }
            """.trimIndent(),
            "Mutation"
        )
        assertTrue(contents.contains("MutationFieldExecutionContext"))
        assertFalse(contents.contains("batchResolve"))
    }

    @Test
    fun `generates mutation resolvers with custom mutation type name`() {
        val sdl = """
            schema {
                query: CustomQuery
                mutation: CustomMutation
                subscription: CustomSubscription
            }
            type CustomQuery { placeholder: Int }
            type CustomMutation { field(x: Int!): Int! }
            type CustomSubscription { event: String }
        """.trimIndent()

        val schema = mkSchema(sdl)
        val type = schema.types["CustomMutation"] as ViaductSchema.Record

        // With correct mutationTypeName, should generate MutationFieldExecutionContext
        val contentsWithCorrectName = genResolver(
            "CustomMutation",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema),
            queryTypeName = "CustomQuery",
            mutationTypeName = "CustomMutation"
        ).toString()
        assertTrue(
            contentsWithCorrectName.contains("MutationFieldExecutionContext"),
            "Should generate MutationFieldExecutionContext when mutationTypeName matches"
        )

        // With wrong mutationTypeName (default "Mutation"), should NOT generate MutationFieldExecutionContext
        val contentsWithWrongName = genResolver(
            "CustomMutation",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema),
            queryTypeName = "CustomQuery",
            mutationTypeName = "Mutation"
        ).toString()
        assertFalse(
            contentsWithWrongName.contains("MutationFieldExecutionContext"),
            "Should NOT generate MutationFieldExecutionContext when mutationTypeName doesn't match"
        )
    }

    @Test
    fun `generates resolvers with custom query type name`() {
        val sdl = """
            schema {
                query: AppQuery
            }
            type AppQuery { field: Int }
        """.trimIndent()

        val schema = mkSchema(sdl)
        val type = schema.types["AppQuery"] as ViaductSchema.Record

        // With correct queryTypeName, should generate FieldExecutionContext with AppQuery
        val contentsWithCorrectName = genResolver(
            "AppQuery",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema),
            queryTypeName = "AppQuery",
            "Mutation"
        ).toString()
        assertTrue(
            contentsWithCorrectName.contains("viaduct.api.grts.AppQuery"),
            "Should reference AppQuery in FieldExecutionContext"
        )
        assertFalse(
            contentsWithCorrectName.contains("viaduct.api.grts.Query"),
            "Should NOT reference default Query type"
        )

        // With default queryTypeName, should generate FieldExecutionContext with Query
        val contentsWithDefaultName = genResolver(
            "AppQuery",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema),
            "Query",
            "Mutation"
        ).toString()
        assertTrue(
            contentsWithDefaultName.contains("viaduct.api.grts.Query"),
            "Should reference default Query type when queryTypeName not provided"
        )
    }

    @Test
    fun `generates backing data resolver`() {
        val contents = gen(
            """
                scalar BackingData
                directive @backingData(class: String!) on FIELD_DEFINITION

                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Subject {
                    field: BackingData @backingData(class: "com.airbnb.myCustomType")
                }
            """.trimIndent(),
            "Subject"
        )

        assertTrue(contents.contains("abstract suspend fun resolve(ctx: Context): kotlin.Any"))
    }

    @Test
    fun `generates ConnectionFieldExecutionContext for connection fields`() {
        val contents = gen(
            """
                directive @connection on OBJECT
                directive @edge on OBJECT

                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }

                type Book {
                    title: String!
                }

                type BookEdge @edge {
                    cursor: String!
                    node: Book
                }

                type BookConnection @connection {
                    edges: [BookEdge!]!
                }

                type Subject {
                    books(first: Int!, after: String): BookConnection!
                    title: String
                }
            """.trimIndent(),
            "Subject"
        )

        // Connection field should use ConnectionFieldExecutionContext
        assertTrue(contents.contains("ConnectionFieldExecutionContext"))
        assertTrue(
            contents.contains("ConnectionFieldExecutionContext<viaduct.api.grts.Subject, viaduct.api.grts.Query, viaduct.api.grts.Subject_Books_Arguments, viaduct.api.grts.BookConnection>")
        )

        // Regular field should still use FieldExecutionContext (not Connection variant)
        assertTrue(contents.contains("FieldExecutionContext<viaduct.api.grts.Subject, viaduct.api.grts.Query,"))

        // Both resolver classes should be generated
        assertTrue(contents.contains("class Books "))
        assertTrue(contents.contains("class Title "))
    }

    @Test
    fun `generates ordinary resolver context for connection fields without pagination arguments`() {
        val contents = gen(
            """
                directive @connection on OBJECT
                directive @edge on OBJECT

                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }

                type Book {
                    title: String!
                }

                type BookEdge @edge {
                    cursor: String!
                    node: Book
                }

                type BookConnection @connection {
                    edges: [BookEdge!]!
                }

                type Subject {
                    books(category: String): BookConnection!
                    allBooks: BookConnection!
                }
            """.trimIndent(),
            "Subject"
        )

        assertTrue(contents.contains("viaduct.api.FieldResolverBase<"))
        assertTrue(
            contents.contains(
                "FieldExecutionContext<viaduct.api.grts.Subject, viaduct.api.grts.Query, viaduct.api.grts.Subject_Books_Arguments, viaduct.api.grts.BookConnection>"
            )
        )
        assertTrue(
            contents.contains(
                "FieldExecutionContext<viaduct.api.grts.Subject, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.BookConnection>"
            )
        )
        assertFalse(contents.contains("viaduct.api.ConnectionResolverBase<"))
        assertFalse(contents.contains("ConnectionFieldExecutionContext"))
    }

    @Test
    fun `generates selective field contexts when resolver is selective`() {
        val contents = gen(
            """
                directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION | OBJECT
                type Query { foo: Foo @resolver(isSelective: true) }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Foo {
                    value: String
                }
            """.trimIndent(),
            "Query"
        )

        assertTrue(contents.contains("@ResolverFor(typeName = \"Query\", fieldName = \"foo\", isSelective = true, isBatching = false)"))
        assertTrue(
            contents.contains(
                "FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.Foo> by inner, viaduct.api.context.SelectiveFieldExecutionContext<viaduct.api.grts.Foo>"
            )
        )
        assertTrue(contents.contains("override fun selections(): viaduct.api.select.SelectionSet<viaduct.api.grts.Foo>"))
        assertTrue(contents.contains("viaduct.api.context.ResolverOwnedSelectionsContext<viaduct.api.grts.Foo>"))
        assertTrue(contents.contains("override fun ownedSelections(): viaduct.api.select.SelectionSet<viaduct.api.grts.Foo>"))
    }

    @Test
    fun `selective non-composite contexts expose selections without an output fragment`() {
        val contents = gen(
            """
                directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION | OBJECT
                enum Choice { ONE }
                type Query {
                  scalarValue: String @resolver(isSelective: true)
                  enumValue: Choice @resolver(isSelective: true)
                }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
            """.trimIndent(),
            "Query"
        )

        assertTrue(contents.contains("SelectiveFieldExecutionContext<viaduct.api.types.CompositeOutput.NotComposite>"))
        assertTrue(contents.contains("override fun selections(): viaduct.api.select.SelectionSet<viaduct.api.types.CompositeOutput.NotComposite>"))
        assertFalse(contents.contains("ResolverOwnedSelectionsContext"))
        assertFalse(contents.contains("ownedSelections()"))
    }

    @Test
    fun `errors when isBatching is true on a standard Mutation field`() {
        val schema = mkSchema(
            """
                directive @resolver(isBatching: Boolean! = false) on FIELD_DEFINITION
                type Query { placeholder: Int }
                type Mutation { field(x: Int!): Int! @resolver(isBatching: true) }
            """.trimIndent()
        )
        val type = schema.types["Mutation"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            genResolver("Mutation", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "Query", "Mutation")
        }
    }

    @Test
    fun `errors when isBatching is true on a custom mutation type field`() {
        val sdl = """
            schema { query: CustomQuery mutation: CustomMutation }
            directive @resolver(isBatching: Boolean! = false) on FIELD_DEFINITION
            type CustomQuery { placeholder: Int }
            type CustomMutation { field(x: Int!): Int! @resolver(isBatching: true) }
        """.trimIndent()
        val schema = mkSchema(sdl)
        val type = schema.types["CustomMutation"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            genResolver("CustomMutation", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "CustomQuery", "CustomMutation")
        }
    }

    @Test
    fun `errors when isSelective is true on a standard Mutation field`() {
        val schema = mkSchema(
            """
                directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION
                type Query { placeholder: Int }
                type Mutation { field(x: Int!): Int! @resolver(isSelective: true) }
            """.trimIndent()
        )
        val type = schema.types["Mutation"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            genResolver("Mutation", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "Query", "Mutation")
        }
    }

    @Test
    fun `errors when isSelective is true on a custom mutation type field`() {
        val sdl = """
            schema { query: CustomQuery mutation: CustomMutation }
            directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION
            type CustomQuery { placeholder: Int }
            type CustomMutation { field(x: Int!): Int! @resolver(isSelective: true) }
        """.trimIndent()
        val schema = mkSchema(sdl)
        val type = schema.types["CustomMutation"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            genResolver("CustomMutation", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "CustomQuery", "CustomMutation")
        }
    }

    @Test
    fun `errors when isSelective is true on a mutation namespace-type field`() {
        val sdl = """
            directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION
            directive @namespaceType on OBJECT
            type Query { placeholder: Int }
            type Mutation { stayFoo: StayFooMutations }
            type StayFooMutations @namespaceType { field(x: Int!): Int! @resolver(isSelective: true) }
        """.trimIndent()
        val schema = mkSchema(sdl)
        val namespaceNames = schema.mutationNamespaceTypeNames()
        val type = schema.types["StayFooMutations"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            genResolver("StayFooMutations", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "Query", "Mutation", namespaceNames)
        }
    }

    @Test
    fun `errors when isBatching is true on a mutation namespace-type field`() {
        val sdl = """
            directive @resolver(isBatching: Boolean! = false) on FIELD_DEFINITION
            directive @namespaceType on OBJECT
            type Query { placeholder: Int }
            type Mutation { stayFoo: StayFooMutations }
            type StayFooMutations @namespaceType { field(x: Int!): Int! @resolver(isBatching: true) }
        """.trimIndent()
        val schema = mkSchema(sdl)
        val namespaceNames = schema.mutationNamespaceTypeNames()
        val type = schema.types["StayFooMutations"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            genResolver("StayFooMutations", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "Query", "Mutation", namespaceNames)
        }
    }

    @Test
    fun `allows isSelective on a query namespace-type field`() {
        // A query namespace type also carries @namespaceType, but is not reachable from the mutation
        // root, so it must not be caught by the mutation ban.
        val sdl = """
            directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION
            directive @namespaceType on OBJECT
            type Query { stayFoo: StayFooQueries }
            type Mutation { placeholder: Int }
            type StayFooQueries @namespaceType { foo: Foo @resolver(isSelective: true) }
            type Foo { id: ID! }
        """.trimIndent()
        val schema = mkSchema(sdl)
        val namespaceNames = schema.mutationNamespaceTypeNames()
        assertTrue(namespaceNames.isEmpty(), "Query namespace must not be treated as a mutation namespace")
        val type = schema.types["StayFooQueries"] as ViaductSchema.Record
        val contents = assertDoesNotThrow {
            genResolver("StayFooQueries", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "Query", "Mutation", namespaceNames)
                .toString().replace("\r\n", "\n")
        }
        assertTrue(contents.contains("isSelective = true"))
    }

    @Test
    fun `errors when the legacy selective alias is true on a Mutation field`() {
        val schema = mkSchema(
            """
                directive @resolver(selective: Boolean! = false) on FIELD_DEFINITION
                type Query { placeholder: Int }
                type Mutation { field(x: Int!): Int! @resolver(selective: true) }
            """.trimIndent()
        )
        val type = schema.types["Mutation"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            genResolver("Mutation", type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema), "Query", "Mutation")
        }
    }

    @Test
    fun `generates resolvers that return ID scalars`() {
        gen(
            """
                type Query { field: ID }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
            """.trimIndent(),
            "Query"
        ).let {
            assertTrue(it.contains("kotlin.String?"))
        }

        gen(
            """
                directive @idOf(type: String!) on FIELD_DEFINITION
                type Query { field: ID @idOf(type: "Foo") }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                interface Node { id: ID! }
                type Foo implements Node { id: ID! }
            """.trimIndent(),
            "Query"
        ).let {
            assertTrue(it.contains("GlobalID<viaduct.api.grts.Foo>"))
        }
    }
}
