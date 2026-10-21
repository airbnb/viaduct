@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.registry

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.bootstrap.fromObjectField
import viaduct.engine.runtime2.bootstrap.fromQueryField
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelection
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.nodeRootFieldReferenceOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD
import viaduct.engine.runtime2.schema.lowering.loweredFieldFromSourceCoordinate
import viaduct.engine.runtime2.schema.materializeSelectionsFrom
import viaduct.engine.runtime2.schema.selectionsFrom
import viaduct.graphql.schema.ViaductSchema

class RegistryConstructionTest {
    @ParameterizedTest
    @EnumSource(ProviderFragment::class)
    fun `shared Query dependencies retain direct inputs and shared links`(root: ProviderFragment) {
        val schemas = schemas(
            "type Query { item: Item ${(0..28).joinToString(" ") { "f$it: Int" }} } type Item { value: Int }",
        )
        val schema = schemas.loweredSchema
        val query = schema.requireQueryTypeDef()
        val item = schema.requireObjectField("Query", "item")
        val empty = schema.emptyFragmentOf("Query")
        val resolvers = buildMap<ViaductSchema.Field, FieldResolverDefinition> {
            put(item, fieldResolverOf(empty) { _, _ -> engineObjectDataOf(schema.requireType("Item") as ViaductSchema.Object, mapOf("value" to 1)) })
            for (index in 0..28) {
                val field = schema.requireObjectField("Query", "f$index")
                val selections = if (index < 2) "item { value }" else "f${index - 1} f${index - 2}"
                val input = fragment(schemas, "fragment Input on Query { $selections }", field)
                put(
                    field,
                    fieldResolverOf(
                        objectFragment = if (root == ProviderFragment.OBJECT) input else empty,
                        queryFragment = if (root == ProviderFragment.QUERY) input else empty,
                    ) { _, _, _ -> 1 },
                )
            }
        }

        val registry = resolverRegistryOf(schemas, emptyMap(), resolvers, variableProviders = emptyMap())

        for (index in 0..28) {
            val template = requireNotNull(registry.precomputedConstructionDemand(schema.requireObjectField("Query", "f$index")))
            val input = requireNotNull(template.queryRootDemand)
            val direct = input.reusableDemand
            val fields = if (index < 2) setOf("item") else setOf("f${index - 1}", "f${index - 2}")
            assertEquals(fields, direct.keys().map { it.field.name }.toSet())
            assertEquals(direct.groundKeys(), input.dependencies.keys)
            input.dependencies.forEach { (key, dependency) ->
                assertSame(registry.precomputedConstructionDemand(key.field), dependency)
            }
            if (index < 2) {
                val children = direct.byKey().values.single { it.key.field == item }.subselections
                assertEquals(1, children.size)
                assertEquals(setOf("value"), children.merge(schema.requireType("Item") as ViaductSchema.Object).keys().map { it.field.name }.toSet())
            }
            assertSame(query, direct.type)
        }
    }

    @Test
    fun `rejects erroneous object and Query fragment arguments before assembly`() {
        val schemas = schemas("type Query { result(seed: Int): Int echo(value: Int): Int dependency(value: Int): Int box: Box } type Box { dependency(value: Int): Int }")
        val schema = schemas.loweredSchema
        val query = schema.requireQueryTypeDef()
        val owner = schema.requireObjectField("Query", "result")
        val variable = Arguments.Variable.of(owner, "seed")
        val symbolic = fragment(schemas, "fragment Input on Query { echo(value: ${'$'}seed) }", owner).materializeSelections
        val empty = schema.emptyFragmentOf("Query")
        for (root in ProviderFragment.entries) {
            for (nested in listOf(false, true)) {
                val invalid = Fragment.of(query, symbolic + erroneousSelections(schema, nested))
                val resolvers =
                    query.fields.filter { it.name != LOWERED_TYPENAME_FIELD }
                        .associateTo(mutableMapOf<ViaductSchema.Field, FieldResolverDefinition>()) { field ->
                            field to fieldResolverOf(empty) { _, _ -> null }
                        }
                resolvers[owner] =
                    fieldResolverOf(
                        objectFragment = if (root == ProviderFragment.OBJECT) invalid else empty,
                        queryFragment = if (root == ProviderFragment.QUERY) invalid else empty,
                    ) { _, _, _ -> null }

                val failure = assertFailsWith<IllegalArgumentException> {
                    resolverRegistryOf(
                        schema = schemas,
                        nodeResolvers = emptyMap(),
                        fieldResolvers = resolvers,
                        variableProviders = mapOf(variable to schema.fromArgument(owner, "seed")),
                    )
                }

                val typeName = if (nested) "Box" else "Query"
                assertEquals("Registry fragment contains erroneous arguments for $typeName/dependency", failure.message)
            }
        }
    }

