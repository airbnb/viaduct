package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.Coordinate
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.mocks.MockLegacyTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.observability.ExecutionObservabilityContext

@ExperimentalCoroutinesApi
class FieldExecutionObservabilityFeatureTest {
    private val schema = """
        extend type Query { idField: String, string1: String, string2: String, hasArgs1(x:Int):Int, hasArgs2(x:String):String, hasArgs3(x:Int):Int }
    """.trimIndent()

    @Test
    fun `resolver name is passed to instrumentation`() {
        val instrumentation = ObservabilityInstrumentation()

        MockLegacyTenantModuleBootstrapper(schema) {
            field("Query" to "idField") {
                resolver {
                    resolverName("query-id-field-resolver")
                    fn { _, _, _, _, _ -> "QmF6OjE=" }
                }
            }
            field("Query" to "string1") {
                resolver {
                    resolverName("query-string1-resolver")
                    objectSelections("idField")
                    fn { _, obj, _, _, _ -> obj.fetchAs<String>("idField") }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = instrumentation.asStandardInstrumentation,
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("query testQuery {string1}").assertJson("{data: {string1: \"QmF6OjE=\"}}")

            assertEquals(
                setOf("Query" to "idField", "Query" to "string1"),
                instrumentation.fieldToRequiredByLookup.keys
            )
            assertEquals(setOf("RESOLVER:query-string1-resolver"), instrumentation.getFieldRequiredBy("Query", "idField").toSet())
            assertEquals(setOf("OPERATION:testQuery"), instrumentation.getFieldRequiredBy("Query", "string1").toSet())
            assertEquals("query-id-field-resolver", instrumentation.getFieldResolvedBy("Query", "idField"))
            assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1"))
        }
    }

    @Test
    fun `no operation name does not break attribution`() {
        val instrumentation = ObservabilityInstrumentation()

        MockLegacyTenantModuleBootstrapper(schema) {
            field("Query" to "idField") {
                resolver {
                    resolverName("query-id-field-resolver")
                    fn { _, _, _, _, _ -> "QmF6OjE=" }
                }
            }
            field("Query" to "string1") {
                resolver {
                    resolverName("query-string1-resolver")
                    objectSelections("idField")
                    fn { _, obj, _, _, _ -> obj.fetchAs<String>("idField") }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = instrumentation.asStandardInstrumentation,
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("{string1}").assertJson("{data: {string1: \"QmF6OjE=\"}}")

            assertEquals(setOf("RESOLVER:query-string1-resolver"), instrumentation.getFieldRequiredBy("Query", "idField").toSet())
            assertEquals(setOf(null), instrumentation.getFieldRequiredBy("Query", "string1").toSet())
        }
    }

    @Test
    fun `same field fetched multiple times carries correct attribution`() {
        val instrumentation = ObservabilityInstrumentation()
        val childPlanExecuted = CountDownLatch(2)

        val countDownInstrumentation = countDownOnFieldExecution(
            "Query" to "string1",
            mapOf("OPERATION:testQuery" to childPlanExecuted)
        )

        MockLegacyTenantModuleBootstrapper(schema) {
            field("Query" to "idField") {
                resolver {
                    resolverName("query-id-field-resolver")
                    fn { _, _, _, _, _ -> "QmF6OjE=" }
                }
            }
            field("Query" to "string1") {
                resolver {
                    resolverName("query-string1-resolver")
                    objectSelections("idField")
                    fn { _, _, _, _, _ ->
                        childPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = ChainedModernGJInstrumentation(
                    listOf(instrumentation.asStandardInstrumentation, countDownInstrumentation)
                ),
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("query testQuery {first: string1, second: string1}").assertJson("{data: {first: \"true\", second: \"true\"}}")

            assertTrue(instrumentation.getFieldRequiredBy("Query", "string1").size == 2) {
                "Expected string1 to be required twice, found: ${instrumentation.getFieldRequiredBy("Query", "string1")}"
            }
            assertTrue(instrumentation.getFieldRequiredBy("Query", "idField").toSet().contains("RESOLVER:query-string1-resolver")) {
                "Expected query-string1-resolver to be in required-by for idField"
            }
        }
    }

    @Test
    fun `field queried by both operation and resolver has both attributions`() {
        val instrumentation = ObservabilityInstrumentation()
        val idFieldResolverChildPlanExecuted = CountDownLatch(1)
        val string1ResolverChildPlanExecuted = CountDownLatch(1)

        val countDownInstrumentation = countDownOnFieldExecution(
            "Query" to "idField",
            mapOf(
                "RESOLVER:query-string1-resolver" to string1ResolverChildPlanExecuted,
                "OPERATION:testQuery" to idFieldResolverChildPlanExecuted
            )
        )

        MockLegacyTenantModuleBootstrapper(schema) {
            field("Query" to "idField") {
                resolver {
                    resolverName("query-id-field-resolver")
                    fn { _, _, _, _, _ ->
                        idFieldResolverChildPlanExecuted.await(1, TimeUnit.SECONDS)
                        "QmF6OjE="
                    }
                }
            }
            field("Query" to "string1") {
                resolver {
                    resolverName("query-string1-resolver")
                    objectSelections("idField")
                    fn { _, _, _, _, _ ->
                        string1ResolverChildPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = ChainedModernGJInstrumentation(
                    listOf(instrumentation.asStandardInstrumentation, countDownInstrumentation)
                ),
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("query testQuery {idField, string1}").assertJson("{data: {idField: \"QmF6OjE=\", string1: \"true\"}}")

            assertEquals(
                setOf("RESOLVER:query-string1-resolver", "OPERATION:testQuery"),
                instrumentation.getFieldRequiredBy("Query", "idField").toSet()
            )
            assertEquals(setOf("OPERATION:testQuery"), instrumentation.getFieldRequiredBy("Query", "string1").toSet())
        }
    }

    @Test
    fun `field queried by multiple resolvers carries both resolver attributions`() {
        val instrumentation = ObservabilityInstrumentation()
        val string1ChildPlanExecuted = CountDownLatch(1)
        val string2ChildPlanExecuted = CountDownLatch(1)

        val countDownInstrumentation = countDownOnFieldExecution(
            "Query" to "idField",
            mapOf(
                "RESOLVER:query-string1-resolver" to string1ChildPlanExecuted,
                "RESOLVER:query-string2-resolver" to string2ChildPlanExecuted
            )
        )

        MockLegacyTenantModuleBootstrapper(schema) {
            field("Query" to "idField") {
                resolver {
                    resolverName("query-id-field-resolver")
                    fn { _, _, _, _, _ -> "QmF6OjE=" }
                }
            }
            field("Query" to "string1") {
                resolver {
                    resolverName("query-string1-resolver")
                    objectSelections("idField")
                    fn { _, _, _, _, _ ->
                        string1ChildPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                    }
                }
            }
            field("Query" to "string2") {
                resolver {
                    resolverName("query-string2-resolver")
                    objectSelections("idField")
                    fn { _, _, _, _, _ ->
                        string2ChildPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = ChainedModernGJInstrumentation(
                    listOf(instrumentation.asStandardInstrumentation, countDownInstrumentation)
                ),
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("query testQuery {string1, string2}").assertJson("{data: {string1: \"true\", string2: \"true\"}}")

            assertEquals(
                setOf("RESOLVER:query-string1-resolver", "RESOLVER:query-string2-resolver"),
                instrumentation.getFieldRequiredBy("Query", "idField").toSet()
            )
        }
    }

    @Test
    fun `resolved_by attribution correct in nested fields`() {
        val instrumentation = ObservabilityInstrumentation()
        val barSchema = """
            extend type Query { string1: String, foo: Foo }
            type Foo { value: String, bar: Bar, valueWithoutResolver: String }
            type Bar { value: String }
        """.trimIndent()
        val barObjData = mapOf<String, Any?>("value" to "Bar.value=[VALUE]")

        MockLegacyTenantModuleBootstrapper(barSchema) {
            field("Query" to "string1") {
                resolver {
                    resolverName("query-string1-resolver")
                    objectSelections("foo { value, valueWithoutResolver }")
                    fn { _, obj, _, _, _ ->
                        val fooObj = obj.fetch("foo")
                        "Query.string1=[${(fooObj as? viaduct.engine.api.EngineObjectData)?.fetch("value")}]"
                    }
                }
            }
            field("Query" to "foo") {
                resolver {
                    resolverName("query-foo-resolver")
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(
                            ctx.fullSchema.schema.getObjectType("Foo"),
                            mapOf("valueWithoutResolver" to "valueWithoutResolver")
                        )
                    }
                }
            }
            field("Foo" to "value") {
                resolver {
                    resolverName("foo-value-resolver")
                    objectSelections("bar { value }")
                    fn { _, obj, _, _, _ ->
                        val barObj = obj.fetch("bar")
                        "Foo.value=[${(barObj as? viaduct.engine.api.EngineObjectData)?.fetch("value")}]"
                    }
                }
            }
            field("Foo" to "bar") {
                resolver {
                    resolverName("foo-bar-resolver")
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(ctx.fullSchema.schema.getObjectType("Bar"), barObjData)
                    }
                }
            }
            field("Bar" to "value") {
                resolver {
                    resolverName("bar-value-resolver")
                    fn { _, _, _, _, _ -> "Bar.value=[VALUE]" }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = instrumentation.asStandardInstrumentation,
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("{string1, foo {valueWithoutResolver}}")

            assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1"))
            assertEquals("query-foo-resolver", instrumentation.getFieldResolvedBy("Query", "foo"))
            assertEquals("foo-value-resolver", instrumentation.getFieldResolvedBy("Foo", "value"))
            assertEquals("query-foo-resolver", instrumentation.getFieldResolvedBy("Foo", "valueWithoutResolver"))
            assertEquals("foo-bar-resolver", instrumentation.getFieldResolvedBy("Foo", "bar"))
            assertEquals("bar-value-resolver", instrumentation.getFieldResolvedBy("Bar", "value"))
        }
    }

    private fun countDownOnFieldExecution(
        coordinate: Coordinate,
        attributionToLatchMap: Map<String?, CountDownLatch>
    ): ViaductModernGJInstrumentation =
        object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldExecution {
            override fun beginFieldExecution(
                parameters: InstrumentationFieldParameters,
                state: InstrumentationState?
            ): InstrumentationContext<Any>? {
                val typeName = parameters.executionStepInfo.objectType.name
                val fieldName = parameters.executionStepInfo.field.name
                if (coordinate == (typeName to fieldName)) {
                    val attribution = parameters.executionContext.getLocalContextForType<ExecutionObservabilityContext>()?.attribution?.toTagString()
                    attributionToLatchMap[attribution]?.countDown()
                }
                return SimpleInstrumentationContext.noOp()
            }
        }.asStandardInstrumentation

    class ObservabilityInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginFieldExecution,
        IViaductInstrumentation.WithBeginFieldFetch {
        val fieldToRequiredByLookup = ConcurrentHashMap<Coordinate, CopyOnWriteArrayList<String?>>()
        val fieldToResolvedByLookup = ConcurrentHashMap<Coordinate, String>()

        override fun beginFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            val coord = parameters.executionStepInfo.objectType.name to parameters.executionStepInfo.field.name
            val attribution = parameters.executionContext.getLocalContextForType<ExecutionObservabilityContext>()?.attribution
            fieldToRequiredByLookup.computeIfAbsent(coord) { CopyOnWriteArrayList() }.add(attribution?.toTagString())
            return SimpleInstrumentationContext.noOp()
        }

        override fun beginFieldFetch(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            val coord = parameters.executionStepInfo.objectType.name to parameters.executionStepInfo.field.name
            parameters.environment.getLocalContextForType<ExecutionObservabilityContext>()?.resolverMetadata?.name?.let {
                fieldToResolvedByLookup[coord] = it
            }
            return SimpleInstrumentationContext.noOp()
        }

        fun getFieldRequiredBy(
            typeName: String,
            fieldName: String
        ) = fieldToRequiredByLookup[typeName to fieldName] ?: emptyList<String?>()

        fun getFieldResolvedBy(
            typeName: String,
            fieldName: String
        ) = fieldToResolvedByLookup[typeName to fieldName]
    }
}
