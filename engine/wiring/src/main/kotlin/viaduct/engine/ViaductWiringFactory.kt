package viaduct.engine

import graphql.schema.DataFetcher
import graphql.schema.PropertyDataFetcher
import graphql.schema.TypeResolver
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.InterfaceWiringEnvironment
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.UnionWiringEnvironment
import graphql.schema.idl.WiringFactory
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.coroutines.CoroutineInterop

/**
 * graphql-java wiring for the Viaduct Modern engine.
 * Simply uses PropertyDataFetcher for every field, see [viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation]
 * for @Resolver execution.
 */
class ViaductWiringFactory(private val coroutineInterop: CoroutineInterop) : WiringFactory {
    override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> {
        return DataFetcher { env ->
            val source = env.getSource<Any?>() ?: return@DataFetcher null
            if (source is EngineObjectData) {
                if (source is EngineObjectData.Sync) {
                    // Don't call suspend fetchOrNull to avoid unnecessarily
                    // creating a coroutine
                    source.getOrNull(env.field.name)
                } else {
                    coroutineInterop.scopedFuture { source.fetchOrNull(env.field.name) }
                }
            } else {
                PropertyDataFetcher.fetching<Any>(env.field.name).get(env)
            }
        }
    }

    override fun providesTypeResolver(environment: InterfaceWiringEnvironment?): Boolean = true

    override fun getTypeResolver(environment: InterfaceWiringEnvironment) =
        TypeResolver {
            val oer = it.getObject() as? EngineObjectData
                ?: throw IllegalStateException(
                    "Invariant: expected engine result to be an `EngineObjectData` for interface" +
                        " named `${environment.interfaceTypeDefinition.name}`."
                )
            oer.graphQLObjectType
        }

    override fun providesTypeResolver(environment: UnionWiringEnvironment?) = true

    override fun getTypeResolver(environment: UnionWiringEnvironment) =
        TypeResolver {
            val oed = it.getObject() as? EngineObjectData
                ?: throw IllegalStateException(
                    "Invariant: expected engine result to be an `EngineObjectData` for union " +
                        " named `${environment.unionTypeDefinition.name}`. "
                )
            oed.graphQLObjectType
        }

    companion object {
        fun buildRuntimeWiring(coroutineInterop: CoroutineInterop): RuntimeWiring {
            return RuntimeWiring.newRuntimeWiring().wiringFactory(ViaductWiringFactory(coroutineInterop)).build()
        }
    }
}