    @Test
    fun `canonical templates reject erroneous arguments in either root`() {
        val schema = schemas("type Query { dependency(value: Int): Int box: Box } type Box { dependency(value: Int): Int }").loweredSchema
        for (root in ProviderFragment.entries) {
            for (nested in listOf(false, true)) {
                val invalid = erroneousSelections(schema, nested)
                val failure = assertFailsWith<IllegalArgumentException> {
                    ResolverFragmentTemplates(
                        objectFragmentTemplate = if (root == ProviderFragment.OBJECT) invalid else materializeSelectionForestOf(),
                        queryFragmentTemplate = if (root == ProviderFragment.QUERY) invalid else materializeSelectionForestOf(),
                    )
                }

                val typeName = if (nested) "Box" else "Query"
                assertEquals("Registry fragment contains erroneous arguments for $typeName/dependency", failure.message)
            }
        }
    }

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
    fun `dispatches canonical node references and retains their authoritative identity`() =
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
                                nodeRootFieldReferenceOf(nodeField, userType, "42")
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

    @Test
    fun `rejects potential field checker cycles in both fragment roots even under static exclusion`() {
        val schemas = schemas("type Query { checked: Int dependency: Int }")
        val schema = schemas.loweredSchema
        val checked = schema.requireObjectField("Query", "checked")
        val dependency = schema.requireObjectField("Query", "dependency")
        val empty = schema.emptyFragmentOf("Query")
        for (root in ProviderFragment.entries) {
            for (guard in listOf("", " @skip(if: true)")) {
                val inputs = fragment(schemas, "fragment Input on Query { dependency$guard }", checked).materializeSelections
                val checker = FieldCheckerResolver.of(
                    checked,
                    schema.requireQueryTypeDef(),
                    fragmentTemplates = mapOf(
                        "input" to ResolverFragmentTemplates(
                            objectFragmentTemplate = if (root == ProviderFragment.OBJECT) inputs else materializeSelectionForestOf(),
                            queryFragmentTemplate = if (root == ProviderFragment.QUERY) inputs else materializeSelectionForestOf(),
                        ),
                    ),
                ) { _, _, _ -> CheckerResult.Success }

                val failure = assertFailsWith<IllegalArgumentException>("root=$root guard=$guard") {
                    resolverRegistryOf(
                        schema = schemas,
                        nodeResolvers = emptyMap(),
                        fieldResolvers = mapOf(
                            checked to fieldResolverOf(empty) { _, _ -> 1 },
                            dependency to fieldResolverOf(fragment(schemas, "fragment Input on Query { checked }", dependency)) { _, _ -> 2 },
                        ),
                        fieldCheckers = mapOf(checked to checker),
                        variableProviders = emptyMap(),
                    )
                }
                assertTrue(failure.message.orEmpty().contains("demand cycle"))
            }
        }
    }

