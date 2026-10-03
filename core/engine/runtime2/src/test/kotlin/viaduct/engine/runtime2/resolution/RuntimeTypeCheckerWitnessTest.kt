package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.ResolverTestCoordinates

class RuntimeTypeCheckerWitnessTest : ResolutionDispatcherResource {
    @Test
    fun `named providers and conditional paths retain occurrence local bindings`() {
        listOf(GeneratedTypeCheckerMode.SUCCESS, GeneratedTypeCheckerMode.DENIAL, GeneratedTypeCheckerMode.MIXED).forEach { mode ->
            for (query in 1..2) {
                validateRuntimeTypeCheckerCase(
                    mode,
                    ResolverTestCoordinates("directed-type-bindings", 424242L, 1, 1, query),
                    resolverDispatcher,
                )
            }
        }
    }
}
