package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class RootFieldReferenceResolutionContractTest : RootFieldReferenceResolutionContract, Engine1QueryExecutionContract {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `nested factory references stay lazy with field mat enabled`(selective: Boolean) {
        val factoryCalls = AtomicInteger()
        MockTenantModuleBootstrapper(
            """
                extend type Query { amenities: Amenities ugcTextFactory: UGCTextFactory }
                type Amenities { title: String groups: [Group] }
                type Group { notes: UGCText }
                type UGCText { source: String }
                type UGCTextFactory @namespaceType { fromTranslatedText: UGCText }
            """.trimIndent(),
        ) {
            field("Query" to "amenities") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = selective,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, context ->
                            createEngineObjectData(
                                "Amenities",
                                mapOf(
                                    "title" to "Amenities",
                                    "groups" to listOf(
                                        createEngineObjectData(
                                            "Group",
                                            mapOf(
                                                "notes" to context.createRootFieldReference(
                                                    listOf("ugcTextFactory", "fromTranslatedText"),
                                                    schema.schema.getObjectType("UGCText"),
                                                    emptyMap(),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
            field("UGCTextFactory" to "fromTranslatedText") {
                resolver {
                    fn { _, _, _, _, _ ->
                        factoryCalls.incrementAndGet()
                        createEngineObjectData("UGCText", mapOf("source" to "Safety note"))
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION),
            ),
        ) {
            runQueryWithin("{ amenities { title } }", timeoutMillis = 2_000)
                .assertJson("{data: {amenities: {title: 'Amenities'}}}")
            assertEquals(0, factoryCalls.get())
            runQueryWithin("{ amenities { groups { notes { source } } } }", timeoutMillis = 2_000)
                .assertJson("{data: {amenities: {groups: [{notes: {source: 'Safety note'}}]}}}")
            assertEquals(1, factoryCalls.get())
        }
    }
}
