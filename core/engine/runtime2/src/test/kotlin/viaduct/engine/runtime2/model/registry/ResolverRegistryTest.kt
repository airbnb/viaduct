@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.model.registry

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.additionalContextForResolver
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.fromSDL
import viaduct.engine.runtime2.model.testing.generatedFieldResolverOf
import viaduct.engine.runtime2.model.testing.generatedNodeResolverOf
import viaduct.engine.runtime2.model.testing.mayDemandFrom
import viaduct.engine.runtime2.model.testing.nodeReferenceOf
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.model.testing.snipToDemand
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.engine.runtime2.model.testing.withGeneratedOutputPolicy
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

class ResolverRegistryTest {
    @Test
    fun `generated node projection preserves authoritative id context and nonselective invocation`(): Unit =
        runBlocking {
            val retained = Any()
            var calls = 0
            for (selective in listOf(true, false, true)) {
                val fixture = TestWorld.fromSDL(
                    schemaSDL = "interface Node { id: ID! } type User implements Node { id: ID! name: String extra: String } type Query { user: User }",
                    fieldResolvers = { emptyMap() },
                    nodeResolvers = { schemas ->
                        val type = schemas.loweredSchema.requireType("User") as ViaductSchema.Object
                        mapOf(
                            type to nodeResolverOf { id ->
                                calls++
                                assertEquals("authoritative", id)
                                engineObjectDataOf(type, mapOf("id" to "wrong", "name" to "Ada", "extra" to "retained"), additionalContextForResolver = retained)
                            }.withGeneratedOutputPolicy(type, selective)
                        )
                    },
                )
                val field = fixture.schema.requireObjectField("Query", "node")
                val reference = fixture.schema.nodeReferenceOf("User", "authoritative")
                val world = fixture.assumptions
                val root = world.resolverRegistry.createRootQueryInput()
                for (selection in listOf("id name", "id")) {
                    val demand = fixture.schemas.fragmentFrom("fragment Demand on User { $selection }").subselections
                    val output = assertIs<EngineObjectData.Sync>(
                        world.resolverRegistry.resolver(field)(
                            input = root,
                            arguments = reference.arguments,
                            selections = demand,
                            executionContext = ResolutionExecutionContext.Unsupported,
                        )
                    )
                    val expected = selection.split(" ").toSet()
                    assertEquals(expected, output.getSelections().toSet())
                    assertEquals("authoritative", output.get("id"))
                    assertSame(retained, output.additionalContextForResolver)
                }
            }
            assertEquals(6, calls)
        }

    @Test
    fun `generated node witnesses preserve null error and forwarding on ID-only lookups`() =
        runBlocking {
            for (kind in listOf("null", "error", "reference")) {
                val observedDemand = mutableListOf<Set<String>>()
                val failure = EngineErrorData.of()
                val fixture = TestWorld.fromSDL(
                    schemaSDL = "interface Node { id: ID! } type User implements Node { id: ID! name: String } type Query { user: User }",
                    fieldResolvers = { emptyMap() },
                    nodeResolvers = { schemas ->
                        val type = schemas.loweredSchema.requireType("User") as ViaductSchema.Object
                        mapOf(
                            type to generatedNodeResolverOf { id, demand ->
                                assertEquals("authoritative", id)
                                observedDemand.add(buildSet { demand.forEach { add(it.key.field.name) } })
                                when (kind) {
                                    "null" -> null
                                    "error" -> failure
                                    else -> schemas.loweredSchema.nodeReferenceOf("User", "forwarded")
                                }
                            }.withGeneratedOutputPolicy(type, true)
                        )
                    },
                )
                val registry = fixture.resolverRegistry
                val field = fixture.schema.requireObjectField("Query", "node")
                val reference = fixture.schema.nodeReferenceOf("User", "authoritative")
                for (selection in listOf("name", "id")) {
                    val output = registry.resolver(field)(
                        input = registry.createRootQueryInput(),
                        arguments = reference.arguments,
                        selections = fixture.schemas.fragmentFrom("fragment Demand on User { $selection }").subselections,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    )
                    when (kind) {
                        "null" -> assertEquals(null, output)
                        "error" -> assertSame(failure, output)
                        else -> {
                            val forwarded = assertIs<RootFieldReferenceData>(output)
                            assertEquals(fixture.schema.nodeReferenceOf("User", "forwarded").arguments, forwarded.arguments)
                        }
                    }
                }
                assertEquals(listOf(setOf("name"), emptySet()), observedDemand)
            }
        }

    @Test
    fun `projection retains context while publishing only demanded Node and ordinary fields`() {
        val schemas = ViaductAndGJSchema.fromSDL(
            "type Query { node: User item: Item } interface Node { id: ID! } type User implements Node { id: ID! name: String } type Item { id: ID! name: String }"
        )
        for (typeName in listOf("User", "Item")) {
            val type = schemas.loweredSchema.requireType(typeName) as ViaductSchema.Object
            for (context in listOf(null, Any(), mapOf("id" to "id"))) {
                val source = engineObjectDataOf(type, mapOf("id" to "id", "name" to "Ada"), additionalContextForResolver = context)
                val demand = schemas.fragmentFrom("fragment Demand on $typeName { name }").subselections
                val projected = source.snipToDemand(demand, type)

                assertEquals(setOf("name"), projected.getSelections().toSet())
                assertSame(context, projected.additionalContextForResolver)
                assertEquals("Ada", projected.get("name"))
                val empty = source.snipToDemand(selectionForestOf(), type)
                assertTrue(empty.getSelections().none())
                assertSame(context, empty.additionalContextForResolver)
                val withId = source.snipToDemand(schemas.fragmentFrom("fragment Demand on $typeName { id name }").subselections, type)
                assertEquals(setOf("id", "name"), withId.getSelections().toSet())
                assertEquals(2, withId.getSelections().count())
                assertEquals("id", withId.get("id"))
                val withoutId = engineObjectDataOf(type, mapOf("name" to "Ada"), additionalContextForResolver = context).snipToDemand(demand, type)
                assertFalse(withoutId.isPresent("id"))
            }
        }
    }

