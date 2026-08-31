package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid
import viaduct.graphql.scopes.SchemaScopingMode
import viaduct.graphql.scopes.SchemaView
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

@ExperimentalCoroutinesApi
class RequiredSelectionsTest {
    @Test
    fun `resolve field with required sibling field`() =
        EngineTestModule("extend type Query { foo: String, bar: String }") {
            fieldWithValue("Query" to "bar", "BAR")
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> (obj.fetch("bar") as String).reversed() }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": "RAB"}}""")
        }

    @Test
    fun `resolve field with transitive required selections`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 2)
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar: bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use deep aliases`() =
        EngineTestModule("extend type Query { string1: String, bar: Bar } type Bar { value: String }") {
            fieldWithValue("Query" to "bar", mapOf("value" to "B"))
            field("Query" to "string1") {
                resolver {
                    objectSelections("aliasedBar: bar { aliasedValue: value }")
                    fn { _, obj, _, _, _ ->
                        val bar = obj.fetchAs<EngineObjectData>("aliasedBar")
                        val value = bar.fetch("aliasedValue")
                        "A:$value"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{string1}")
                .assertJson("""{"data": {"string1": "A:B"}}""")
        }

    @Test
    fun `required selections use arguments`() =
        EngineTestModule("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases and arguments`() =
        EngineTestModule("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar:bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections select an argumented field multiple times`() =
        EngineTestModule("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("b1:bar(x:3), b2:bar(x:5)")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("b1") * obj.fetchAs<Int>("b2")
                    }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 60}}""")
        }

    @Test
    fun `required selections use fragments`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("fragment _ on Query { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `child RSS keeps pruned fragments separate from operation fragments`() {
        val module = EngineTestModule(
            """
            extend type Query {
                container: Container!
                result: Int!
            }

            type Container {
                value: Int!
                rssOnly: Int
            }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, selections, _ ->
                        val value = if ("rssOnly" in checkNotNull(selections).conditionallyExcludedResultKeys()) {
                            1
                        } else {
                            2
                        }
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf("value" to value),
                        )
                    }
                }
            }
            field("Query" to "result") {
                resolver {
                    objectSelections(
                        """
                        fragment Main on Query {
                            container {
                                value
                                ...Shared @skip(if: true)
                            }
                        }

                        fragment Shared on Container {
                            rssOnly
                        }
                        """.trimIndent()
                    )
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("container").fetchAs<Int>("value")
                    }
                }
            }
        }

        val flagManagers = listOf(
            MockFlagManager.Disabled,
            MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION),
        )
        for (flagManager in flagManagers) {
            module.runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(flagManager = flagManager)
            ) {
                runQuery(
                    """
                    query { ...Shared }
                    fragment Shared on Query { result }
                    """.trimIndent()
                ).assertJson("{data: {result: 1}}")
            }
        }
    }

    @Test
    fun `required selections use untyped inline fragments`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use typed inline fragments`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... on Query { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `resolve fields with shared requirement`() {
        val bazCount = AtomicInteger()
        EngineTestModule("extend type Query { foo: Int, bar: Int, baz: Int }") {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, _ -> bazCount.incrementAndGet().let { 5 } }
                }
            }
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 10, "bar": 15}}""")
                .also { assertEquals(1, bazCount.get()) }
        }
    }

    @Test
    fun `selective field executes once for a single selection shape`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { details: Details }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
        }.runFeatureTest {
            runQuery("{ details { a } }")
                .assertJson("""{"data": {"details": {"a": 1}}}""")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a"), detailsSelections)
    }

    @Test
    fun `selective field executes separately for client and resolver rss shapes`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { details: Details, fromB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "fromB") {
                resolver {
                    querySelections("details { b }")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ details { a } fromB }")
                .assertJson("""{"data": {"details": {"a": 1}, "fromB": 2}}""")
        }

        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `selective required selection is resolved independently from client query selection`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "summary") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { details { a } summary } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "summary": 20}}}""")
        }

        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `parent field accesses already requested parent fields through named fragment`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User { parent: Company @parent, parentCompanyName: String }
            """.trimIndent()
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("companyName" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { companyName }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                query {
                  company {
                    companyName
                    user {
                      ...UserNameFields
                    }
                  }
                }

                fragment UserNameFields on User {
                  parentCompanyName
                }
                """.trimIndent()
            ).assertJson("""{"data": {"company": {"companyName": "Airbnb", "user": {"parentCompanyName": "Airbnb"}}}}""")
        }
    }

    @Test
    fun `nested parent field accesses already requested grandparent fields`() {
        EngineTestModule(
            """
            extend type Query { organization: Organization }
            type Organization { name: String, company: Company }
            type Company { parent: Organization @parent, user: User }
            type User { parent: Company @parent, parentOrganizationName: String }
            """.trimIndent()
        ) {
            field("Query" to "organization") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Organization"),
                            mapOf("name" to "Engineering")
                        )
                    }
                }
            }
            field("Organization" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentOrganizationName") {
                resolver {
                    objectSelections("parent { parent { name } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent")
                            .fetchAs<EngineObjectData>("parent")
                            .fetchAs<String>("name")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ organization { name company { user { parentOrganizationName } } } }")
                .assertJson("""{"data": {"organization": {"name": "Engineering", "company": {"user": {"parentOrganizationName": "Engineering"}}}}}""")
        }
    }

    @Test
    fun `parent field with resolver argument variables runs child plan`() {
        val resolvedNameLocales = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { name(locale: String): String, user: User }
            type User { parent: Company @parent, localizedCompanyName(locale: String!): String }
            """.trimIndent()
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "name") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val locale = args["locale"] as String
                        resolvedNameLocales.add(locale)
                        "Airbnb-$locale"
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "localizedCompanyName") {
                resolver {
                    argumentVariables("locale" to "locale")
                    objectSelections("parent { name(locale: ${'$'}locale) }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("name")
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ company { user { localizedCompanyName(locale: \"en\") } } }")

            // name(locale: $locale) is resolved by the RSS child plan after
            // User.localizedCompanyName's argument exists.
            assertEquals(
                mapOf(
                    "company" to mapOf(
                        "user" to mapOf("localizedCompanyName" to "Airbnb-en"),
                    )
                ),
                result.getData()
            )
            assertEquals(0, result.errors.size)
        }

        assertEquals(setOf("en"), resolvedNameLocales)
    }

    @Test
    fun `parent field with child object field variables runs child plan`() {
        val resolvedNameLocales = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { name(locale: String): String, user: User }
            type User { locale: String!, parent: Company @parent, localizedCompanyName: String }
            """.trimIndent()
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "name") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val locale = args["locale"] as String
                        resolvedNameLocales.add(locale)
                        "Airbnb-$locale"
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("User"),
                            mapOf("locale" to "de")
                        )
                    }
                }
            }
            field("User" to "localizedCompanyName") {
                resolver {
                    objectSelections("locale parent { name(locale: ${'$'}locale) }") {
                        variables("locale", rss = createRSS("User", "locale")) { ctx, _ ->
                            mapOf("locale" to ctx.objectData.fetchAs<String>("locale"))
                        }
                    }
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("name")
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ company { user { localizedCompanyName } } }")

            // name(locale: $locale) is resolved by the RSS child plan after Company.user has
            // produced the User object that owns the locale field.
            assertEquals(
                mapOf(
                    "company" to mapOf(
                        "user" to mapOf("localizedCompanyName" to "Airbnb-de"),
                    )
                ),
                result.getData()
            )
            assertEquals(0, result.errors.size)
        }

        assertEquals(setOf("de"), resolvedNameLocales)
    }

    @Test
    fun `parent field in checker required selection is available to checker`() {
        val checkedCompanyNames = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User { parent: Company @parent, sensitiveProfile: String }
            """.trimIndent()
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            fieldWithValue("Company" to "companyName", "Airbnb")
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "sensitiveProfile") {
                value("allowed")
                checker {
                    objectSelections("company", "parent { companyName }")
                    fn { _, objectDataMap ->
                        checkedCompanyNames.add(
                            objectDataMap["company"]!!
                                .fetchAs<EngineObjectData>("parent")
                                .fetchAs<String>("companyName")
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { sensitiveProfile } } }")
                .assertJson("""{"data": {"company": {"user": {"sensitiveProfile": "allowed"}}}}""")
        }

        assertEquals(setOf("Airbnb"), checkedCompanyNames)
    }

    @Test
    fun `parent field in variable resolver required selection is available to variables resolver`() {
        val resolvedNameLocales = ConcurrentHashMap.newKeySet<String>()
        val variableLocales = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { locale: String!, name(locale: String): String, user: User }
            type User { parent: Company @parent, localizedCompanyName: String }
            """.trimIndent()
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            fieldWithValue("Company" to "locale", "fr")
            field("Company" to "name") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val locale = args["locale"] as String
                        resolvedNameLocales.add(locale)
                        "Airbnb-$locale"
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "localizedCompanyName") {
                resolver {
                    objectSelections("parent { name(locale: ${'$'}locale) }") {
                        variables("locale", rss = createRSS("User", "parent { locale }")) { ctx, _ ->
                            val locale = ctx.objectData
                                .fetchAs<EngineObjectData>("parent")
                                .fetchAs<String>("locale")
                            variableLocales.add(locale)
                            mapOf("locale" to locale)
                        }
                    }
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("name")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { localizedCompanyName } } }")
                .assertJson("""{"data": {"company": {"user": {"localizedCompanyName": "Airbnb-fr"}}}}""")
        }

        assertEquals(setOf("fr"), variableLocales)
        assertEquals(setOf("fr"), resolvedNameLocales)
    }

    @Test
    fun `parent field selections honor conditional directives`() {
        val companyNameCount = AtomicInteger()

        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, users: [User] }
            type User { includeParentName: Boolean!, parent: Company @parent, parentCompanyName: String }
            """.trimIndent()
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "companyName") {
                resolver {
                    fn { _, _, _, _, _ ->
                        companyNameCount.incrementAndGet()
                        "Airbnb"
                    }
                }
            }
            field("Company" to "users") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(
                            createEngineObjectData(
                                schema.schema.getObjectType("User"),
                                mapOf("includeParentName" to true)
                            ),
                            createEngineObjectData(
                                schema.schema.getObjectType("User"),
                                mapOf("includeParentName" to false)
                            )
                        )
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("includeParentName parent { companyName @include(if: ${'$'}includeParentName) }") {
                        variables("includeParentName", rss = createRSS("User", "includeParentName")) { ctx, _ ->
                            mapOf("includeParentName" to ctx.objectData.fetchAs<Boolean>("includeParentName"))
                        }
                    }
                    fn { _, obj, _, _, _ ->
                        val parent = obj.fetchAs<EngineObjectData>("parent")
                        if (obj.fetchAs<Boolean>("includeParentName")) {
                            parent.fetchAs<String>("companyName")
                        } else {
                            "skipped"
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { users { parentCompanyName } } }")
                .assertJson("""{"data": {"company": {"users": [{"parentCompanyName": "Airbnb"}, {"parentCompanyName": "skipped"}]}}}""")
        }

        assertEquals(1, companyNameCount.get())
    }

    @Test
    fun `plain OER keys cause required selections to reuse client selection shape`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()
        val engineConfig = EngineConfiguration.featureTestDefault.copy(flagManager = MockFlagManager.Disabled)

        EngineTestModule(
            """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "summary") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig) {
            val result = runQuery("{ container { details { a } summary } }")

            assertEquals(
                mapOf(
                    "container" to mapOf(
                        "details" to mapOf("a" to 1),
                        "summary" to null,
                    )
                ),
                result.getData()
            )
            assertEquals(1, result.errors.size)
            assertTrue(result.errors.first().message.contains("null cannot be cast to non-null type kotlin.Int"))
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a"), detailsSelections)
    }

    @Test
    fun `non-selective required selection is shared across client query and dependency selections`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                mapOf("a" to 1, "b" to 2)
                            )
                        }
                    )
                }
            }
            field("Container" to "summary") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { details { a } summary } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "summary": 20}}}""")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a"), detailsSelections)
    }

    @Test
    fun `selective required selection is resolved independently across resolver rss variants`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { container: Container }
            type Container { details: Details, fromA: Int, fromB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "fromA") {
                resolver {
                    objectSelections("details { a }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("a")
                    }
                }
            }
            field("Container" to "fromB") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { fromA fromB } }")
                .assertJson("""{"data": {"container": {"fromA": 1, "fromB": 2}}}""")
        }

        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `object rss node selection ignores nested fragment on other implementation`() {
        // Query.foo's object RSS reads Query.node with a selection whose outer fragment is on Bar,
        // but the node resolves to Foo. The resolver-facing object value should therefore expose no
        // child selections for the Foo node.
        //
        // This used to time out because EngineSelectionSet widened the nested `... on Node`
        // fragment inside `... on Bar`, so fetching `id` through the Foo proxy waited on an OER
        // field that the planned traversal correctly never wrote.
        EngineTestModule(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID! }
                type Bar implements Node { id: ID! }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    val objectRss = createRSS(
                        "Query",
                        """
                            fragment Main on Query {
                              node(id: "Rm9vOlQrZw==") {
                                ...BarNodeFields
                              }
                            }

                            fragment BarNodeFields on Bar {
                              ... on Node {
                                id
                              }
                            }
                        """.trimIndent()
                    )
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = objectRss,
                        isSelective = false,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, ctx ->
                            val node = obj.fetchAs<EngineObjectData>("node")
                            assertNull(withTimeout(1_000) { node.fetchOrNull("id") })
                            ctx.createNodeReference("foo", schema.schema.getObjectType("Foo")!!)
                        }
                    )
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id)
                    )
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                    query {
                      foo {
                        __typename
                      }
                    }
                """.trimIndent()
            ).assertJson(
                """
                    {
                      data: {
                        foo: {
                          __typename: "Foo"
                        }
                      }
                    }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `required selection with impossible sibling implementation dependency can be resolved`() {
        // Foo.x has an object RSS rooted at Foo. The outer `... on Node` branch can match Foo,
        // but after that refinement the nested `... on Bar` branch is impossible because Foo and Bar
        // are sibling implementations of Node.
        EngineTestModule(
            """
                extend type Query {
                  trigger: Int
                  bar1: Node
                }

                extend interface Node {
                  x: Int
                }

                type Foo implements Node {
                  id: ID!
                  x: Int
                }

                type Bar implements Node {
                  id: ID!
                  x: Int
                  y: Int
                  foo: Foo
                  gate: Boolean
                }
            """.trimIndent()
        ) {
            field("Query" to "trigger") {
                resolver {
                    querySelections("bar1 {x, ... on Bar { y } }")
                    fn { _, _, query, _, _ ->
                        query.fetchAs<EngineObjectData>("bar1").fetchAs<Int>("y")
                    }
                }
            }

            field("Query" to "bar1") {
                valueFromContext { ctx ->
                    ctx.createNodeReference(
                        ctx.globalIDCodec.serialize("Bar", "1"),
                        schema.schema.getObjectType("Bar")!!
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections(
                        """
                            ... on Node @include(if: ${'$'}gate) {
                              ... on Bar {
                                y
                              }
                            }
                        """.trimIndent()
                    ) {
                        variables(
                            "gate",
                            rss = createRSS("Query", "bar1 { ... on Bar { gate } }")
                        ) { resolveCtx, _ ->
                            val gate = resolveCtx.objectData
                                .fetchAs<EngineObjectData>("bar1")
                                .fetchAs<Boolean>("gate")
                            mapOf("gate" to gate)
                        }
                    }
                    fn { _, _, _, _, _ -> 1 }
                }
            }

            fieldWithValue("Bar" to "gate", true)

            field("Bar" to "y") {
                resolver {
                    objectSelections("foo { x }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("foo")
                            .fetchAs<Int>("x")
                    }
                }
            }

            field("Bar" to "foo") {
                valueFromContext {
                    createEngineObjectData(schema.schema.getObjectType("Foo")!!, emptyMap())
                }
            }

            type("Bar") {
                nodeUnbatchedExecutor { _, _, _ ->
                    createEngineObjectData(objectType, emptyMap())
                }
            }
        }.runFeatureTest(withoutDefaultQueryNodeResolvers = true) {
            runQueryWithTimeout("{ trigger }")
                .assertJson("{data: {trigger: 1}}")
        }
    }

    @Test
    fun `sibling cyclic required selections keep direct materializations during execution`() {
        val bInAPlanChildPlanCount = AtomicInteger(-1)

        // Query.a's checker requires depB: b, while Query.b's checker requires depA: a.
        // Executing both fields exercises the same sibling cyclic RSS root shape as
        // QueryPlanTest, through the public required-selection DSL.
        EngineTestModule("extend type Query { a: Int, b: Int }") {
            field("Query" to "a") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val parameters = ctx.executionHandle as ExecutionParameters
                        val aField = parameters.queryPlan.selectionSet.selections
                            .filterIsInstance<QueryPlan.Field>()
                            .first { it.resultKey == "a" }
                        val aPlan = parameters.queryPlan.planForTest(aField.childPlans.single())
                        val bInAPlan = aPlan.selectionSet.selections
                            .filterIsInstance<QueryPlan.Field>()
                            .first { it.resultKey == "depB" }

                        bInAPlanChildPlanCount.set(bInAPlan.childPlans.size)
                        1
                    }
                }
                checker {
                    objectSelections("deps", "depB: b")
                    fn { _, _ -> }
                }
            }

            field("Query" to "b") {
                resolver {
                    fn { _, _, _, _, _ ->
                        2
                    }
                }
                checker {
                    objectSelections("deps", "depA: a")
                    fn { _, _ -> }
                }
            }
        }.runFeatureTest {
            runQuery("{ a b }").assertJson("{data: {a: 1, b: 2}}")
        }

        assertEquals(1, bInAPlanChildPlanCount.get())
    }

    @Test
    fun `selective query field resolves matching resolver and checker selections independently`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()
        val checkerCount = AtomicInteger()

        EngineTestModule(
            """
            extend type Query { details: Details, fromObjectB: Int, fromQueryB: Int, checked: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "fromObjectB") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
            field("Query" to "fromQueryB") {
                resolver {
                    querySelections("details { b }")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
            field("Query" to "checked") {
                value(1)
                checker {
                    querySelections("key", "fragment _ on Query { details { b } }")
                    fn { _, objectDataMap ->
                        checkerCount.incrementAndGet()
                        objectDataMap["key"]!!
                            .fetchAs<EngineObjectData>("details")
                            .fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ details { a } fromObjectB fromQueryB checked }")
                .assertJson("""{"data": {"details": {"a": 1}, "fromObjectB": 2, "fromQueryB": 2, "checked": 1}}""")
        }

        assertEquals(1, checkerCount.get())
        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `selective required selection resolves resolver and type checker selections independently`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()
        val checkerCount = AtomicInteger()

        EngineTestModule(
            """
            extend type Query { container: Container }
            type Container { details: Details, fromObjectB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "fromObjectB") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
            type("Container") {
                checker {
                    objectSelections("key", "fragment _ on Container { details { b } }")
                    fn { _, objectDataMap ->
                        checkerCount.incrementAndGet()
                        objectDataMap["key"]!!
                            .fetchAs<EngineObjectData>("details")
                            .fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { details { a } fromObjectB } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "fromObjectB": 2}}}""")
        }

        assertEquals(1, checkerCount.get())
        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `selective required selection through interface inline fragment uses concrete runtime type`() {
        val detailsCount = AtomicInteger()

        EngineTestModule(
            """
            interface Container { details: Details }
            extend type Query { container: Container }
            type ConcreteContainer implements Container { details: Details, fromObjectB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("ConcreteContainer"),
                            emptyMap()
                        )
                    }
                }
            }
            field("ConcreteContainer" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("ConcreteContainer" to "fromObjectB") {
                resolver {
                    objectSelections("... on Container { details { b } }")
                    fn { _, obj, _, _, _ ->
                        withTimeout(1_000) {
                            obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { ... on ConcreteContainer { fromObjectB } } }")
                .assertJson("""{"data": {"container": {"fromObjectB": 2}}}""")
        }

        assertEquals(1, detailsCount.get())
    }

    @Test
    fun `descendant fields of a selective resolver are not keyed selectively`() {
        EngineTestModule(
            """
            extend type Query { details: Details, fromProfileName: String }
            type Details { profile: Profile }
            type Profile { name: String }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSet()
                                .orEmpty()
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("profile" in requestedSelections) {
                                        put("profile", mapOf("name" to "Ada"))
                                    }
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "fromProfileName") {
                resolver {
                    objectSelections("details { profile { name } }")
                    fn { _, obj, _, _, _ ->
                        withTimeout(200) {
                            obj.fetchAs<EngineObjectData>("details")
                                .fetchAs<EngineObjectData>("profile")
                                .fetchAs<String>("name")
                        }
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ fromProfileName }")
            result.assertJson("""{"data": {"fromProfileName": "Ada"}}""")
        }
    }

    @Test
    fun `resolve field with multiple requirements`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 5)
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar baz")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("bar") * obj.fetchAs<Int>("baz")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 15}}""")
        }

    @Test
    fun `resolve fields multiple mergeable requirements`() {
        val barCount = AtomicInteger()
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            field("Query" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> 3.also { barCount.incrementAndGet() } }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections(
                        """
                        fragment F on Query { bar }
                        fragment Main on Query {
                          bar
                          aliasedBar: bar
                          ... {
                            bar
                            ... {
                              bar
                              ... F
                            }
                          }
                          ... on Query {
                            bar
                            ... on Query {
                              bar
                              ... F
                            }
                          }
                          ... F
                        }
                        """.trimIndent()
                    )
                    fn { _, obj, _, _, _ ->
                        // make sure we wait for aliasedBar
                        obj.fetchAs<Int>("aliasedBar")

                        // but ultimately just return 2 * "bar"
                        obj.fetchAs<Int>("bar") * 2
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 6, "bar": 3}}""")
                .also { assertEquals(2, barCount.get()) }
        }
    }

    @Test
    fun `proxy engine object data reads required selections through two nested client and rss merges`() {
        val outerCount = AtomicInteger()
        val middleCount = AtomicInteger()
        val innerCount = AtomicInteger()
        val innerSelections = ConcurrentHashMap.newKeySet<String>()

        EngineTestModule(
            """
            extend type Query { summary: Int, outer: Outer }
            type Outer { middle: Middle }
            type Middle { inner: Inner }
            type Inner { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "outer") {
                resolver {
                    fn { _, _, _, _, _ ->
                        outerCount.incrementAndGet()
                        createEngineObjectData(schema.schema.getObjectType("Outer"), emptyMap())
                    }
                }
            }
            field("Outer" to "middle") {
                resolver {
                    fn { _, _, _, _, _ ->
                        middleCount.incrementAndGet()
                        createEngineObjectData(schema.schema.getObjectType("Middle"), emptyMap())
                    }
                }
            }
            field("Middle" to "inner") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selectionSetForType("Inner")
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            innerCount.incrementAndGet()
                            innerSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Inner"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "summary") {
                resolver {
                    objectSelections("outer { middle { inner { b } } }")
                    fn { _, obj, _, _, _ ->
                        withTimeout(200) {
                            obj.fetchAs<EngineObjectData>("outer")
                                .fetchAs<EngineObjectData>("middle")
                                .fetchAs<EngineObjectData>("inner")
                                .fetchAs<Int>("b")
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                query {
                  outer {
                    middle {
                      inner {
                        a
                      }
                    }
                  }
                  summary
                }
                """.trimIndent()
            ).assertJson("""{"data": {"outer": {"middle": {"inner": {"a": 1}}}, "summary": 2}}""")
        }

        assertEquals(1, outerCount.get())
        assertEquals(1, middleCount.get())
        assertEquals(2, innerCount.get())
        assertEquals(setOf("a", "b"), innerSelections)
    }

    @Test
    fun `variable resolver rss reads through multiple selective fields including abstract hop`() {
        val middleCount = AtomicInteger()
        val nodeCount = AtomicInteger()

        EngineTestModule(
            """
            extend type Query { outer: Outer, compute(x: Int!): Int!, result: Int! }
            type Outer { middle: Middle }
            type Middle { node: AbstractNode }
            interface AbstractNode { id: ID! }
            type ConcreteNode implements AbstractNode { id: ID!, value: Int! }
            """.trimIndent()
        ) {
            field("Query" to "outer") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Outer"),
                            emptyMap(),
                        )
                    }
                }
            }
            field("Outer" to "middle") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = "Outer.middle",
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            middleCount.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Middle"),
                                emptyMap(),
                            )
                        }
                    )
                }
            }
            field("Middle" to "node") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = "Middle.node",
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selectionSetForType("ConcreteNode")
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSet()
                                .orEmpty()
                            nodeCount.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("ConcreteNode"),
                                buildMap {
                                    if ("id" in requestedSelections) put("id", "n1")
                                    if ("value" in requestedSelections) put("value", 7)
                                },
                            )
                        }
                    )
                }
            }
            field("Query" to "compute") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") + 1 }
                }
            }
            field("Query" to "result") {
                resolver {
                    objectSelections("compute(x:\$value)") {
                        variables(
                            "value",
                            rss = createRSS(
                                "Query",
                                "outer { middle { node { ... on ConcreteNode { value } } } }",
                            ),
                        ) { ctx, _ ->
                            val value = withTimeout(200) {
                                ctx.objectData
                                    .fetchAs<EngineObjectData>("outer")
                                    .fetchAs<EngineObjectData>("middle")
                                    .fetchAs<EngineObjectData>("node")
                                    .fetchAs<Int>("value")
                            }
                            mapOf("value" to value)
                        }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("compute") }
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                query {
                  outer {
                    middle {
                        node {
                        ... on ConcreteNode {
                          id
                        }
                      }
                    }
                  }
                  result
                }
                """.trimIndent()
            ).assertJson(
                """
                {"data": {
                  "outer": {"middle": {"node": {"id": "n1"}}},
                  "result": 8
                }}
                """.trimIndent()
            )
        }

        assertEquals(1, middleCount.get())
        assertEquals(2, nodeCount.get())
    }

    @Test
    fun `two resolvers with structurally-equivalent variable-resolver RSSes both resolve correctly`() {
        // Setup: two resolver fields (foo1, foo2) whose objectSelections RSS is structurally
        // identical — both select `y(a:$vara)` and both reference the same shared
        // MockVariablesResolver (whose nested RSS selects `z`). The resolver for y returns its
        // argument unchanged, so each foo returns whatever z produces. If the nested RSS id rebind
        // were missing/wrong, one of foo1/foo2 would fail to fetch `vara` at runtime.
        val sharedNestedRss = createRSS("Query", "z")
        val sharedVariablesResolver = MockVariablesResolver(
            "vara",
            requiredSelectionSet = sharedNestedRss,
        ) { ctx, _ -> mapOf("vara" to ctx.objectData.getAs<Int>("z")) }
        val sharedResolvers = listOf(sharedVariablesResolver)

        EngineTestModule(
            "extend type Query { foo1: Int, foo2: Int, y(a:Int): Int, z: Int }"
        ) {
            fieldWithValue("Query" to "z", 7)
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("a") }
                }
            }
            field("Query" to "foo1") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Query", "y(a:\$vara)", sharedResolvers),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") },
                    )
                }
            }
            field("Query" to "foo2") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Query", "y(a:\$vara)", sharedResolvers),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") },
                    )
                }
            }
        }.runFeatureTest {
            runQuery("{ foo1 foo2 }")
                .assertJson("""{"data": {"foo1": 7, "foo2": 7}}""")
        }
    }

    @Test
    fun `resolve private field in RSS`() {
        // Need to set up both full schema and scoped schema
        val fullSchemaSDL = """
            extend type Query @scope(to: ["*"]) { _: String }
            extend type Query @scope(to: ["scoped"]) { foo: Int }
            extend type Query @scope(to: ["private"]) { bar: Int }
        """

        val bootstrapper = EngineTestModule(fullSchemaSDL) {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") + 1 }
                }
            }
        }

        val privateSchema = ViaductSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                scopingMode = SchemaScopingMode.ScopeAware(setOf("scoped", "private")),
            ).build(SchemaView.Scoped(setOf("scoped"))).filtered
        )

        bootstrapper.runFeatureTest(schema = privateSchema) {
            runQuery("{foo}")
                .assertJson("{data: {foo: 4}}")
        }
    }

    @Test
    fun `resolve field with queryValueFragment - simple field access`() =
        EngineTestModule("extend type Query { currentUser: String, userGreeting: String }") {
            fieldWithValue("Query" to "currentUser", "Alice")
            field("Query" to "userGreeting") {
                resolver {
                    querySelections("currentUser")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("currentUser")
                        "Hello, $user!"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{userGreeting}")
                .assertJson("""{"data": {"userGreeting": "Hello, Alice!"}}""")
        }

    @Test
    fun `objectSelections conditional directives honor per-item variables`() {
        val selectedValueCount = AtomicInteger()

        EngineTestModule(
            """
            extend type Query {
                items: [Item!]!
            }

            type Item {
                includeSelectedValue: Boolean!
                selectedValue: String
                summary: String
            }
            """.trimIndent()
        ) {
            field("Query" to "items") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(
                            createEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("includeSelectedValue" to true)
                            ),
                            createEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("includeSelectedValue" to false)
                            )
                        )
                    }
                }
            }
            field("Item" to "selectedValue") {
                resolver {
                    fn { _, _, _, _, _ ->
                        selectedValueCount.incrementAndGet()
                        "selected"
                    }
                }
            }
            field("Item" to "summary") {
                resolver {
                    objectSelections("includeSelectedValue selectedValue @include(if: ${'$'}includeSelectedValue)") {
                        variables("includeSelectedValue", rss = createRSS("Item", "includeSelectedValue")) { resolveCtx, _ ->
                            mapOf(
                                "includeSelectedValue" to resolveCtx.objectData.getAs<Boolean>("includeSelectedValue")
                            )
                        }
                    }
                    fn { _, obj, _, _, _ ->
                        if (obj.fetchAs<Boolean>("includeSelectedValue")) {
                            obj.fetchAs<String>("selectedValue")
                        } else {
                            "skipped"
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ items { summary } }")
                .assertJson("""{"data": {"items": [{"summary": "selected"}, {"summary": "skipped"}]}}""")
        }

        assertEquals(1, selectedValueCount.get())
    }

    @Test
    fun `resolve field with queryValueFragment - with aliases`() =
        EngineTestModule("extend type Query { currentUser: String, userCount: Int, summary: String }") {
            fieldWithValue("Query" to "currentUser", "Bob")
            fieldWithValue("Query" to "userCount", 42)
            field("Query" to "summary") {
                resolver {
                    querySelections("user: currentUser, count: userCount")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("user")
                        val count = qry.fetchAs<Int>("count")
                        "$user has $count items"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{summary}")
                .assertJson("""{"data": {"summary": "Bob has 42 items"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - with arguments`() =
        EngineTestModule("extend type Query { user(id: String!): String, userMessage: String }") {
            field("Query" to "user") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val id = args.getAs<String>("id")
                        "User-$id"
                    }
                }
            }
            field("Query" to "userMessage") {
                resolver {
                    querySelections("user(id: \"123\")")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("user")
                        "Message for: $user"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{userMessage}")
                .assertJson("""{"data": {"userMessage": "Message for: User-123"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - using fragments`() =
        EngineTestModule("extend type Query { userName: String, userEmail: String, profile: String }") {
            fieldWithValue("Query" to "userName", "Charlie")
            fieldWithValue("Query" to "userEmail", "charlie@example.com")
            field("Query" to "profile") {
                resolver {
                    querySelections("fragment UserInfo on Query { userName userEmail }")
                    fn { _, _, qry, _, _ ->
                        val name = qry.fetchAs<String>("userName")
                        val email = qry.fetchAs<String>("userEmail")
                        "Name: $name, Email: $email"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{profile}")
                .assertJson("""{"data": {"profile": "Name: Charlie, Email: charlie@example.com"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment and objectValueFragment together`() =
        EngineTestModule("extend type Query { globalConfig: String, baz: Baz } type Baz { x: Int, y: String }") {
            fieldWithValue("Query" to "globalConfig", "Premium")
            fieldWithValue("Query" to "baz", createEngineObjectData(schema.schema.getObjectType("Baz"), mapOf("x" to 100)))
            field("Baz" to "y") {
                resolver {
                    objectSelections("x")
                    querySelections("globalConfig")
                    fn { _, obj, qry, _, _ ->
                        val config = qry.fetchAs<String>("globalConfig")
                        val x = obj.fetchAs<Int>("x")
                        "$config item with value $x"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{baz { y }}")
                .assertJson("{data: {baz: {y: \"Premium item with value 100\"}}}")
        }

    @Test
    fun `resolve field with queryValueFragment - transitive dependencies`() =
        EngineTestModule("extend type Query { baseValue: Int, multipliedValue: Int, finalValue: Int }") {
            fieldWithValue("Query" to "baseValue", 5)
            field("Query" to "multipliedValue") {
                resolver {
                    querySelections("baseValue")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<Int>("baseValue") * 2
                    }
                }
            }
            field("Query" to "finalValue") {
                resolver {
                    querySelections("multipliedValue")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<Int>("multipliedValue") + 10
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{finalValue}")
                .assertJson("""{"data": {"finalValue": 20}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - multiple query selections`() {
        val userCount = AtomicInteger()
        val configCount = AtomicInteger()
        EngineTestModule("extend type Query { currentUser: String, globalConfig: String, combined: String }") {
            field("Query" to "currentUser") {
                resolver {
                    fn { _, _, _, _, _ ->
                        userCount.incrementAndGet()
                        "David"
                    }
                }
            }
            field("Query" to "globalConfig") {
                resolver {
                    fn { _, _, _, _, _ ->
                        configCount.incrementAndGet()
                        "Advanced"
                    }
                }
            }
            field("Query" to "combined") {
                resolver {
                    querySelections("currentUser globalConfig")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("currentUser")
                        val config = qry.fetchAs<String>("globalConfig")
                        "$user - $config mode"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{combined}")
                .assertJson("""{"data": {"combined": "David - Advanced mode"}}""")
                .also {
                    assertEquals(1, userCount.get(), "currentUser should be resolved only once")
                    assertEquals(1, configCount.get(), "globalConfig should be resolved only once")
                }
        }
    }

    @Test
    fun `resolve field with queryValueFragment - inline fragment without type condition`() =
        EngineTestModule("extend type Query { isEnabled: Boolean, config: String, result: String }") {
            fieldWithValue("Query" to "isEnabled", true)
            fieldWithValue("Query" to "config", "production")
            field("Query" to "result") {
                resolver {
                    querySelections("... { isEnabled config }")
                    fn { _, _, qry, _, _ ->
                        val enabled = qry.fetchAs<Boolean>("isEnabled")
                        val config = qry.fetchAs<String>("config")
                        if (enabled) "Running in $config" else "Disabled"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{result}")
                .assertJson("""{"data": {"result": "Running in production"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - handles null gracefully`() =
        EngineTestModule("extend type Query { optionalValue: String, result: String }") {
            fieldWithValue("Query" to "optionalValue", null)
            field("Query" to "result") {
                resolver {
                    querySelections("optionalValue")
                    fn { _, _, qry, _, _ ->
                        val value = qry.fetch("optionalValue") as? String
                        value ?: "No value provided"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{result}")
                .assertJson("""{"data": {"result": "No value provided"}}""")
        }

    @Test
    fun `resolve mutation with queryValueFragment`() =
        EngineTestModule("extend type Query { string1: String } extend type Mutation { string1: String }") {
            fieldWithValue("Query" to "string1", "InitialValue")
            field("Mutation" to "string1") {
                resolver {
                    querySelections("string1")
                    fn { _, _, qry, _, _ ->
                        val currentValue = qry.fetchAs<String>("string1")
                        "Mutated from: $currentValue"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("mutation { string1 }")
                .assertJson("{data: {string1: \"Mutated from: InitialValue\"}}")
        }

    @Test
    fun `resolve field with queryValueFragment - nested object access`() =
        EngineTestModule("extend type Query { bar: Bar, baz: Baz } type Bar { value: String } type Baz { x: Int, y: String }") {
            fieldWithValue("Query" to "bar", createEngineObjectData(schema.schema.getObjectType("Bar"), mapOf()))
            fieldWithValue("Bar" to "value", "BarValue")
            fieldWithValue("Query" to "baz", createEngineObjectData(schema.schema.getObjectType("Baz"), mapOf()))
            field("Baz" to "y") {
                resolver {
                    querySelections("bar { value }")
                    fn { _, _, qry, _, _ ->
                        val bar = qry.fetchAs<EngineObjectData>("bar")
                        val barValue = bar.fetch("value")
                        "Baz sees bar value: $barValue"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{baz { y }}")
                .assertJson("{data: {baz: {y: \"Baz sees bar value: BarValue\"}}}")
        }

    @Test
    fun `resolve field with queryValueFragment - typed inline fragment`() =
        EngineTestModule("extend type Query { enabled: Boolean, message: String, status: String }") {
            fieldWithValue("Query" to "enabled", false)
            fieldWithValue("Query" to "message", "System offline")
            field("Query" to "status") {
                resolver {
                    querySelections("... on Query { enabled message }")
                    fn { _, _, qry, _, _ ->
                        val enabled = qry.fetchAs<Boolean>("enabled")
                        val message = qry.fetchAs<String>("message")
                        if (!enabled) message else "OK"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{status}")
                .assertJson("""{"data": {"status": "System offline"}}""")
        }

    @Test
    fun `queryValueFragment with unclosed brace should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            EngineTestModule("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("{ field") // Missing closing brace
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with invalid field syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            EngineTestModule("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("field(") // Invalid - parenthesis without arguments
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment referencing non-existent field should fail at build time`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            EngineTestModule("extend type Query { existingField: String, result: String }") {
                fieldWithValue("Query" to "existingField", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("nonExistentField") // Field doesn't exist in schema
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with invalid fragment syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            EngineTestModule("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("fragment on Query { field }") // Missing fragment name
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment with invalid variable syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            EngineTestModule("extend type Query { field(arg: Int!): String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("field(arg: $)") // Invalid variable syntax
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment with empty selection set should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            EngineTestModule("extend type Query { result: String }") {
                field("Query" to "result") {
                    resolver {
                        querySelections("{}") // Empty selection set
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with wrong type condition should fail at build time`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            EngineTestModule("extend type Query { field: String, result: String } extend type Mutation { dummy: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("... on Mutation { field }") // Wrong type - should be Query
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `query rss variable resolver is planned when repeated fragment spread has runtime directive`() {
        // Query.a has a query RSS that spreads the same fragment twice: once behind a runtime
        // directive whose variable comes from a variables resolver, and once unconditionally.
        // The variables resolver has its own RSS selecting Query.b.
        //
        // Planning keeps Query.b through the unconditional fragment spread, but the child plan for
        // the variables resolver RSS is not indexed. Runtime then fails before Query.a's resolver
        // runs, while building the EngineObjectData passed to the variables resolver.
        EngineTestModule(
            "extend type Query { a: Int, b: Int }"
        ) {
            field("Query" to "a") {
                resolver {
                    querySelections(
                        """
                            fragment Main on Query {
                              ...Fragment_B @skip(if: ${"$"}skipB)
                              ...Fragment_B
                            }

                            fragment Fragment_B on Query {
                              b
                            }
                        """.trimIndent()
                    ) {
                        variables(
                            "skipB",
                            rss = createRSS("Query", "b")
                        ) { _, _ ->
                            mapOf("skipB" to false)
                        }
                    }
                    fn { _, _, _, _, _ -> 1 }
                }
            }
        }.runFeatureTest {
            runQuery("{ a }").assertJson("{data: {a: 1}}")
        }
    }

    /**
     * Regression: a field-level checker RSS rooted on Query (the origin-coordinate leaker shape)
     * must not fire when the runtime type is Foo, not Bar. Before the fix, Bar.value's checker
     * could leak into resolution of Foo.value because isRootType permitted any root-type parent.
     */
    @Test
    fun `field-level checker RSS rooted on Query does not leak into sibling interface implementor`() {
        val sdl = """
            interface Iface { value: String }
            type Foo implements Iface { value: String }
            type Bar implements Iface { value: String }
            extend type Query { iface: Iface, string1: String }
        """.trimIndent()

        val checkerInvocations = ConcurrentHashMap<String, AtomicInteger>()

        EngineTestModule(sdl) {
            field("Query" to "iface") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(ctx.fullSchema.schema.getObjectType("Foo"), mapOf("value" to "foo-value"))
                    }
                }
            }
            fieldWithValue("Query" to "string1", "irrelevant")
            field("Foo" to "value") {
                resolver { fn { _, _, _, _, _ -> "foo-value" } }
                // Checker RSS rooted on Foo — should fire when resolving Foo.value
                checker {
                    objectSelections("fooKey", "value")
                    fn { _, _ ->
                        checkerInvocations.computeIfAbsent("Foo.value") { AtomicInteger() }.incrementAndGet()
                        viaduct.engine.api.CheckerResult.Success
                    }
                }
            }
            field("Bar" to "value") {
                resolver { fn { _, _, _, _, _ -> "bar-value" } }
                // Checker RSS rooted on Query (the prod-observed leaker shape) — must NOT fire for Foo
                checker {
                    querySelections("barKey", "string1")
                    fn { _, _ ->
                        checkerInvocations.computeIfAbsent("Bar.value") { AtomicInteger() }.incrementAndGet()
                        viaduct.engine.api.CheckerResult.Success
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ iface { value } }").assertJson("""{"data": {"iface": {"value": "foo-value"}}}""")

            assertEquals(1, checkerInvocations["Foo.value"]?.get()) { "Foo.value checker should run exactly once" }
            assertEquals(null, checkerInvocations["Bar.value"]) { "Bar.value checker must not run when resolving Foo.value" }
        }
    }

    private fun QueryPlan.planForTest(childPlan: FieldChildPlan): QueryPlan =
        checkNotNull(index.find(childPlan.requiredSelectionSetId)) {
            "Missing QueryPlan for RequiredSelectionSet ${childPlan.requiredSelectionSetId}"
        }

    private fun FeatureTest.runQueryWithTimeout(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1_000,
    ): graphql.ExecutionResult {
        val input = ExecutionInput(
            operationText = query,
            variables = variables,
            requestContext = Any(),
        )
        return kotlinx.coroutines.runBlocking {
            withTimeout(timeoutMillis) {
                DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                    engine.execute(input)
                }.await()
            }
        }
    }
}
