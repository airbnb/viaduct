@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory.getLogger
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TenantModuleException
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.NodeResolverDispatcherImpl
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedCheckerDispatcher
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedFieldResolverDispatcher
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedNodeResolverDispatcher
import viaduct.engine.runtime.validation.Validator

class DispatcherRegistryFactory(
    private val tenantAPIBootstrapper: TenantAPIBootstrapper,
    private val validator: Validator<ExecutorValidatorContext>,
    private val checkerExecutorFactory: CheckerExecutorFactory,
    private val resolverInstrumentation: ViaductResolverInstrumentation = ViaductResolverInstrumentation.DEFAULT
) {
    companion object {
        private fun log() = getLogger(this::class.java.name.substringBefore("\$Companion"))
    }

    /** create and return a validated DispatcherRegistry */
    fun create(schema: ViaductSchema): DispatcherRegistry {
        val fieldResolverDispatchers = mutableMapOf<Coordinate, FieldResolverDispatcher>()
        val nodeResolverDispatchers = mutableMapOf<String, NodeResolverDispatcher>()
        val fieldCheckerDispatchers = mutableMapOf<Coordinate, CheckerDispatcher>()
        val typeCheckerDispatchers = mutableMapOf<String, CheckerDispatcher>()

        // Create a collection of executors for validation purpose
        val fieldResolverExecutorsToValidate = mutableMapOf<Coordinate, FieldResolverExecutor>()
        val nodeResolverExecutorsToValidate = mutableMapOf<String, NodeResolverExecutor>()
        val fieldCheckerExecutorsToValidate = mutableMapOf<Coordinate, CheckerExecutor>()
        val typeCheckerExecutorsToValidate = mutableMapOf<String, CheckerExecutor>()

        val tenantModuleBootstrappers = runBlocking(Dispatchers.Default) {
            tenantAPIBootstrapper.tenantModuleBootstrappers()
        }

        var nonContributingModernBootstrappersPresent = false

        // Concatenate resolvers from all bootstrappers into a single list.
        for (tenant in tenantModuleBootstrappers) {
            val (tenantFieldResolverExecutors, tenantNodeResolverExecutors) = try {
                val tenantFieldResolverExecutors = tenant.fieldResolverExecutors(schema)
                val tenantNodeResolverExecutors = tenant.nodeResolverExecutors(schema)
                Pair(tenantFieldResolverExecutors, tenantNodeResolverExecutors)
            } catch (e: TenantModuleException) {
                log().warn("Could not bootstrap $tenant", e)
                continue // still concatenate everything else, just skipping one tenant
            }

            var tenantContributesExecutors = false
            for ((fieldCoord, executor) in tenantFieldResolverExecutors) {
                fieldResolverDispatchers[fieldCoord] = InstrumentedFieldResolverDispatcher(FieldResolverDispatcherImpl(executor), resolverInstrumentation)
                fieldResolverExecutorsToValidate[fieldCoord] = executor
                tenantContributesExecutors = true
            }
            for ((typeName, executor) in tenantNodeResolverExecutors) {
                nodeResolverDispatchers[typeName] = InstrumentedNodeResolverDispatcher(NodeResolverDispatcherImpl(executor), resolverInstrumentation)
                nodeResolverExecutorsToValidate[typeName] = executor
                tenantContributesExecutors = true
            }
            if (!tenantContributesExecutors) {
                log().warn("Bootstrapping $tenant (a ${tenant.javaClass.name}) did not contribute any executors")
                if (tenant.javaClass.simpleName == "ViaductTenantModuleBootstrapper") {
                    nonContributingModernBootstrappersPresent = true
                }
            }
        }

        // Register access checkers
        schema.schema.allTypesAsList.forEach typeLoop@{ type ->
            // Only register checkers for object types (skip types starting with "__" which are reserved by GraphQL)
            if (type is GraphQLObjectType && !type.name.startsWith("__")) {
                val typeName = type.name
                type.fields.forEach fieldLoop@{ field ->
                    // skip fields starting with "__" which are reserved by GraphQL
                    if (field.name.startsWith("__")) {
                        return@fieldLoop
                    }
                    checkerExecutorFactory.checkerExecutorForField(schema, typeName, field.name)?.let {
                        val fieldCoord = typeName to field.name
                        fieldCheckerDispatchers[fieldCoord] = InstrumentedCheckerDispatcher(CheckerDispatcherImpl(it), resolverInstrumentation)
                        fieldCheckerExecutorsToValidate[fieldCoord] = it
                    }
                }
                checkerExecutorFactory.checkerExecutorForType(schema, typeName)?.let {
                    typeCheckerDispatchers[typeName] = InstrumentedCheckerDispatcher(CheckerDispatcherImpl(it), resolverInstrumentation)
                    typeCheckerExecutorsToValidate[typeName] = it
                }
            }
        }
        val dispatcherRegistry = DispatcherRegistry.Impl(fieldResolverDispatchers.toMap(), nodeResolverDispatchers.toMap(), fieldCheckerDispatchers.toMap(), typeCheckerDispatchers.toMap())
        if (dispatcherRegistry.isEmpty() && nonContributingModernBootstrappersPresent) {
            log().warn("Empty executor registry for {}.", tenantModuleBootstrappers)
        }

        validator.validate(
            ExecutorValidatorContext(
                fieldResolverExecutorsToValidate,
                nodeResolverExecutorsToValidate,
                fieldCheckerExecutorsToValidate,
                typeCheckerExecutorsToValidate,
                dispatcherRegistry, // need requiredSelectionSetRegistry on dispatcherRegistry for validation
            )
        )
        return dispatcherRegistry
    }
}