    @Test
    fun `projection preserves opaque context recursively without publishing extra fields`() {
        val schemas = ViaductAndGJSchema.fromSDL("type Query { item: Item } type Item { value: Int extra: Int child: Item }")
        val type = schemas.loweredSchema.requireType("Item") as ViaductSchema.Object
        val parentContext = Any()
        val childContext = Any()
        val child = engineObjectDataOf(type, mapOf("value" to 2, "extra" to 3), additionalContextForResolver = childContext)
        val source = engineObjectDataOf(type, mapOf("value" to 1, "extra" to 4, "child" to child), additionalContextForResolver = parentContext)
        val demand = schemas.fragmentFrom("fragment Demand on Item { value child { value } }").subselections

        val projected = source.snipToDemand(demand, type)
        val projectedChild = projected.get("child") as EngineObjectData.Sync

        assertSame(parentContext, projected.additionalContextForResolver)
        assertSame(childContext, projectedChild.additionalContextForResolver)
        assertEquals(setOf("value", "child"), projected.getSelections().toSet())
        assertEquals(listOf("value"), projectedChild.getSelections().toList())
        assertEquals(2, projectedChild.get("value"))
    }

    @Test
    fun `definition function mapping preserves fragments variables and demand handling`(): Unit =
        runBlocking {
            val schemas = ViaductAndGJSchema.fromSDL("type Query { user: User source: String } type User { name: String age: Int }")
            val schema = schemas.loweredSchema
            val query = schema.requireQueryTypeDef()
            val user = schema.requireType("User") as ViaductSchema.Object
            val field = schema.requireObjectField("Query", "user")
            val variable = Arguments.Variable.of(field, "flag")
            val fragment = schemas.fragmentFrom("fragment Input on Query { source @include(if: ${'$'}flag) }", variableField = field)
            val projectedDemand = schemas.fragmentFrom("fragment Demand on User { name }").subselections
            val definition = fieldResolverOf(fragment, fragment) { _, _, _, _, _ ->
                error("Original callback must not run")
            }.withVariablesProvider(setOf("flag")) { mapOf("flag" to true) }

            fun assemble(definition: FieldResolverDefinition): FieldValueResolver =
                resolverRegistryOf(
                    schema = schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers = mapOf(
                        field to definition,
                        schema.requireObjectField("Query", "source") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "source" },
                    ),
                    variableProviders = emptyMap(),
                ).resolver(field)
            val original = assemble(definition)
            var calls = 0
            val input = engineObjectDataOf(query)
            val resolverContext = Any()
            val arguments = Arguments.Resolved.of(field, emptyMap())
            val demand = schemas.fragmentFrom("fragment Demand on User { age }").subselections
            val replacement = assemble(
                definition.mapFunction { _ ->
                    { actualInput, queryValue, actualArguments, selections, additionalContext, context ->
                        calls++
                        assertSame(input, actualInput)
                        assertSame(input, queryValue)
                        assertSame(arguments, actualArguments)
                        assertSame(projectedDemand, selections)
                        assertSame(resolverContext, additionalContext)
                        assertSame(ResolutionExecutionContext.Unsupported, context)
                        engineObjectDataOf(user, mapOf("name" to "replacement", "age" to 37))
                    }
                }.mapDemand { projectedDemand }
            )

