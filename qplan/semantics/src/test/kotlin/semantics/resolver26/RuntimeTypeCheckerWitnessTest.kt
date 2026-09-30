package semantics.resolver26

import org.junit.jupiter.api.Test
import semantics.arbitrary.GeneratedTypeCheckerMode
import semantics.arbitrary.ResolverTestCoordinates

class RuntimeTypeCheckerWitnessTest : Resolver26DispatcherResource {
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
