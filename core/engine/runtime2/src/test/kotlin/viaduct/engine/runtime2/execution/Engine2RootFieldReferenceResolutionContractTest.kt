package viaduct.engine.runtime2.execution

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.invocationContextFor
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.runtime.execution.RootFieldReferenceResolutionContract
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime2.execution.testing.runRuntime2FeatureTest
import viaduct.graphql.test.assertJson

class Engine2RootFieldReferenceResolutionContractTest : RootFieldReferenceResolutionContract, Engine2QueryExecutionContract {
    @Test
    fun `nested queries use the arguments from each root field reference`() {
        val module = EngineTestModule(
            """
            extend type Query {
                referencedItem(id: Int!): Item @resolver
                lookup(id: Int!): String @resolver
                catalog: Catalog
            }
            type Catalog @namespaceType {
                item(id: Int!): Item @resolver
            }
            type Item {
                value: String
            }
            """.trimIndent(),
        ) {
            field("Query" to "referencedItem") {
                resolver {
                    fn { arguments, _, _, _, context ->
                        context.createRootFieldReference(
                            rootFieldPath = listOf("catalog", "item"),
                            type = schema.schema.getObjectType("Item"),
                            args = mapOf("id" to arguments.getValue("id")),
                        )
                    }
                }
            }
            field("Catalog" to "item") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId, isSelective = true) { selectors, context ->
                        selectors.reversed().associateWith { selector ->
                            val itemContext = context.invocationContextFor(selector)
                            val id = selector.arguments.getValue("id") as Int
                            val selections = itemContext.engineSelectionSetFactory.engineSelectionSet(
                                "Query",
                                "lookup(id: \$id)",
                                mapOf("id" to id),
                            )
                            val lookupResult = itemContext.query(selections)
                            val values = mapOf("value" to lookupResult.get("lookup"))
                            Result.success(createEngineObjectData("Item", values))
                        }
                    }
                }
            }
            field("Query" to "lookup") {
                resolver {
                    fn { selectors, _ ->
                        selectors.associateWith { selector ->
                            val id = selector.arguments.getValue("id") as Int
                            Result.success("lookup-$id")
                        }
                    }
                }
            }
        }

        module.runRuntime2FeatureTest {
            val result = runQueryWithTimeout(
                """
                {
                  first: referencedItem(id: 4) { value }
                  second: referencedItem(id: 9) { value }
                }
                """.trimIndent(),
            )

            result.assertJson(
                """
                {
                  "data": {
                    "first": {"value": "lookup-4"},
                    "second": {"value": "lookup-9"}
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CallerAttr")
    override fun `caller is derived from resolver object traversal`() {
        super<RootFieldReferenceResolutionContract>.`caller is derived from resolver object traversal`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CallerAttr")
    override fun `factory backing data remains available to child resolver RSS without duplicate execution`() {
        super<RootFieldReferenceResolutionContract>.`factory backing data remains available to child resolver RSS without duplicate execution`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CallerAttr")
    override fun `caller survives a chain of resolver RSS dependencies`() {
        super<RootFieldReferenceResolutionContract>.`caller survives a chain of resolver RSS dependencies`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "ReferenceChk: Access checks on the reference target path are not enforced")
    override fun `factory field access check failure is propagated`() {
        super<RootFieldReferenceResolutionContract>.`factory field access check failure is propagated`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "ReferenceChk: Access checks on the reference namespace path are not enforced")
    override fun `namespace access check failure prevents factory field execution`() {
        super<RootFieldReferenceResolutionContract>.`namespace access check failure prevents factory field execution`()
    }

    @Engine2Disabled(Engine2DisabledKind.ALT, "RefDedup")
    override fun `equivalent factory references share execution while distinct arguments resolve independently`() {
        super<RootFieldReferenceResolutionContract>.`equivalent factory references share execution while distinct arguments resolve independently`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "NodeLower: A node executor reference to an inline Node result repeats Query.node resolution")
    override fun `node resolver returns a root field reference`() {
        super<RootFieldReferenceResolutionContract>.`node resolver returns a root field reference`()
    }

    @Engine2Disabled(Engine2DisabledKind.ALT, "RootObjRSS")
    override fun `factory resolver reads object and query selection sets`() {
        super<RootFieldReferenceResolutionContract>.`factory resolver reads object and query selection sets`()
    }

    @Engine2Alternate(original = "factory backing data remains available to child resolver RSS without duplicate execution")
    fun `ALTERNATIVE factory backing data remains available to child resolver RSS without duplicate execution`() {
        val factoryCalls = AtomicInteger()
        val localizedStringCalls = AtomicInteger()

        runQueryTest(
            EngineTestModule(
                """
            type UGCText {
                translationConfig: String
                localizedString: String @resolver
            }
            type UGCTextFactory @namespaceType {
                create: UGCText @resolver
            }
            extend type Query {
                ugcTextFactory: UGCTextFactory
                description: UGCText @resolver
            }
        """
            ) {
                field("UGCTextFactory" to "create") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            factoryCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("UGCText"),
                                mapOf("translationConfig" to "French")
                            )
                        }
                    }
                }
                field("UGCText" to "localizedString") {
                    resolver {
                        objectSelections("translationConfig")
                        fn { _, obj, _, _, _ ->
                            localizedStringCalls.incrementAndGet()
                            "Localized with ${obj.fetchAs<String>("translationConfig")}"
                        }
                    }
                }
                field("Query" to "description") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createRootFieldReference(
                                rootFieldPath = listOf("ugcTextFactory", "create"),
                                type = schema.schema.getObjectType("UGCText"),
                                args = emptyMap(),
                            )
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ description { localizedString } }")
                .assertJson("""{"data": {"description": {"localizedString": "Localized with French"}}}""")
        }

        assertEquals(1, factoryCalls.get())
        assertEquals(1, localizedStringCalls.get())
    }

    @Engine2Alternate(original = "equivalent factory references share execution while distinct arguments resolve independently")
    fun `ALTERNATIVE equivalent factory references share execution while distinct arguments resolve independently`() {
        val factoryCalls = AtomicInteger()

        runQueryTest(
            EngineTestModule(
                """
            type Color {
                name: String
            }
            type ColorFactory @namespaceType {
                create(name: String!): Color @resolver
            }
            extend type Query {
                colorFactory: ColorFactory
                colors: [Color] @resolver
            }
        """
            ) {
                field("ColorFactory" to "create") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            factoryCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Color"),
                                mapOf("name" to args.getAs<String>("name"))
                            )
                        }
                    }
                }
                field("Query" to "colors") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            listOf(
                                ctx.createRootFieldReference(
                                    rootFieldPath = listOf("colorFactory", "create"),
                                    type = schema.schema.getObjectType("Color"),
                                    args = mapOf("name" to "Red"),
                                ),
                                ctx.createRootFieldReference(
                                    rootFieldPath = listOf("colorFactory", "create"),
                                    type = schema.schema.getObjectType("Color"),
                                    args = mapOf("name" to "Red"),
                                ),
                                ctx.createRootFieldReference(
                                    rootFieldPath = listOf("colorFactory", "create"),
                                    type = schema.schema.getObjectType("Color"),
                                    args = mapOf("name" to "Blue"),
                                ),
                            )
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ colors { name } }")
                .assertJson("""{"data": {"colors": [{"name": "Red"}, {"name": "Red"}, {"name": "Blue"}]}}""")
        }

        assertEquals(3, factoryCalls.get())
    }

    @Engine2Alternate(original = "factory resolver reads object and query selection sets")
    fun `ALTERNATIVE factory resolver reads object and query selection sets`() {
        runQueryTest(
            EngineTestModule(
                """
            type Product {
                objectName: String
                queryName: String
            }
            type ProductFactory @namespaceType {
                defaultName: String @resolver
                create: Product @resolver
            }
            extend type Query {
                defaultProductName: String @resolver
                productFactory: ProductFactory
                product: Product @resolver
            }
        """
            ) {
                field("ProductFactory" to "defaultName") {
                    resolver {
                        fn { _, _, _, _, _ -> "DefaultWidget" }
                    }
                }
                field("Query" to "defaultProductName") {
                    resolver {
                        fn { _, _, _, _, _ -> "QueryWidget" }
                    }
                }
                field("ProductFactory" to "create") {
                    resolver {
                        querySelections("productFactory { defaultName } defaultProductName")
                        fn { _, _, qry, _, _ ->
                            val productFactory =
                                qry.fetchAs<EngineObjectData>("productFactory")
                            createEngineObjectData(
                                schema.schema.getObjectType("Product"),
                                mapOf(
                                    "objectName" to productFactory.fetchAs<String>("defaultName"),
                                    "queryName" to qry.fetchAs<String>("defaultProductName"),
                                )
                            )
                        }
                    }
                }
                field("Query" to "product") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createRootFieldReference(
                                rootFieldPath = listOf("productFactory", "create"),
                                type = schema.schema.getObjectType("Product"),
                                args = emptyMap(),
                            )
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ product { objectName queryName } }")
                .assertJson("""{"data": {"product": {"objectName": "DefaultWidget", "queryName": "QueryWidget"}}}""")
        }
    }
}
