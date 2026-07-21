package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.language.Field as GJField
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.IntValue
import graphql.language.SelectionSet as GJSelectionSet
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainExactly as shouldContainExactlyEntries
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asDocument
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.build

class QueryPlanFilterTest {
    @Test
    fun `filterTo preserves retained field AST and prunes unused variables`() {
        Fixture(
            """
                type Query { foo: Foo, z(arg: String): String }
                type Foo { x: ID, y(arg: String): String }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    query (${'$'}includeY: Boolean!, ${'$'}yArg: String!, ${'$'}zArg: String!) {
                      foo {
                        aliasY: y(arg: ${'$'}yArg) @include(if: ${'$'}includeY)
                        x
                      }
                      z(arg: ${'$'}zArg)
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field(
                            "Foo",
                            key("y", alias = "aliasY", arguments = mapOf("arg" to "y")),
                        )
                    }
                },
                variables = mapOf("includeY" to true, "yArg" to "y", "zArg" to "z"),
            )

            filtered.variableDefinitions.map { it.name }.shouldContainExactlyInAnyOrder("includeY", "yArg")
            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            val yAst = fooAst.selectionSet.selections.single() as GJField
            assertEquals("aliasY", yAst.alias)
            yAst.arguments.map { it.name }.shouldContainExactly("arg")
            yAst.directives.map { it.name }.shouldContainExactly("include")
        }
    }

    @Test
    fun `filterTo preserves every active occurrence of a merged response key`() {
        Fixture(
            """
                directive @tag(value:Int!) on FIELD
                type Query { foo:Foo }
                type Foo { x:Int, y:Int }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    {
                      foo @tag(value: 1) { x }
                      foo @tag(value: 2) { y }
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                        field("Foo", key("y"))
                    }
                }
            )