    @Test
    fun `rejects a type checker Query dependency cycle through an abstract return type`() {
        val schemas = schemas(
            "type Query { item: Item dependency: Int } interface Item { value: Int } type Leaf implements Item { value: Int }",
        )
        val schema = schemas.loweredSchema
        val item = schema.requireObjectField("Query", "item")
        val dependency = schema.requireObjectField("Query", "dependency")
        val leaf = schema.requireType("Leaf") as ViaductSchema.Object
        val checker = TypeCheckerResolver.of(
            leaf,
            schema.requireQueryTypeDef(),
            fragmentTemplates = mapOf(
                "input" to ResolverFragmentTemplates(
                    objectFragmentTemplate = materializeSelectionForestOf(),
                    queryFragmentTemplate = fragment(schemas, "fragment Input on Query { dependency }", dependency).materializeSelections,
                ),
            ),
        ) { _, _ -> CheckerResult.Success }
        val failure = assertFailsWith<IllegalArgumentException> {
            resolverRegistryOf(
                schema = schemas,
                nodeResolvers = emptyMap(),
                fieldResolvers = mapOf(
                    item to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> null },
                    dependency to fieldResolverOf(fragment(schemas, "fragment Input on Query { item { value } }", dependency)) { _, _ -> 1 },
                ),
                typeCheckers = mapOf(leaf to checker),
                variableProviders = emptyMap(),
            )
        }
        assertTrue(failure.message.orEmpty().contains("demand cycle"))
        assertTrue(failure.message.orEmpty().contains("type-checker:Leaf"))
    }

    @Test
    fun `checker raw self reads and mutually raw checker inputs are not dependency cycles`() {
        val schemas = schemas("type Query { first: Int second: Int }")
        val schema = schemas.loweredSchema
        val fields = listOf("first", "second").map { schema.requireObjectField("Query", it) }
        val registry = resolverRegistryOf(
            schema = schemas,
            nodeResolvers = emptyMap(),
            fieldResolvers = fields.associateWith { fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 } },
            fieldCheckers = fields.associateWith { field ->
                FieldCheckerResolver.of(
                    field,
                    schema.requireQueryTypeDef(),
                    fragmentTemplates = mapOf(
                        "input" to ResolverFragmentTemplates(
                            objectFragmentTemplate = fragment(schemas, "fragment Input on Query { first second }", field).materializeSelections,
                            queryFragmentTemplate = materializeSelectionForestOf(),
                        ),
                    ),
                ) { _, _, _ -> CheckerResult.Success }
            },
            variableProviders = emptyMap(),
        )
        fields.forEach { assertTrue(registry.fieldChecker(it) != null) }
    }

    @Test
    fun `recursive schema and raw type checker reads are allowed`() {
        val schemas = schemas("type Query { node: Item } type Item { next: Item value: Int }")
        val schema = schemas.loweredSchema
        val node = schema.requireObjectField("Query", "node")
        val item = schema.requireType("Item") as ViaductSchema.Object
        val checker = TypeCheckerResolver.of(
            item,
            schema.requireQueryTypeDef(),
            fragmentTemplates = mapOf(
                "input" to ResolverFragmentTemplates(
                    objectFragmentTemplate = fragment(schemas, "fragment Input on Item { next { value } }", node).materializeSelections,
                    queryFragmentTemplate = materializeSelectionForestOf(),
                ),
            ),
        ) { _, _ -> CheckerResult.Success }
        val registry = resolverRegistryOf(
            schema = schemas,
            nodeResolvers = emptyMap(),
            fieldResolvers = mapOf(node to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> null }),
            typeCheckers = mapOf(item to checker),
            variableProviders = emptyMap(),
        )
        assertSame(checker, registry.typeChecker(item))
    }

    private fun erroneousSelections(
        schema: ViaductSchema,
        nested: Boolean,
    ): MaterializeSelectionForest {
        val field = schema.requireObjectField(if (nested) "Box" else "Query", "dependency")
        val error =
            materializeSelectionForestOf(
                MaterializeSelection.of(
                    responseKey = "dependency",
                    key = ObjectEngineResult.GroundKey.of(field, Arguments.Error),
                    possibleTypes = setOf(field.containingDef),
                    subselections = materializeSelectionForestOf(),
                    inclusionCondition = if (nested) InclusionCondition.Never else InclusionCondition.Always,
                ),
            )
        if (!nested) return error
        val box = schema.requireObjectField("Query", "box")
        return materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = "box",
                key = ObjectEngineResult.GroundKey.of(box, emptyMap()),
                possibleTypes = setOf(box.containingDef),
                subselections = error,
            ),
        )
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
