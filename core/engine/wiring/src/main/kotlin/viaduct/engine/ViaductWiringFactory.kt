@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.PropertyDataFetcher
import graphql.schema.TypeResolver
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.InterfaceWiringEnvironment
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.UnionWiringEnvironment
import graphql.schema.idl.WiringFactory
import kotlinx.coroutines.CancellationException
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.execution.ResolverOutputMissingFieldHandler
import viaduct.engine.runtime.observability.ResolverOutputContext

/**
 * graphql-java wiring for the Viaduct Modern engine.
 * Simply uses PropertyDataFetcher for every field, see [viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation]
 * for @Resolver execution.
 */
class ViaductWiringFactory(
    private val coroutineInterop: CoroutineInterop,
) : WiringFactory {
    override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> {
        return DataFetcher { env ->
            val source = env.getSource<Any?>() ?: return@DataFetcher null
            if (source is EngineObjectData) {
                if (source is EngineObjectData.Sync) {
                    // Don't call suspend fetchOrNull to avoid unnecessarily
                    // creating a coroutine
                    val value = source.getOrNull(env.field.name)
                    if (value == null) reportIfMissing(env, source, env.field.name) ?: value else value
                } else {
                    coroutineInterop.scopedFuture {
                        val value = source.fetchOrNull(env.field.name)
                        if (value == null) reportIfMissing(env, source, env.field.name) ?: value else value
                    }
                }
            } else {
                PropertyDataFetcher.fetching<Any>(env.field.name).get(env)
            }
        }
    }

    private fun reportIfMissing(
        environment: DataFetchingEnvironment,
        source: EngineObjectData.Sync,
        fieldName: String,
    ): DataFetcherResult<Any?>? {
        val outputContext =
            environment.getLocalContextForType<ResolverOutputContext>() ?: return null
        val fieldIsPresent =
            try {
                source.isPresent(fieldName)
            } catch (_: Exception) {
                return null
            }
        return if (fieldIsPresent) {
            null
        } else {
            ResolverOutputMissingFieldHandler.reportMissingField(
                environment = environment,
                objectType = source.type.name,
                fieldName = fieldName,
                outputContext = outputContext,
            )
        }
    }

    private suspend fun reportIfMissing(
        environment: DataFetchingEnvironment,
        source: EngineObjectData,
        fieldName: String,
    ): DataFetcherResult<Any?>? {
        val outputContext =
            environment.getLocalContextForType<ResolverOutputContext>() ?: return null
        val fieldIsPresent =
            try {
                source.fetchSelections().any { it == fieldName }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        return if (fieldIsPresent) {
            null
        } else {
            ResolverOutputMissingFieldHandler.reportMissingField(
                environment = environment,
                objectType = source.type.name,
                fieldName = fieldName,
                outputContext = outputContext,
            )
        }
    }

    override fun providesTypeResolver(environment: InterfaceWiringEnvironment): Boolean = true

    override fun getTypeResolver(environment: InterfaceWiringEnvironment) =
        TypeResolver {
            val oer = it.getObject() as? EngineObjectData
                ?: throw IllegalStateException(
                    "Invariant: expected engine result to be an `EngineObjectData` for interface" +
                        " named `${environment.interfaceTypeDefinition.name}`."
                )
            oer.type
        }

    override fun providesTypeResolver(environment: UnionWiringEnvironment) = true

    override fun getTypeResolver(environment: UnionWiringEnvironment) =
        TypeResolver {
            val oed = it.getObject() as? EngineObjectData
                ?: throw IllegalStateException(
                    "Invariant: expected engine result to be an `EngineObjectData` for union " +
                        " named `${environment.unionTypeDefinition.name}`. "
                )
            oed.type
        }

    companion object {
        fun buildRuntimeWiring(coroutineInterop: CoroutineInterop): RuntimeWiring {
            return RuntimeWiring.newRuntimeWiring().wiringFactory(ViaductWiringFactory(coroutineInterop)).build()
        }
    }
}