            filtered.astSelectionSet.selections
                .filterIsInstance<GJField>()
                .associate { field ->
                    val tagValue = (field.directives.single().arguments.single().value as IntValue)
                        .value
                        .intValueExact()
                    tagValue to field.selectionSet.fieldNames()
                }.shouldContainExactlyEntries(
                    mapOf(
                        1 to listOf("x"),
                        2 to listOf("y"),
                    ),
                )
        }
    }

    @Test
    fun `filterTo projects reused fragments independently at each path`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID, y: String, z: Foo }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    {
                      foo {
                        ...FooFields
                        z { ...FooFields }
                      }
                    }

                    fragment FooFields on Foo {
                      x
                      y
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                        field("Foo", key("z")) {
                            field("Foo", key("y"))
                        }
                    }
                }
            )

            val fooSelectionSet = checkNotNull(filtered.selectionSet.fieldSelectionSet("foo"))
            fooSelectionSet.fieldResultKeys().shouldContainExactlyInAnyOrder("x", "z")
            fooSelectionSet.fieldSelectionSet("z")!!.fieldResultKeys().shouldContainExactly("y")
            filtered.fragments.keys.shouldBeEmpty()

            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            fooAst.selectionSet.fieldNames().shouldContainExactlyInAnyOrder("x", "z")
            val zAst = fooAst.selectionSet.selections.filterIsInstance<GJField>().single { it.name == "z" }
            zAst.selectionSet.fieldNames().shouldContainExactly("y")
        }
    }

    @Test
    fun `filterTo filters inline fragments with the current tree`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID, y: String, z: String }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                "{ foo { ... on Foo { x y }, z } }"
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                    }
                }
            )

            filtered.selectionSet.fieldSelectionSet("foo")!!.fieldResultKeys().shouldContainExactly("x")

            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            fooAst.selectionSet.fieldNames().shouldContainExactly("x")
        }
    }

    @Test
    fun `filterTo removes resolved inline fragment constraints from retained fields`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    query (${'$'}includeFoo: Boolean!) {
                      ... on Query @include(if: ${'$'}includeFoo) {
                        foo { x }
                      }
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                    }
                },
                variables = mapOf("includeFoo" to true),
            )

            filtered.variableDefinitions.map { it.name }.shouldBeEmpty()
            val collected = CollectFields.shallowStrictCollect(
                schema = schema,
                selectionSet = filtered.selectionSet,
                variables = CoercedVariables.emptyVariables(),
                parentType = query,
                fragments = filtered.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = true,
            )

            collected.fieldResultKeys().shouldContainExactly("foo")
        }
    }

    @Test
    fun `filterTo removes resolved skip constraints from retained fields`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    query (${'$'}skipFoo: Boolean!) {
                      ... on Query @skip(if: ${'$'}skipFoo) {
                        foo { x }
                      }
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                    }
                },
                variables = mapOf("skipFoo" to false),
            )

            filtered.variableDefinitions.map { it.name }.shouldBeEmpty()
            val collected = CollectFields.shallowStrictCollect(
                schema = schema,
                selectionSet = filtered.selectionSet,
                variables = CoercedVariables.emptyVariables(),
                parentType = query,
                fragments = filtered.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = true,
            )

            collected.fieldResultKeys().shouldContainExactly("foo")
        }
    }

    @Test
    fun `filterTo removes inherited constraints while preserving field directives`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    query (${'$'}includeFragment: Boolean!, ${'$'}includeField: Boolean!) {
                      ... on Query @include(if: ${'$'}includeFragment) {
                        foo @include(if: ${'$'}includeField) { x }
                      }
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                    }
                },
                variables = mapOf("includeFragment" to true, "includeField" to true),
            )

            filtered.variableDefinitions.map { it.name }.shouldContainExactly("includeField")
            val included = CollectFields.shallowStrictCollect(
                schema = schema,
                selectionSet = filtered.selectionSet,
                variables = CoercedVariables.of(mapOf("includeField" to true)),
                parentType = query,
                fragments = filtered.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = true,
            )
            val excluded = CollectFields.shallowStrictCollect(
                schema = schema,
                selectionSet = filtered.selectionSet,
                variables = CoercedVariables.of(mapOf("includeField" to false)),
                parentType = query,
                fragments = filtered.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = true,
            )

            included.fieldResultKeys().shouldContainExactly("foo")
            excluded.fieldResultKeys().shouldBeEmpty()
        }
    }

    @Test
    fun `filterTo removes resolved constraints within concrete type projections`() {
        Fixture(
            """
                type Query { value: Abstract }
                interface Abstract { x: Int }
                type Impl1 implements Abstract { x: Int }
                type Impl2 implements Abstract { x: Int }
            """.trimIndent()
        ) {
            val impl1 = schema.getObjectType("Impl1")!!
            val plan = buildPlan(
                """
                    query (${'$'}includeImpl1: Boolean!) {
                      value {
                        ... on Impl1 @include(if: ${'$'}includeImpl1) {
                          x
                        }
                      }
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("value")) {
                        field("Impl1", key("x"))
                    }
                },
                variables = mapOf("includeImpl1" to true),
            )

            filtered.variableDefinitions.map { it.name }.shouldBeEmpty()
            val collected = CollectFields.shallowStrictCollect(
                schema = schema,
                selectionSet = checkNotNull(filtered.selectionSet.fieldSelectionSet("value")),
                variables = CoercedVariables.emptyVariables(),
                parentType = impl1,
                fragments = filtered.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = true,
            )

            collected.fieldResultKeys().shouldContainExactly("x")
        }
    }

    @Test
    fun `filterTo preserves exact selections for each concrete type`() {
        Fixture(
            """
                type Query { value: Abstract }
                interface Abstract { x: Int, y: Int }
                type Impl1 implements Abstract { x: Int, y: Int }
                type Impl2 implements Abstract { x: Int, y: Int }
            """.trimIndent()
        ) {
            val impl1 = schema.getObjectType("Impl1")!!
            val impl2 = schema.getObjectType("Impl2")!!
            val plan = buildPlan("{ value { x y } }")
            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("value")) {
                        field(impl1.name, key("x"))
                        field(impl2.name, key("y"))
                    }
                }
            )

            val selections = ExecutionSelectionSet
                .create(ViaductSchema(schema), filtered)
                .selectionSetForField("Query", "value")
                .selections()
                .toSet()

            selections.shouldContainExactlyInAnyOrder(
                EngineSelection("Impl1", "x", "x"),
                EngineSelection("Impl2", "y", "y"),
            )
        }
    }

    @Test
    fun `filterTo projects multiple concrete roots from an explicit nested selection set`() {
        Fixture(
            """
                type Query { value: Abstract }
                interface Abstract { x: Int, y: Int }
                type Impl1 implements Abstract { x: Int, y: Int }
                type Impl2 implements Abstract { x: Int, y: Int }
            """.trimIndent()
        ) {
            val plan = buildPlan("{ value { x y } }")
            val valueField = plan.selectionSet.selections.single() as QueryPlan.Field
            val abstractType = schema.getType("Abstract") as GraphQLCompositeType

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Impl1", key("x"))
                    field("Impl2", key("y"))
                },
                source = TypedSelectionSet(
                    selectionSet = checkNotNull(valueField.selectionSet),
                    parentType = abstractType,
                ),
            )

            assertEquals(abstractType, filtered.parentType)
            val selectionsByType = filtered.astSelectionSet.selections
                .filterIsInstance<GJInlineFragment>()
                .associate { fragment ->
                    fragment.typeCondition.name to fragment.selectionSet.fieldNames()
                }
            selectionsByType.shouldContainExactlyEntries(
                mapOf(
                    "Impl1" to listOf("x"),
                    "Impl2" to listOf("y"),
                ),
            )
        }
    }

    @Test
    fun `filterTo drops statically skipped selections even when present in tree`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID, y: String }
            """.trimIndent()
        ) {
            val plan = buildPlan("{ foo { x @skip(if: true) y } }")

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                        field("Foo", key("y"))
                    }
                }
            )

            filtered.selectionSet.fieldSelectionSet("foo")!!.fieldResultKeys().shouldContainExactly("y")

            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            fooAst.selectionSet.fieldNames().shouldContainExactly("y")
        }
    }

    @Test
    fun `filterTo preserves conditionally excluded result keys`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID, y: String }
            """.trimIndent()
        ) {
            val plan = buildPlan("{ foo { x @skip(if: true) y } }")

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("y"))
                    }
                }
            )

            val fooSelections = ExecutionSelectionSet
                .create(ViaductSchema(schema), filtered)
                .selectionSetForField("Query", "foo")

            fooSelections.conditionallyExcludedResultKeys().shouldContainExactlyInAnyOrder("x")
        }
    }

    @Test
    fun `filterTo drops fragment spreads whose filtered fragment is empty`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID, y: String }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    { foo { ...FooFields y } }
                    fragment FooFields on Foo { x }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("y"))
                    }
                }
            )

            val fooSelectionSet = filtered.selectionSet.fieldSelectionSet("foo")!!
            fooSelectionSet.selections.shouldHaveSize(1)
            fooSelectionSet.fieldResultKeys().shouldContainExactly("y")
            filtered.fragments.keys.shouldBeEmpty()

            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            fooAst.selectionSet.selections.shouldHaveSize(1)
            fooAst.selectionSet.fieldNames().shouldContainExactly("y")
        }
    }

    @Test
    fun `filterTo inlines nested fragments and keeps their active variables`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x(arg: String): String, y: String, z(arg: String): String }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    query (${'$'}xArg: String!, ${'$'}zArg: String!) {
                      foo {
                        ...Outer
                        z(arg: ${'$'}zArg)
                      }
                    }

                    fragment Outer on Foo {
                      ...Inner
                    }

                    fragment Inner on Foo {
                      x(arg: ${'$'}xArg)
                      y
                    }
                """.trimIndent()
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x", arguments = mapOf("arg" to "x")))
                    }
                },
                variables = mapOf("xArg" to "x", "zArg" to "z"),
            )

            filtered.fragments.keys.shouldBeEmpty()
            filtered.variableDefinitions.map { it.name }.shouldContainExactlyInAnyOrder("xArg")

            filtered.selectionSet.fieldSelectionSet("foo")!!.fieldResultKeys().shouldContainExactly("x")
            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            fooAst.selectionSet.fieldNames().shouldContainExactly("x")
        }
    }

    @Test
    fun `filterTo keeps field child plans scoped to retained fields`() {
        val registry = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Foo" to "x", "y")
            .fieldCheckerEntry("Foo" to "y", "x")
            .build()

        Fixture(
            """
                type Query { foo:Foo }
                type Foo { x:Int, y:Int }
            """.trimIndent(),
            registry,
        ) {
            val plan = buildPlan("{ foo { x y } }")
            val fooField = plan.selectionSet.selections.single() as QueryPlan.Field
            val originalFields = fooField.selectionSet!!.selections.filterIsInstance<QueryPlan.Field>()
            val xChildPlanId = originalFields.single { it.resultKey == "x" }.childPlans.single().requiredSelectionSetId

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                    }
                }
            )

            val filteredFoo = filtered.selectionSet.selections.single() as QueryPlan.Field
            val filteredX = filteredFoo.selectionSet!!.selections.single() as QueryPlan.Field

            filteredX.childPlans.map { it.requiredSelectionSetId }.shouldContainExactly(xChildPlanId)
            filtered.childPlanIds.shouldBeEmpty()
        }
    }

    @Test
    fun `filterTo keeps active variable resolvers and required selection ids`() {
        val activeRss = createRSS("Foo", "x")
        val inactiveRss = createRSS("Foo", "y")
        val activeResolver = MockVariablesResolver("xArg", requiredSelectionSet = activeRss) { _, _ ->
            mapOf("xArg" to "a")
        }
        val inactiveResolver = MockVariablesResolver("zArg", requiredSelectionSet = inactiveRss) { _, _ ->
            mapOf("zArg" to "b")
        }

        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x(arg: String): String, z(arg: String): String }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    query (${'$'}xArg: String!, ${'$'}zArg: String!) {
                      foo {
                        x(arg: ${'$'}xArg)
                        z(arg: ${'$'}zArg)
                      }
                    }
                """.trimIndent()
            ).copy(
                variablesResolvers = listOf(activeResolver, inactiveResolver),
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x", arguments = mapOf("arg" to "a")))
                    }
                },
                variables = mapOf("xArg" to "a", "zArg" to "b"),
            )

            filtered.variablesResolvers.map { it.variableNames }.shouldContainExactly(listOf(setOf("xArg")))
            filtered.childPlanIds.shouldContainExactly(activeRss.id)
            filtered.childPlanIds.shouldNotContain(inactiveRss.id)
        }
    }

    @Test
    fun `filterTo uses the tree root type when filtering an explicit selection set`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID, y: String }
            """.trimIndent()
        ) {
            val plan = buildPlan("{ foo { x y } }")
            val fooField = plan.selectionSet.selections.single() as QueryPlan.Field

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Foo", key("x"))
                },
                source = TypedSelectionSet(
                    selectionSet = fooField.selectionSet!!,
                    parentType = foo,
                ),
            )

            assertEquals(foo, filtered.parentType)
            filtered.selectionSet.fieldResultKeys().shouldContainExactly("x")
            filtered.astSelectionSet.fieldNames().shouldContainExactly("x")
        }
    }

    @Test
    fun `filterTo filters collected source fields`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID, y: String }
            """.trimIndent()
        ) {
            val plan = buildPlan("{ foo { x y } }")
            val collected = CollectFields.shallowStrictCollect(
                schema = schema,
                selectionSet = plan.selectionSet,
                variables = CoercedVariables.emptyVariables(),
                parentType = query,
                fragments = plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = true,
            )

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo")) {
                        field("Foo", key("x"))
                    }
                },
                source = TypedSelectionSet(
                    selectionSet = collected,
                    parentType = query,
                ),
            )

            val filteredFoo = filtered.selectionSet.selections.single() as QueryPlan.CollectedField
            filteredFoo.selectionSet!!.fieldResultKeys().shouldContainExactly("x")

            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            fooAst.selectionSet.fieldNames().shouldContainExactly("x")
            filteredFoo.mergedField.singleField.selectionSet.fieldNames().shouldContainExactly("x")
        }
    }

    @Test
    fun `filterTo keeps composite terminal fields traversable`() {
        Fixture(
            """
                type Query { foo: Foo }
                type Foo { x: ID }
            """.trimIndent()
        ) {
            val plan = buildPlan("{ foo { x @skip(if: true) } }")

            val filtered = plan.filterTo(
                KeyTree.build(viaductSchema) {
                    field("Query", key("foo"))
                }
            )

            filtered.selectionSet.fieldSelectionSet("foo").shouldNotBe(null)
            val fooAst = filtered.astSelectionSet.selections.single() as GJField
            fooAst.selectionSet.shouldNotBe(null)
        }
    }

    private class Fixture(
        sdl: String,
        private val registry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
        test: Fixture.() -> Unit,
    ) {
        val schema = sdl.asSchema
        val viaductSchema = ViaductSchema(schema)
        val query: GraphQLObjectType = schema.queryType
        val foo: GraphQLObjectType
            get() = schema.getObjectType("Foo")!!

        init {
            test()
        }

        fun buildPlan(doc: String): QueryPlan =
            runExecutionTest {
                QueryPlanFactory.Default.build(
                    QueryPlan.Parameters(
                        query = doc,
                        schema = viaductSchema,
                        registry = registry,
                    ),
                    doc.asDocument,
                )
            }

        fun QueryPlan.filterTo(
            shape: KeyTree,
            source: TypedSelectionSet? = null,
            variables: Map<String, Any?> = emptyMap(),
        ): QueryPlan {
            val context = QueryPlanFilterCtx(
                schema = schema,
                variables = CoercedVariables.of(variables),
            )
            return filterTo(
                shape = shape,
                context = context,
                source = source,
            )
        }
    }
}

private fun QueryPlan.SelectionSet.fieldResultKeys(): List<String> =
    selections.mapNotNull {
        when (it) {
            is QueryPlan.CollectedField -> it.responseKey
            is QueryPlan.Field -> it.resultKey
            else -> null
        }
    }

private fun QueryPlan.SelectionSet.fieldSelectionSet(resultKey: String): QueryPlan.SelectionSet? {
    val field = selections.single { selection ->
        when (selection) {
            is QueryPlan.CollectedField -> selection.responseKey == resultKey
            is QueryPlan.Field -> selection.resultKey == resultKey
            else -> false
        }
    }
    return when (field) {
        is QueryPlan.CollectedField -> field.selectionSet
        is QueryPlan.Field -> field.selectionSet
        else -> error("Expected a field for `$resultKey`")
    }
}

private fun GJSelectionSet.fieldNames(): List<String> = selections.filterIsInstance<GJField>().map { it.name }
