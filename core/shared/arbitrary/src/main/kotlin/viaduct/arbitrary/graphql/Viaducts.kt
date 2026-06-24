@file:OptIn(InternalApi::class)

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
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

/**
 * Generate an arbitrary [Viaduct] instance.
 *
 * Every generated Viaduct instance will include generated resolvers for every field or Node type with
 * `@resolver` directives, though it may insert additional resolvers depending on [cfg] (see configuration
 * notes below).
 *
 * Note that when using this generator with arbitrarily-generated [ViaductSchema]s, you will likely want
 * to increase the resolver density with [UndeclaredFieldResolverWeight] and [UndeclaredNodeResolverWeight].
 *
 * For all configurations, generated Viaduct instances are guaranteed to be free of illegal RSS cycles
 *
 * # Configuration
 * This generator supports these configurations:
 * - [UndeclaredFieldResolverWeight]
 * - [UndeclaredNodeResolverWeight]
 * - [SelectiveResolverWeight]
 * - [BatchingResolverWeight]
 * - [IncludeRequiredResolvers]
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
    val resolverConfig: ResolverConfig
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
            override val resolverConfig: ResolverConfig,
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
            rs: RandomSource,
            resolverConfig: ResolverConfig = ResolverConfig(schema, cfg, rs),
        ): ViaductGenEnv =
            Impl(
                Schemas(schema),
                resolverConfig,
                cfg,
                rs
            )
    }
}

private class ViaductGen(private val env: ViaductGenEnv) {
    fun gen(): Viaduct {
        val fieldResolverExecutors = genFieldResolverExecutors()
        val nodeResolverExecutors = genNodeResolverExecutors()
        val fieldCheckerExecutors = genFieldCheckerExecutors()
        val typeCheckerExecutors = genTypeCheckerExecutors()

        @Suppress("DEPRECATION")
        val viaduct = StandardViaduct.Builder()
            .withSchemaConfiguration(SchemaConfiguration.fromSchema(env.schemas.viaductSchema))
            .withTenantAPIBootstrapperBuilders(genTenantModuleBootstrapperBuilders(fieldResolverExecutors, nodeResolverExecutors))
            .withCheckerExecutorFactory(genCheckerExecutorFactory(fieldCheckerExecutors, typeCheckerExecutors))
            // Framework flags on, matching FeatureTest's MockFlagManager.Enabled — in particular
            // selective resolver execution, which the generated resolvers exercise.
            .withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = true
            })
            .build()

        val descriptorConfig = GeneratedViaductDescriptorConfig(
            schema = env.schemas.viaductSchema,
            fieldResolverExecutors = fieldResolverExecutors,
            instrumentedFieldResolverFactory = env.cfg[FieldResolverFactory] as? FieldResolver.Factory.Instrumented,
            nodeResolverExecutors = nodeResolverExecutors,
            fieldCheckerExecutors = fieldCheckerExecutors,
            typeCheckerExecutors = typeCheckerExecutors,
        )
        return DescribedViaduct(
            viaduct,
            {
                ViaductDescriptor.fromGenerated(viaduct, descriptorConfig)
            }
        )
    }

    private fun genTenantModuleBootstrapperBuilders(
        fieldResolverExecutors: List<Pair<Coordinate, FieldResolverExecutor>>,
        nodeResolverExecutors: List<Pair<String, NodeResolverExecutor>>,
    ): List<TenantAPIBootstrapperBuilder> {
        val bootstrapper = genTenantApiBootstrapper(fieldResolverExecutors, nodeResolverExecutors)
        return listOf(
            object : TenantAPIBootstrapperBuilder {
                override fun create() = bootstrapper
            }
        )
    }

    private fun genTenantApiBootstrapper(
        fieldResolverExecutors: List<Pair<Coordinate, FieldResolverExecutor>>,
        nodeResolverExecutors: List<Pair<String, NodeResolverExecutor>>,
    ): TenantAPIBootstrapper {
        val tenantModuleBootstrappers = listOf(genTenantModuleBootstrapper(fieldResolverExecutors, nodeResolverExecutors))

        return object : TenantAPIBootstrapper {
            override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> = tenantModuleBootstrappers
        }
    }

    private fun genTenantModuleBootstrapper(
        fieldResolverExecutors: List<Pair<Coordinate, FieldResolverExecutor>>,
        nodeResolverExecutors: List<Pair<String, NodeResolverExecutor>>,
    ): TenantModuleBootstrapper =
        object : TenantModuleBootstrapper {
            override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> = fieldResolverExecutors

            override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> = nodeResolverExecutors
        }

    private fun genFieldResolverExecutors(): List<Pair<Coordinate, FieldResolverExecutor>> =
        env.resolverConfig.fieldResolvers.map { coord ->
            coord to env.fieldResolverExecutorGen.gen(coord)
        }

    private fun genNodeResolverExecutors(): List<Pair<String, NodeResolverExecutor>> =
        env.resolverConfig.nodeResolvers.map { tname ->
            tname to env.nodeResolverExecutorGen.gen(tname)
        }

    private fun genFieldCheckerExecutors(): Map<Coordinate, CheckerExecutor> =
        env.schemas.viaductSchema.objectCoordinates.mapNotNull { coord ->
            if (env.rs.sampleWeight(env.cfg[FieldCheckerWeight])) {
                coord to env.checkerExecutorGen.gen(coord)
            } else {
                null
            }
        }.toMap()

    private fun genTypeCheckerExecutors(): Map<String, CheckerExecutor> =
        env.schemas.viaductSchema.objects.mapNotNull { obj ->
            if (env.rs.sampleWeight(env.cfg[TypeCheckerWeight])) {
                obj.name to env.checkerExecutorGen.gen(obj.name to null)
            } else {
                null
            }
        }.toMap()

    private fun genCheckerExecutorFactory(
        fieldCheckerExecutors: Map<Coordinate, CheckerExecutor>,
        typeCheckerExecutors: Map<String, CheckerExecutor>,
    ): EngineCheckerExecutorFactory {
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

/** render this [Viaduct] to a human-readable String */
fun Viaduct.dump(): String {
    val describedViaduct = this as? DescribedViaduct
        ?: throw UnsupportedOperationException(
            "Unsupported operation: only a Viaduct created by Arb.viaduct may be dumped"
        )
    return describedViaduct.descriptor().toString()
}