            assertSame(field, replacement.field)
            assertEquals(original.target, replacement.target)
            assertEquals(original.declaredDependencies.variables, replacement.declaredDependencies.variables)
            assertSame(original.declaredDependencies.variablesProvider, replacement.declaredDependencies.variablesProvider)
            assertEquals(original.declaredDependencies.objectFragment.keys(), replacement.declaredDependencies.objectFragment.keys())
            assertEquals(original.declaredDependencies.queryFragment.keys(), replacement.declaredDependencies.queryFragment.keys())
            val occurrence = ResolverOccurrenceId.at(schema.testRoot(), listOf(ObjectEngineResult.GroundKey.of(field, emptyMap())))
            for (resolver in listOf(original, replacement)) {
                assertEquals(setOf(variable.instantiate(occurrence)), resolver.declaredDependencies.instantiateObjectMaterializationSelections(occurrence).usedVariables())
                assertEquals(setOf(variable.instantiate(occurrence)), resolver.declaredDependencies.instantiateQueryMaterializationSelections(occurrence).usedVariables())
            }
            val invoked = replacement(input, input, arguments, demand, ResolutionExecutionContext.Unsupported, resolverContext)
            val result = assertIs<EngineObjectData.Sync>(invoked)
            assertEquals(setOf("name", "age"), result.getSelections().toSet())
            assertEquals("replacement", result.get("name"))
            assertEquals(1, calls)
        }

    @Test
    fun `definition function mapping preserves selective output behavior`(): Unit =
        runBlocking {
            val schemas = ViaductAndGJSchema.fromSDL("type Query { user: User } type User { name: String age: Int }")
            val schema = schemas.loweredSchema
            val query = schema.requireQueryTypeDef()
            val field = schema.requireObjectField("Query", "user")
            val definition = selectiveFieldResolverOf(schema.emptyFragmentOf("Query"), schema.emptyFragmentOf("Query")) {
                    _, _, _, _, _, _ ->
                error("Original callback must not run")
            }
            val value = engineObjectDataOf(schema.requireType("User") as ViaductSchema.Object, mapOf("name" to "Ada", "age" to 37))
            val resolverContext = Any()
            val replacement = resolverRegistryOf(
                schema = schemas,
                nodeResolvers = emptyMap(),
                variableProviders = emptyMap(),
                fieldResolvers = mapOf(
                    field to definition.mapFunction { _ ->
                        { _, _, _, _, additionalContext, _ ->
                            assertSame(resolverContext, additionalContext)
                            value
                        }
                    }
                ),
            ).resolver(field)
            val input = engineObjectDataOf(query)
            val arguments = Arguments.Resolved.of(field, emptyMap())
            val demand = schemas.fragmentFrom("fragment Demand on User { name }").subselections

            assertSame(value, replacement(input, input, arguments, demand, ResolutionExecutionContext.Unsupported, resolverContext))
        }

    @Test
    fun `generated projection is chosen during construction and retains complete source values`(): Unit =
        runBlocking {
            for (selective in listOf(true, false)) {
                lateinit var complete: EngineObjectData.Sync
                val fixture = TestWorld.fromSDL(
                    "type Query { items: [Item] } type Item { name: String extra: String children: [Item] }",
                    fieldResolvers = { schemas ->
                        val type = schemas.loweredSchema.requireType("Item") as ViaductSchema.Object
                        val child = engineObjectDataOf(type, mapOf("name" to "child", "extra" to "retained"))
                        complete = engineObjectDataOf(type, mapOf("name" to "parent", "extra" to "retained", "children" to listOf(child, null)))
                        val field = schemas.loweredSchema.requireObjectField("Query", "items")
                        val empty = schemas.loweredSchema.emptyFragmentOf("Query")
                        mapOf(field to generatedFieldResolverOf(field, empty, empty, selective) { _, _, _ -> listOf(complete, null) })
                    },
                )
                val world = fixture.assumptions
                val field = world.schema.requireObjectField("Query", "items")
                val root = world.resolverRegistry.createRootQueryInput()
                val demand = fixture.schemas.fragmentFrom("fragment Demand on Item { name children { name } }").subselections

                suspend fun invoke(world: Assumptions) =
                    assertIs<List<*>>(
                        world.resolverRegistry.resolver(field)(root, root, Arguments.Resolved.of(field, emptyMap()), demand, ResolutionExecutionContext.Unsupported),
                    )

                val selected = invoke(world)
                val item = assertIs<EngineObjectData.Sync>(selected[0])
                assertEquals(if (selective) setOf("name", "children") else setOf("name", "extra", "children"), item.getSelections().toSet())
                assertEquals("parent", item.get("name"))
                val children = assertIs<List<*>>(item.outputValue("children"))
                assertEquals(if (selective) setOf("name") else setOf("name", "extra"), assertIs<EngineObjectData.Sync>(children[0]).getSelections().toSet())
                assertEquals(null, children[1])
                assertEquals(null, selected[1])
                assertEquals("retained", complete.get("extra"))
            }
        }

    @Test
    fun `projects delegating Sync objects recursively using canonical declared types`() {
        val schemas = ViaductAndGJSchema.fromSDL(
            """
            type Query { container: Container }
            type Container { items: [Item] }
            interface Item { selected: Int extra: Int }
            type A implements Item { selected: Int extra: Int }
            type B implements Item { selected: Int extra: Int }
            """.trimIndent(),
        )
        val schema = schemas.loweredSchema
        val source = object : EngineObjectData.Sync by ResolvedEngineObjectData(
            schemas.graphQLSchema.getObjectType("Container"),
            mapOf(
                "items" to listOf(
                    object : EngineObjectData.Sync by ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("A"), mapOf("selected" to 1, "extra" to 3)) {},
                    object : EngineObjectData.Sync by ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("B"), mapOf("selected" to null, "extra" to 2)) {},
                    null,
                ),
            ),
        ) {}
        val demand = schemas.fragmentFrom("fragment Demand on Container { items { selected ... on A { extra } } }").subselections

        val projected = assertIs<EngineObjectData.Sync>(source.snipToDemand(demand, schema.requireType("Container") as ViaductSchema.Object))
        val items = assertIs<List<*>>(projected.outputValue("items"))
        val a = assertIs<EngineObjectData.Sync>(items[0])
        val b = assertIs<EngineObjectData.Sync>(items[1])

        assertSame(schema.requireType("A"), a.schemaType)
        assertEquals(setOf("selected", "extra"), a.getSelections().toSet())
        assertEquals(1, a.outputValue("selected"))
        assertEquals(3, a.outputValue("extra"))
        assertSame(schema.requireType("B"), b.schemaType)
        assertEquals(listOf("selected"), b.getSelections().toList())
        assertTrue(b.isPresent("selected"))
        assertEquals(null, b.outputValue("selected"))
        assertFalse(b.isPresent("extra"))
        assertEquals(null, items[2])
    }

    @Test
    fun `stores type checkers by their canonical concrete object target`() {
        lateinit var suppliedChecker: TypeCheckerResolver
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query { item: Item }
                    type Item { value: Int }
                    """.trimIndent(),
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    suppliedChecker =
                        TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            CheckerResult.Success
                        }
                    mapOf(item to suppliedChecker)
                },
            )

        val item = world.schema.requireType("Item") as ViaductSchema.Object
        assertSame(suppliedChecker, world.resolverRegistry.typeChecker(item))
        assertEquals(null, world.resolverRegistry.typeChecker(world.schema.requireQueryTypeDef()))
    }

    @Test
    fun `rejects a type checker registered under a different object target`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query { item: Item }
                    type Item { value: Int }
                    type Other { value: Int }
                    """.trimIndent(),
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    val other = schema.loweredSchema.requireType("Other") as ViaductSchema.Object
                    mapOf(
                        item to
                            TypeCheckerResolver.of(other, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                                CheckerResult.Success
                            },
                    )
                },
            )
        }
    }

    @Test
    fun `selective field resolver receives demand without output projection`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                        """.trimIndent(),
                )
            val schema = world.schemas
            val query = schema.loweredSchema.requireQueryTypeDef()
            val userField = schema.loweredSchema.requireObjectField("Query", "user")
            val demand =
                schema.fragmentFrom(
                    "fragment ignored on User { name }",
                ).subselections
            var observedDemand: SelectionForest? = null
            var observedExecutionContext: ResolutionExecutionContext? = null
            val retainedContext = Any()
            var observedAdditionalContext: Any? = null
            val resolver =
                FieldValueResolver.ofSelective(
                    field = userField,
                    fragmentTemplates =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate = materializeSelectionForestOf(),
                            queryFragmentTemplate = materializeSelectionForestOf(),
                        ),
                    queryType = query,
                    function = { _, _, _, selections, additionalContext, executionContext ->
                        observedAdditionalContext = additionalContext
                        observedDemand = selections
                        observedExecutionContext = executionContext
                        schema.loweredSchema.objectOf("User") {
                            "name" setTo "Ada"
                            "age" setTo 37
                        }
                    },
                )

            val output =
                resolver(
                    input = engineObjectDataOf(query),
                    arguments = Arguments.Resolved.of(userField, emptyMap()),
                    selections = demand,
                    executionContext = ResolutionExecutionContext.Unsupported,
                    additionalContextForResolver = retainedContext,
                )

            assertSame(demand, observedDemand)
            assertSame(ResolutionExecutionContext.Unsupported, observedExecutionContext)
            assertSame(retainedContext, observedAdditionalContext)
            assertEquals(setOf("name", "age"), assertIs<EngineObjectData.Sync>(output).getSelections().toSet())
        }

    @Test
    fun `nonselective field resolver factory preserves complete output`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                        """.trimIndent(),
                )
            val schema = world.schemas
            val query = schema.loweredSchema.requireQueryTypeDef()
            val userField = schema.loweredSchema.requireObjectField("Query", "user")
            val retainedContext = Any()
            var observedAdditionalContext: Any? = null
            val resolver =
                FieldValueResolver.of(
                    field = userField,
                    fragmentTemplates =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate = materializeSelectionForestOf(),
                            queryFragmentTemplate = materializeSelectionForestOf(),
                        ),
                    queryType = query,
                    function = { _, _, _, additionalContext, _ ->
                        observedAdditionalContext = additionalContext
                        schema.loweredSchema.objectOf("User") {
                            "name" setTo "Ada"
                            "age" setTo 37
                        }
                    },
                )

            val output =
                resolver(
                    input = engineObjectDataOf(query),
                    arguments = Arguments.Resolved.of(userField, emptyMap()),
                    selections =
                        schema.fragmentFrom(
                            "fragment ignored on User { name }",
                        ).subselections,
                    executionContext = ResolutionExecutionContext.Unsupported,
                    additionalContextForResolver = retainedContext,
                )

            assertSame(retainedContext, observedAdditionalContext)
            assertEquals(setOf("name", "age"), assertIs<EngineObjectData.Sync>(output).getSelections().toSet())
        }

    @Test
    fun `selection-aware nonselective resolver receives demand and preserves complete output`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                        """.trimIndent(),
                )
            val schema = world.schemas
            val query = schema.loweredSchema.requireQueryTypeDef()
            val userField = schema.loweredSchema.requireObjectField("Query", "user")
            val demand =
                schema.fragmentFrom(
                    "fragment ignored on User { name }",
                ).subselections
            var observedDemand: SelectionForest? = null
            val retainedContext = Any()
            var observedAdditionalContext: Any? = null
            val resolver =
                FieldValueResolver.ofSelectionAwareNonselective(
                    field = userField,
                    fragmentTemplates =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate = materializeSelectionForestOf(),
                            queryFragmentTemplate = materializeSelectionForestOf(),
                        ),
                    queryType = query,
                    function = { _, _, _, selections, additionalContext, _ ->
                        observedAdditionalContext = additionalContext
                        observedDemand = selections
                        schema.loweredSchema.objectOf("User") {
                            "name" setTo "Ada"
                            "age" setTo 37
                        }
                    },
                )

            val output =
                resolver(
                    input = engineObjectDataOf(query),
                    arguments = Arguments.Resolved.of(userField, emptyMap()),
                    selections = demand,
                    executionContext = ResolutionExecutionContext.Unsupported,
                    additionalContextForResolver = retainedContext,
                )

            assertSame(demand, observedDemand)
            assertSame(retainedContext, observedAdditionalContext)
            assertEquals(setOf("name", "age"), assertIs<EngineObjectData.Sync>(output).getSelections().toSet())
        }

    @Test
    fun `definition function wrapping preserves additional context and complete output`(): Unit =
        runBlocking {
            val schemas = ViaductAndGJSchema.fromSDL(
                """
            type Query { user: User }
            type User { name: String age: Int }
                """.trimIndent(),
            )
            val schema = schemas.loweredSchema
            val query = schema.requireQueryTypeDef()
            val field = schema.requireObjectField("Query", "user")
            val definition = fieldResolverOf(schema.emptyFragmentOf("Query"), schema.emptyFragmentOf("Query")) {
                    _, _, _, additionalContext, _ ->
                engineObjectDataOf(schema.requireType("User") as ViaductSchema.Object, mapOf("name" to additionalContext, "age" to 37))
            }
            val wrapped = resolverRegistryOf(
                schema = schemas,
                nodeResolvers = emptyMap(),
                variableProviders = emptyMap(),
                fieldResolvers = mapOf(
                    field to definition.mapFunction { function ->
                        { input, queryValue, arguments, demand, additionalContext, context ->
                            function(input, queryValue, arguments, demand, "wrapped $additionalContext", context)
                        }
                    }
                ),
            ).resolver(field)
            val input = engineObjectDataOf(query)
            val arguments = Arguments.Resolved.of(field, emptyMap())
            val demand = schemas.fragmentFrom("fragment Demand on User { name }").subselections

            val invoked = wrapped.invoke(input, input, arguments, demand, ResolutionExecutionContext.Unsupported, "Ada")
            val defaultContext = wrapped.invoke(input, input, arguments, demand, ResolutionExecutionContext.Unsupported)

            val user = assertIs<EngineObjectData.Sync>(invoked)
            assertEquals(setOf("name", "age"), user.getSelections().toSet())
            assertEquals("wrapped Ada", user.get("name"))
            assertEquals(37, user.get("age"))
            assertEquals("wrapped null", assertIs<EngineObjectData.Sync>(defaultContext).get("name"))
        }

    @Test
    fun `lowers node and field resolvers to field coordinates`(): Unit =
        runBlocking {
            val observedFields = mutableListOf<String>()
            val world =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    nodeResolvers = { schema ->
                        val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                        mapOf(
                            user to
                                nodeResolverOf { id ->
                                    observedFields += "node"
                                    assertEquals("42", id)
                                    schema.loweredSchema.objectOf("User") {
                                        "id" setTo "lookup-id"
                                        "name" setTo "Ada"
                                    }
                                },
                        )
                    },
                    fieldResolvers = { schema ->
                        val userField = schema.loweredSchema.requireField("Query", "user")
                        val queryFragment = schema.loweredSchema.emptyFragmentOf("Query")
                        mapOf<ViaductSchema.Field, FieldResolverDefinition>(
                            userField to
                                fieldResolverOf(
                                    objectFragment = queryFragment,
                                    function = { parent, arguments ->
                                        observedFields += "user"
                                        assertEquals(schema.loweredSchema.requireQueryTypeDef(), parent.schemaType)
                                        assertTrue(parent.getSelections().none())
                                        assertTrue(arguments.fieldValues.isEmpty())
                                        schema.loweredSchema.nodeReferenceOf("User", "42")
                                    },
                                ),
                        )
                    },
                )
            val schema = world.schemas
            val query = schema.loweredSchema.objectOf("Query")
            val user =
                schema.loweredSchema.objectOf("User") {
                    "id" setTo "42"
                    "name" setTo "Ada"
                }
            val userField = schema.loweredSchema.requireObjectField("Query", "user")
            val queryNode = schema.loweredSchema.requireObjectField("Query", "node")
            val registry = world.resolverRegistry
            val assumptions = world.assumptions

            assertEquals(registry, assumptions.resolverRegistry)
            assertTrue(userField in registry)
            assertTrue(queryNode in registry)
            assertTrue(registry.mayDemandFrom(userField).isEmpty())
            assertTrue(registry.mayDemandFrom(queryNode).isEmpty())
            val reference =
                registry
                    .resolver(userField)(
                    input = query,
                    arguments = Arguments.Resolved.of(userField, emptyMap()),
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            val nodeReference = assertIs<RootFieldReferenceData>(reference)
            assertEquals("User", nodeReference.nodeReferenceIdentityOrNull()?.type?.name)
            assertEquals("42", nodeReference.nodeReferenceIdentityOrNull()?.id)
            val nodeValue =
                assertIs<EngineObjectData.Sync>(
                    registry.resolver(queryNode)(
                        input = query,
                        arguments = nodeReference.arguments,
                        selections =
                            schema.fragmentFrom(
                                """
                            fragment ignored on User {
                              id
                              name
                            }
                                """.trimIndent(),
                            ).subselections,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    ),
                )
            assertEquals(user.schemaType, nodeValue.schemaType)
            assertEquals(user.getSelections().toSet(), nodeValue.getSelections().toSet())
            user.getSelections().forEach { selection ->
                assertEquals(user.get(selection), nodeValue.get(selection))
            }
            assertEquals(listOf("user", "node"), observedFields)
        }

    @Test
    fun `field resolver receives response-preserving query fragment value`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                    type Query {
                      source: Int!
                      consumer: Int!
                    }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val query = schema.loweredSchema.requireQueryTypeDef()
                        val source = schema.loweredSchema.requireObjectField("Query", "source")
                        val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                        mapOf(
                            source to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                            consumer to
                                fieldResolverOf(
                                    objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            """
                                        fragment ignored on Query {
                                          aliased: source
                                        }
                                            """.trimIndent(),
                                        ),
                                ) { _, queryValue, _ ->
                                    assertEquals(query, queryValue.schemaType)
                                    queryValue.get("aliased")
                                },
                        )
                    },
                )
            val schema = world.schemas
            val query = schema.loweredSchema.requireQueryTypeDef()
            val source = schema.loweredSchema.requireObjectField("Query", "source")
            val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
            val resolver = world.resolverRegistry.resolver(consumer)
            val queryValue =
                engineObjectDataOf(
                    schemaType = query,
                    fields =
                        listOf(
                            EngineObjectDataEntry.of(
                                selection = "aliased",
                                field = source,
                                value = 7,
                            ),
                        ),
                )

            val result =
                resolver(
                    input = engineObjectDataOf(query),
                    queryValue = queryValue,
                    arguments = Arguments.Resolved.of(consumer, emptyMap()),
                    executionContext = ResolutionExecutionContext.Unsupported,
                )

            assertEquals(7, result)
            val resolverOccurrenceId = ResolverOccurrenceId.at(schema.loweredSchema.testRoot(), emptyList())
            assertEquals(
                "aliased",
                resolver
                    .declaredDependencies.instantiateQueryMaterializationSelections(resolverOccurrenceId)
                    .single()
                    .responseKey,
            )
        }

    @Test
    fun `root query input is an empty query object`() {
        val fixture = Fixture()
        val root = fixture.assumptions.resolverRegistry.createRootQueryInput()

        assertEquals(fixture.schema.loweredSchema.requireQueryTypeDef(), root.schemaType)
        assertEquals(emptySet(), root.getSelections().toSet())
    }

    @Test
    fun `preserves every Node source list layer`() {
        val schema =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Query {
                      user: User!
                      users: [User!]!
                      matrix: [[User!]!]!
                    }
                    """.trimIndent(),
            ).schemas

        assertNotNull(schema.loweredSchema.requireQueryTypeDef().field("user"))
        assertNotNull(schema.loweredSchema.requireQueryTypeDef().field("users"))
        assertNotNull(schema.loweredSchema.requireQueryTypeDef().field("matrix"))
        val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
        val node = schema.loweredSchema.requireType("Node") as ViaductSchema.Interface
        assertEquals(setOf(user), node.possibleObjectTypes)
        val matrix = schema.loweredSchema.requireField("Query", "matrix")
        val inner = checkNotNull(matrix.type.unwrapList())
        val element = checkNotNull(inner.unwrapList())
        assertEquals(user, element.baseTypeDef)
        assertFalse(matrix.type.isNullable)
        assertFalse(inner.isNullable)
        assertFalse(element.isNullable)
    }

    @Test
    fun `Query node dispatch keeps producer arguments separate`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Query {
                      seed: ID!
                      user(id: ID!): User!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                    mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                },
                fieldResolvers = { schema ->
                    val user = schema.loweredSchema.requireField("Query", "user")
                    mapOf(
                        schema.loweredSchema.requireField("Query", "seed") to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        user to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment ignored on Query { seed }",
                                    ),
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val schema = world.schemas
        val producer = schema.loweredSchema.requireObjectField("Query", "user")
        val queryNode = schema.loweredSchema.requireObjectField("Query", "node")

        assertEquals(setOf("id"), producer.args.mapTo(linkedSetOf(), ViaductSchema.FieldArg::name))
        assertTrue(world.resolverRegistry.resolver(producer).declaredDependencies.variables.isEmpty())
        val nodeResolver = world.resolverRegistry.resolver(queryNode)
        assertTrue(nodeResolver.declaredDependencies.variables.isEmpty())
        assertEquals(setOf("id"), queryNode.args.mapTo(linkedSetOf(), ViaductSchema.FieldArg::name))
        assertTrue(nodeResolver.declaredDependencies.objectFragment.isEmpty())
    }

    @Test
    fun `field resolvers return the complete nullable output-value algebra`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                    type Query {
                      scalar: String!
                      list: [String]
                      nullable: String
                      failed: String
                    }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val fragment = schema.loweredSchema.emptyFragmentOf("Query")
                        mapOf<ViaductSchema.Field, FieldResolverDefinition>(
                            schema.loweredSchema.requireField("Query", "scalar") to
                                fieldResolverOf(fragment) { _, _ -> "value" },
                            schema.loweredSchema.requireField("Query", "list") to
                                fieldResolverOf(fragment) { _, _ ->
                                    listOf("value", null)
                                },
                            schema.loweredSchema.requireField("Query", "nullable") to
                                fieldResolverOf(fragment) { _, _ -> null },
                            schema.loweredSchema.requireField("Query", "failed") to
                                fieldResolverOf(fragment) { _, _ -> EngineErrorData.of() },
                        )
                    },
                )
            val schema = world.schemas
            val parent = schema.loweredSchema.objectOf("Query")
            val outputs =
                listOf("scalar", "list", "nullable", "failed").associateWith { fieldName ->
                    val field = schema.loweredSchema.requireObjectField("Query", fieldName)
                    world.resolverRegistry
                        .resolver(field)(
                        input = parent,
                        arguments = Arguments.Resolved.of(field, emptyMap()),
                        selections = selectionForestOf(),
                        executionContext = ResolutionExecutionContext.Unsupported,
                    )
                }

            assertEquals("value", outputs.getValue("scalar"))
            assertEquals(
                listOf("value", null),
                outputs.getValue("list"),
            )
            assertEquals(null, outputs.getValue("nullable"))
            assertIs<EngineErrorData>(outputs.getValue("failed"))

            outputs.forEach { (fieldName, output) ->
                val projection =
                    with(world.assumptions) {
                        output.snipToDemand(selectionForestOf(), schema.loweredSchema.requireObjectField("Query", fieldName).outputType)
                    }
                assertEquals(output, projection)
            }
        }

    @Test
    fun `reports missing executor coordinates`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schemas
        val registry = world.resolverRegistry
        val userField = schema.loweredSchema.requireObjectField("User", "name")

        val missingField =
            assertFailsWith<MissingResolverException> {
                registry.resolver(userField)
            }
        assertEquals("User", missingField.typeName)
        assertEquals("name", missingField.fieldName)
    }

    @Test
    fun `rejects a field resolver whose object fragment is not its canonical parent type`() {
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { schema ->
                schema.requireType("User") as ViaductSchema.Object
            }
        }

        val foreignQuery = TestWorld.fromSDL(SCHEMA_SDL).schema.requireQueryTypeDef()
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { foreignQuery }
        }
    }

    @Test
    fun `rejects foreign resolver coordinate definitions`() {
        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schemas
        val foreignUser = foreignSchema.loweredSchema.requireType("User") as ViaductSchema.Object
        val foreignUserField = foreignSchema.loweredSchema.requireField("Query", "user")

        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = {
                    mapOf(foreignUser to nodeResolverOf { _: String -> error("Not invoked") })
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val queryFragment = schema.loweredSchema.emptyFragmentOf("Query")
                    mapOf(
                        foreignUserField to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        }
    }

    @Test
    fun `node resolver registration requires a canonical Node interface`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      id: ID!
                    }

                    type Query {
                      user: User
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                    mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                },
            )
        }
    }

    @Test
    fun `rejects node resolvers for object types that do not implement Node`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Other {
                      id: ID!
                    }

                    type Query {
                      user: User
                      other: Other
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val other = schema.loweredSchema.requireType("Other") as ViaductSchema.Object
                    mapOf(other to nodeResolverOf { _: String -> error("Not invoked") })
                },
            )
        }
    }

    @Test
    fun `rejects field resolvers registered at abstract coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val queryFragment = schema.loweredSchema.emptyFragmentOf("Query")
                    val nodeFragment = schema.loweredSchema.emptyFragmentOf("Node")
                    mapOf(
                        schema.loweredSchema.requireField("Query", "user") to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.loweredSchema.requireField("Node", "name") to
                            fieldResolverOf(
                                objectFragment = nodeFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        }
    }

    @Test
    fun `fills missing Query resolvers by nullability and preserves supplied resolvers`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL = "type Query { supplied: Int, nullable: Int, required: Int! }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireField("Query", "supplied") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        )
                    },
                )
            val schema = world.schemas
            val registry = world.resolverRegistry
            val query = schema.loweredSchema.objectOf("Query")

            suspend fun resolve(fieldName: String): Any? {
                val field = schema.loweredSchema.requireObjectField("Query", fieldName)
                return registry.resolver(field)(
                    input = query,
                    arguments = Arguments.Resolved.of(field, emptyMap()),
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }

            assertEquals(7, resolve("supplied"))
            assertEquals(null, resolve("nullable"))
            assertIs<EngineErrorData>(resolve("required"))
        }

    @Test
    fun `requires a field resolver for every Query field in a canonical registry`() {
        val schema = ViaductAndGJSchema.fromSDL("type Query { missing: Int }")
        assertFailsWith<IllegalArgumentException> {
            resolverRegistryOf(
                schema = schema,
                nodeResolvers = emptyMap(),
                fieldResolvers = emptyMap(),
                variableProviders = emptyMap(),
            )
        }
    }

    @Test
    fun `rejects field resolvers for node id and generated typename fields`() {
        listOf("id", "V_A_typename").forEach { fieldName ->
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    nodeResolvers = { schema ->
                        val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                        mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                    },
                    fieldResolvers = { schema ->
                        val fragment = schema.loweredSchema.emptyFragmentOf("User")
                        mapOf(
                            schema.loweredSchema.requireField("User", fieldName) to
                                fieldResolverOf(
                                    objectFragment = fragment,
                                    function = { _, _ -> error("Not invoked") },
                                ),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `snips selected fields recursively through objects and lists`() {
        val world =
            TestWorld.fromSDL(
                """
                type Record {
                  id: ID!
                  name: String!
                  friend: Record
                  peers: [Record]
                }

                type Query {
                  record: Record
                }
                """.trimIndent(),
            )
        val schema = world.schemas
        val record = schema.loweredSchema.requireType("Record") as ViaductSchema.Object

        fun key(fieldName: String): ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                schema.loweredSchema.requireObjectField("Record", fieldName),
                emptyMap(),
            )

        fun selection(
            fieldName: String,
            subselections: SelectionForest = selectionForestOf(),
        ): Selection =
            Selection.of(
                key = key(fieldName),
                possibleTypes = setOf(record),
                subselections = subselections,
            )
        val friend =
            schema.loweredSchema.objectOf("Record") {
                "id" setTo "friend"
                "name" setTo "Friend"
            }
        val peer =
            schema.loweredSchema.objectOf("Record") {
                "id" setTo "peer"
                "name" setTo "Peer"
            }
        val source =
            schema.loweredSchema.objectOf("Record") {
                "id" setTo "target"
                "name" setTo "Target"
                "friend" setTo friend
                "peers" setTo listOf(peer, null)
            }
        val idSelection = selection("id")
        val nameSelection = selection("name")
        val selections =
            selectionForestOf(
                idSelection,
                selection("friend", selectionForestOf(idSelection)),
                selection("peers", selectionForestOf(nameSelection)),
            ) +
                selectionForestOf(
                    Selection.of(
                        key = key("name"),
                        possibleTypes = emptySet(),
                        subselections = selectionForestOf(),
                    ),
                )

        val result =
            assertIs<EngineObjectData.Sync>(
                with(world.assumptions) {
                    source.snipToDemand(selections)
                },
            )

        assertEquals(
            setOf(
                "id",
                "friend",
                "peers",
            ),
            result.getSelections().toSet(),
        )
        assertEquals(
            "target",
            result.get("id"),
        )
        val snippedFriend =
            assertIs<EngineObjectData.Sync>(result.get("friend"))
        assertEquals(
            setOf("id"),
            snippedFriend.getSelections().toSet(),
        )
        val peers = assertIs<List<*>>(result.get("peers"))
        val snippedPeer = assertIs<EngineObjectData.Sync>(peers.first())
        assertEquals(
            setOf("name"),
            snippedPeer.getSelections().toSet(),
        )
        assertEquals(null, peers.last())
    }

    @Test
    fun `snipToDemand omits selections conditioned on another concrete type`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on Admin {
                              level
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(emptySet(), result.getSelections().toSet())
    }

    @Test
    fun `field-resolver snipToDemand stops before an argument-bearing field resolver`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on User {
                              search {
                                id
                              }
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(emptySet(), result.getSelections().toSet())
    }

    @Test
    fun `snipToDemand rejects a present argument-bearing field`() {
        val fixture = Fixture()
        val search = fixture.schema.loweredSchema.requireObjectField("User", "search")
        val source =
            engineObjectDataOf(
                schemaType = fixture.user,
                fields =
                    listOf(
                        EngineObjectDataEntry.of(
                            selection = search.name,
                            field = search,
                            value = null,
                        ),
                    ),
            )
        val demand =
            fixture.schema.fragmentFrom(
                """
                fragment ignored on User {
                  search(limit: 1) {
                    id
                  }
                }
                """.trimIndent(),
            ).subselections

        val failure =
            assertFailsWith<IllegalArgumentException> {
                source.snipToDemand(demand)
            }

        assertEquals(
            "Resolver output must not supply argument-bearing field User/search",
            failure.message,
        )
    }

    @Test
    @Ignore("Runtime argument-bearing resolver-output validation is disabled")
    fun `field resolver rejects output containing an argument-bearing field`(): Unit =
        runBlocking {
            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                    type Item {
                      value(index: Int): String
                    }

                    type Query {
                      item: Item!
                    }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val itemType = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                        val valueField = schema.loweredSchema.requireObjectField("Item", "value")
                        mapOf(
                            schema.loweredSchema.requireObjectField("Query", "item") to
                                fieldResolverOf(
                                    objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                    function = { _, _ ->
                                        engineObjectDataOf(
                                            schemaType = itemType,
                                            fields =
                                                listOf(
                                                    EngineObjectDataEntry.of(
                                                        selection = valueField.name,
                                                        field = valueField,
                                                        value = "one",
                                                    ),
                                                ),
                                        )
                                    },
                                ),
                        )
                    },
                )
            val world = testWorld.assumptions
            val itemField = world.schema.requireObjectField("Query", "item")
            val resolver = world.resolverRegistry.resolver(itemField)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    resolver(
                        input = world.schema.objectOf("Query"),
                        arguments = Arguments.Resolved.of(itemField, emptyMap()),
                        executionContext = ResolutionExecutionContext.Unsupported,
                    )
                }

            assertEquals(
                "Resolver output must not supply argument-bearing field Item/value",
                failure.message,
            )
        }

    @Test
    fun `snipToDemand does not expand resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      firstName: String!
                      lastName: String!
                      greeting: String!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "viewer") to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.loweredSchema.requireField("User", "greeting") to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          firstName
                                          lastName
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val source =
            world.objectOf("User") {
                "firstName" setTo "Ada"
                "lastName" setTo "Lovelace"
            }
        val demand =
            testWorld.schemas.fragmentFrom(
                """
                fragment ignored on User {
                  greeting
                }
                """.trimIndent(),
            ).subselections

        val result =
            assertIs<EngineObjectData.Sync>(
                source.snipToDemand(demand),
            )

        assertEquals(emptySet(), result.getSelections().toSet())
    }

    @Test
    fun `snipToDemand retains present resolver fields including null and error values`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      value: Int
                      nullValue: Int
                      errorValue: Int
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    listOf("value", "nullValue", "errorValue").associate { fieldName ->
                        schema.loweredSchema.requireField("Item", fieldName) to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Item"),
                                function = { _, _ -> error("Not invoked") },
                            )
                    }
                },
            )
        val world = testWorld.assumptions
        val error = EngineErrorData.of()
        val source =
            world.objectOf("Item") {
                "value" setTo 7
                "nullValue" setTo null
                "errorValue" setTo error
            }
        val demand =
            testWorld.schemas.fragmentFrom(
                """
                fragment ignored on Item {
                  value
                  nullValue
                  errorValue
                }
                """.trimIndent(),
            ).subselections

        val result =
            assertIs<EngineObjectData.Sync>(
                source.snipToDemand(demand),
            )

        assertEquals(setOf("value", "nullValue", "errorValue"), result.getSelections().toSet())
        assertEquals(7, result.get("value"))
        assertTrue(result.isPresent("nullValue"))
        assertEquals(null, result.get("nullValue"))
        assertEquals(error, result.outputValue("errorValue"))
    }

    @Test
    fun `field-resolver snipToDemand has no implicit node ownership boundary`() {
        val fixture = Fixture(withNodeResolver = true)
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
                "name" setTo "Target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on Node {
                              name
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(
            setOf("name"),
            result.getSelections().toSet(),
        )
    }

    @Test
    fun `selection factory distinguishes leaves from empty composites`() {
        val fixture = Fixture()

        val leaf = fixture.selection("Node", "id")
        val emptyComposite = fixture.selection("User", "friend")

        assertTrue(leaf.isLeaf)
        assertTrue(leaf.subselections.isEmpty())
        assertFalse(emptyComposite.isLeaf)
        assertTrue(emptyComposite.subselections.isEmpty())
    }

    private fun worldWithFragmentType(fragmentType: (ViaductSchema) -> ViaductSchema.CompositeTypeDef): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val fragment =
                    Fragment.of(fragmentType(schema.loweredSchema), selectionForestOf())
                val userField = schema.loweredSchema.requireField("Query", "user")
                mapOf(
                    userField to
                        fieldResolverOf(
                            objectFragment = fragment,
                            function = { _, _ -> schema.loweredSchema.objectOf("User") },
                        ),
                )
            },
        )

    private class Fixture(
        withNodeResolver: Boolean = false,
    ) {
        private val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    if (withNodeResolver) {
                        val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                        mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                    } else {
                        emptyMap()
                    }
                },
                fieldResolvers = { schema ->
                    val queryFragment = schema.loweredSchema.emptyFragmentOf("Query")
                    val userFragment = schema.loweredSchema.emptyFragmentOf("User")
                    mapOf(
                        schema.loweredSchema.requireField("Query", "user") to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.loweredSchema.requireField("User", "search") to
                            fieldResolverOf(
                                objectFragment = userFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val schema = world.schemas
        val assumptions = world.assumptions
        val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
        val userField = schema.loweredSchema.requireField("Query", "user")

        fun key(fieldName: String): ObjectEngineResult.Key =
            ObjectEngineResult.Key.of(
                field = schema.loweredSchema.requireField(user.name, fieldName),
                arguments = emptyMap(),
            )

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<ViaductSchema.Object> =
                (schema.loweredSchema.requireType(typeName) as ViaductSchema.CompositeTypeDef).possibleObjectTypes,
        ): Selection {
            return Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = schema.loweredSchema.requireField(typeName, fieldName),
                        arguments = emptyMap(),
                    ),
                possibleTypes = possibleTypes,
                subselections = selectionForestOf(),
            )
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
              name: String!
            }

            type User implements Node {
              id: ID!
              name: String!
              friend: User
              peers: [User]
              search(limit: Int): User
            }

            type Admin implements Node {
              id: ID!
              name: String!
              level: Int!
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
