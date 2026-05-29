@file:OptIn(InternalApi::class)
@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest
import viaduct.arbitrary.common.Config
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.CheckerExecutorFactory as EngineCheckerExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

/**
 * Generate an arbitrary [Viaduct] instance.
 *
 * Every generated Viaduct instance will include generated resolvers for every field or Node type with
 * `@resolver` directives, though it may insert additional resolvers depending on [cfg] (see configuration
 * notes below).
 *
 * For all configurations, generated Viaduct instances are guaranteed to be free of illegal RSS cycles
 *
 * # Configuration
 * This generator supports these configurations:
 * - [UndeclaredFieldResolverWeight]
 * - [UndeclaredNodeResolverWeight]
 * - [FieldCheckerWeight]
 * - [TypeCheckerWeight]
 * - [RequiredSelectionSetWeight]
 * - [FieldResolverFactory]
 * - [NodeResolverFactory]
 * - [VariablesResolverFactory]
 * - [CheckerExecutorFactory]
 */
@VisibleForTest
fun Arb.Companion.viaduct(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<Viaduct> =
    arbitrary { rs ->
        ViaductGen(ViaductGenEnv(schema, cfg, rs))
            .gen()
    }

internal interface ViaductGenEnv {
    val schemas: Schemas
    val resolverCoordinates: ResolverCoordinates
    val cfg: Config
    val rs: RandomSource
    val requiredSelectionSetGen: RequiredSelectionSetGen
    val fieldResolverValueGen: FieldResolverValueGen
    val nodeResolverValueGen: NodeResolverValueGen
    val fieldResolverExecutorGen: FieldResolverExecutorGen
    val nodeResolverExecutorGen: NodeResolverExecutorGen
    val variablesResolverGen: VariablesResolverGen
    val checkerExecutorGen: CheckerExecutorGen

    // at build time, deterministically give each resolver its own env that includes an isolated RandomSource.
    // As the sole user of that RS, they will get deterministic behavior if it's used inside their resolve function
    fun fork(): ViaductGenEnv

    companion object {
        private data class Impl(
            override val schemas: Schemas,
            override val resolverCoordinates: ResolverCoordinates,
            override val cfg: Config,
            override val rs: RandomSource,
        ) : ViaductGenEnv {
            override val requiredSelectionSetGen = RequiredSelectionSetGen(this)
            override val fieldResolverValueGen = FieldResolverValueGen(this)
            override val nodeResolverValueGen = NodeResolverValueGen(this)
            override val fieldResolverExecutorGen = FieldResolverExecutorGen(this)
            override val nodeResolverExecutorGen = NodeResolverExecutorGen(this)
            override val variablesResolverGen = VariablesResolverGen(this)
            override val checkerExecutorGen = CheckerExecutorGen(this)

            override fun fork(): ViaductGenEnv = copy(rs = rs.fork())
        }

        operator fun invoke(
            schema: ViaductSchema,
            cfg: Config,
            rs: RandomSource
        ): ViaductGenEnv =
            Impl(
                Schemas(schema),
                ResolverCoordinates(schema, cfg, rs),
                cfg,
                rs
            )
    }
}

private class ViaductGen(private val env: ViaductGenEnv) {
    fun gen(): Viaduct =
        StandardViaduct.Builder()
            .withSchemaConfiguration(SchemaConfiguration.fromSchema(env.schemas.viaductSchema))
            .withTenantAPIBootstrapperBuilders(genTenantModuleBootstrapperBuilders())
            .withCheckerExecutorFactory(genCheckerExecutorFactory())
            .build()

    private fun genTenantModuleBootstrapperBuilders(): List<TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>> {
        val bootstrapper = genTenantApiBootstrapper()
        return listOf(
            object : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
                override fun create() = bootstrapper
            }
        )
    }

    private fun genTenantApiBootstrapper(): TenantAPIBootstrapper {
        val tenantModuleBootstrappers = listOf(genTenantModuleBootstrapper())

        return object : TenantAPIBootstrapper {
            override suspend fun tenantModuleBootstrappers(): Iterable<LegacyTenantModuleBootstrapper> = tenantModuleBootstrappers
        }
    }

    private fun genTenantModuleBootstrapper(): LegacyTenantModuleBootstrapper {
        val fieldResolverExecutors = env.resolverCoordinates.fieldResolvers.map { coord ->
            coord to env.fieldResolverExecutorGen.gen(coord)
        }
        val nodeResolverExecutors = env.resolverCoordinates.nodeResolvers.map { tname ->
            tname to env.nodeResolverExecutorGen.gen(tname)
        }

        return object : LegacyTenantModuleBootstrapper {
            override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> = fieldResolverExecutors

            override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> = nodeResolverExecutors
        }
    }

    private fun genCheckerExecutorFactory(): EngineCheckerExecutorFactory {
        val fieldCheckerExecutors = env.schemas.viaductSchema.objectCoordinates.mapNotNull { coord ->
            if (env.rs.sampleWeight(env.cfg[FieldCheckerWeight])) {
                coord to env.checkerExecutorGen.gen(coord)
            } else {
                null
            }
        }.toMap()

        val typeCheckerExecutors = env.schemas.viaductSchema.objects.mapNotNull { obj ->
            if (env.rs.sampleWeight(env.cfg[TypeCheckerWeight])) {
                obj.name to env.checkerExecutorGen.gen(obj.name to null)
            } else {
                null
            }
        }.toMap()

        return object : EngineCheckerExecutorFactory {
            override fun checkerExecutorForField(
                schema: ViaductSchema,
                typeName: String,
                fieldName: String
            ): CheckerExecutor? = fieldCheckerExecutors[typeName to fieldName]

            override fun checkerExecutorForType(
                schema: ViaductSchema,
                typeName: String
            ): CheckerExecutor? = typeCheckerExecutors[typeName]
        }
    }
}
