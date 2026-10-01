@file:Suppress("ForbiddenImport")

package model.registry

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.Assumptions
import model.Fragment
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.lowering.ViaductAndGJSchema
import model.lowering.loweredFieldFromSourceCoordinate
import model.nodeReferenceIdentityOrNull
import model.parsing.materializeSelectionsFrom
import model.parsing.selectionsFrom
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.schemaType
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

class RegistryConstructionTest {
    @Test
    fun `constructs an executable registry and assumptions through main APIs`() =
        runBlocking {
            val schemas = schemas("type Query { greeting: String }")
            val schema = schemas.loweredSchema
            val greeting = schema.requireObjectField("Query", "greeting")
            val registry =
                resolverRegistryOf(
                    schema = schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers = mapOf(greeting to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "hello" }),
                    variableProviders = emptyMap(),
                )
            val world = Assumptions.of(schema, registry)
            val root = registry.createRootQueryInput()

            assertSame(schema, world.schema)
            assertSame(registry, world.resolverRegistry)
            assertSame(schema.requireQueryTypeDef(), root.schemaType)
            assertTrue(root.getSelections().none())
            assertEquals(
                "hello",
                registry.resolver(greeting)(
                    input = root,
                    queryValue = root,
                    arguments = Arguments.Resolved.of(greeting, emptyMap()),
                    selectiveResolvers = true,
                    executionContext = ResolutionExecutionContext.Unsupported,
                ),
            )
            val typename = schema.loweredFieldFromSourceCoordinate("Query", "__typename") as ViaductSchema.ObjectField
            assertTrue(registry.mayDemandFrom(typename).isEmpty())
            assertEquals(
                "Query",
                registry.resolver(typename)(
                    input = root,
                    queryValue = root,
                    arguments = Arguments.Resolved.of(typename, emptyMap()),
                    selectiveResolvers = true,
                    executionContext = ResolutionExecutionContext.Unsupported,
                ),
            )
        }

    @Test
    fun `does not supply fixture defaults for missing Query resolvers`() {
        val schemas = schemas("type Query { missing: String }")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                resolverRegistryOf(
                    schema = schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers = emptyMap(),
                    variableProviders = emptyMap(),
                )
            }

        assertTrue(failure.message.orEmpty().contains("Query fields without field resolvers: missing"))
    }

    @Test
    fun `lowers node references and retains their authoritative identity`() =
        runBlocking {
            val schemas =
                schemas(
                    """
                    type Query { node(id: ID!): Node user: User }
                    interface Node { id: ID! }
                    type User implements Node { id: ID! name: String }
                    """,
                )
            val schema = schemas.loweredSchema
            val userType = schema.requireType("User") as ViaductSchema.Object
            val userField = schema.requireObjectField("Query", "user")
            val nodeField = schema.requireObjectField("Query", "node")
            val observedIds = mutableListOf<String>()
            val registry =
                resolverRegistryOf(
                    schema = schemas,
                    nodeResolvers =
                        mapOf(
                            userType to nodeResolverOf { id ->
                                observedIds += id
                                engineObjectDataOf(userType, mapOf("name" to "Ada"))
                            },
                        ),
                    fieldResolvers =
                        mapOf(
                            nodeField to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> null },
                            userField to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                engineObjectDataOf(userType, mapOf("id" to "42"))
                            },
                        ),
                    variableProviders = emptyMap(),
                )
            val root = registry.createRootQueryInput()
            val reference =
                assertIs<RootFieldReferenceData>(
                    registry.resolver(userField)(
                        input = root,
                        queryValue = root,
                        arguments = Arguments.Resolved.of(userField, emptyMap()),
                        selectiveResolvers = true,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    ),
                )

            assertSame(nodeField, reference.targetField)
            assertSame(userType, reference.nodeReferenceIdentityOrNull()?.type)
            assertEquals("42", reference.nodeReferenceIdentityOrNull()?.id)
            val node =
                assertIs<EngineObjectData.Sync>(
                    registry.resolver(nodeField)(
                        input = root,
                        queryValue = root,
                        arguments = reference.arguments,
                        selections = schemas.selectionsFrom("fragment Demand on User { id name }").second,
                        selectiveResolvers = true,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    ),
                )
            assertSame(userType, node.schemaType)
            assertEquals(setOf("id", "name"), node.getSelections().toSet())
            assertEquals("42", node.get("id"))
            assertEquals("Ada", node.get("name"))
            assertEquals(listOf("42"), observedIds)
        }

    @Test
    fun `compiles owned variables without conflating object and Query provider paths`() {
        val schemas = schemas("type Query { source: Int! use(x: Int!): Int! result(x: Int!): Int }")
        val schema = schemas.loweredSchema
        val result = schema.requireObjectField("Query", "result")
        val objectSource =
            "fragment ObjectInput on Query { selected: source objectUse: use(x: \$object) argumentUse: use(x: \$arg) providerUse: use(x: \$provided) }"
        val querySource = "fragment QueryInput on Query { selected: source queryUse: use(x: \$query) }"
        val resultDefinition =
            fieldResolverOf(fragment(schemas, objectSource, result), fragment(schemas, querySource, result)) { _, _, _ -> null }
                .withVariablesProvider(setOf("provided")) { arguments -> mapOf("provided" to arguments.fieldValues.getValue("x")) }
        val registry =
            resolverRegistryOf(
                schema = schemas,
                nodeResolvers = emptyMap(),
                fieldResolvers =
                    mapOf(
                        schema.requireObjectField("Query", "source") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        schema.requireObjectField("Query", "use") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments -> arguments.fieldValues.getValue("x") },
                        result to resultDefinition,
                    ),
                variableProviders =
                    mapOf(
                        Arguments.Variable.of(result, "arg") to schema.fromArgument(result, "x"),
                        Arguments.Variable.of(result, "object") to schemas.fromObjectField(objectSource, listOf("selected"), variableField = result),
                        Arguments.Variable.of(result, "query") to schemas.fromQueryField(querySource, listOf("selected"), variableField = result),
                    ),
            )
        val variables = registry.resolver(result).variables

        assertEquals(setOf("arg", "object", "query", "provided"), variables.keys.map { it.variableName }.toSet())
        variables.keys.forEach { variable ->
            assertSame(result, assertIs<ResolverTarget.FieldValueResolverTarget>(variable.target).field)
        }
        val byName = variables.entries.associate { it.key.variableName to it.value }
        assertEquals("x", assertIs<VariableDefinition.FromArgument>(byName.getValue("arg")).argument.name)
        assertSame(VariableDefinition.FromProvider, byName.getValue("provided"))
        for ((name, provider) in listOf("object" to ProviderFragment.OBJECT, "query" to ProviderFragment.QUERY)) {
            val definition = assertIs<VariableDefinition.FromField>(byName.getValue(name))
            assertEquals(provider, definition.providerFragment)
            assertEquals(listOf("selected"), definition.responsePath)
            assertSame(schema.requireObjectField("Query", "source"), definition.path.single().field)
        }
    }

    @Test
    fun `preserves field and type checker registrations`() {
        val schemas = schemas("type Query { value: String }")
        val schema = schemas.loweredSchema
        val query = schema.requireQueryTypeDef()
        val field = schema.requireObjectField("Query", "value")
        val fieldChecker = FieldCheckerResolver.of(field, query) { _, _, _ -> CheckerResult.Success }
        val typeChecker = TypeCheckerResolver.of(query, query) { _, _ -> CheckerResult.Success }

        val registry =
            resolverRegistryOf(
                schema = schemas,
                nodeResolvers = emptyMap(),
                fieldResolvers = mapOf(field to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "value" }),
                fieldCheckers = mapOf(field to fieldChecker),
                typeCheckers = mapOf(query to typeChecker),
                variableProviders = emptyMap(),
            )

        assertSame(fieldChecker, registry.fieldChecker(field))
        assertSame(typeChecker, registry.typeChecker(query))
    }

    @Test
    fun `rejects resolver coordinates from another schema`() {
        val schemas = schemas("type Query { value: String }")
        val foreignSchema = schemas("type Query { value: String }").loweredSchema

        val failure =
            assertFailsWith<IllegalArgumentException> {
                resolverRegistryOf(
                    schema = schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers =
                        mapOf(
                            foreignSchema.requireObjectField("Query", "value") to fieldResolverOf(foreignSchema.emptyFragmentOf("Query")) { _, _ -> "value" },
                        ),
                    variableProviders = emptyMap(),
                )
            }

        assertTrue(failure.message.orEmpty().contains("not the canonical field-resolver field"))
    }

    @Test
    fun `rejects cyclic required selections during registry construction`() {
        val schemas = schemas("type Query { a: Int b: Int }")
        val a = schemas.loweredSchema.requireObjectField("Query", "a")
        val b = schemas.loweredSchema.requireObjectField("Query", "b")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                resolverRegistryOf(
                    schema = schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers =
                        mapOf(
                            a to fieldResolverOf(fragment(schemas, "fragment A on Query { b }", a)) { _, _ -> 1 },
                            b to fieldResolverOf(fragment(schemas, "fragment B on Query { a }", b)) { _, _ -> 2 },
                        ),
                    variableProviders = emptyMap(),
                )
            }

        assertTrue(failure.message.orEmpty().contains("demand cycle"))
    }

    private fun schemas(sdl: String): ViaductAndGJSchema =
        ViaductAndGJSchema.fromGraphQLSchema(
            UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl)),
        )

    private fun fragment(
        schemas: ViaductAndGJSchema,
        source: String,
        owner: ViaductSchema.ObjectField,
    ): Fragment {
        val (type, selections) =
            schemas.materializeSelectionsFrom(
                source = source,
                variableField = owner,
                preserveSourceResponseKeys = true,
            )
        return Fragment.of(type, selections)
    }
}
