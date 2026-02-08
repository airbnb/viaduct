package viaduct.engine.api.mocks

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema

@DslMarker
/**
 * A DslMarker tells the compiler to take the members of outer
 * classes out of scope for lambdas of inner classes.  For
 * example:
 * ```
 * class OuterScope {
 *     private val foos =  mutableListOf<Int>()
 *     private val bars = mutableListOf<String>()
 *
 *     fun addFoo(i: Int) = foos.add(i)
 *     fun bar(block: InnerScope.() -> Unit) = InnerScope().block()
 *
 *     @TenantModuleBootstrapperDsl
 *     inner class InnerScope {
 *         fun add(s: String) = bars.add(s)
 *     }
 * }
 *
 * OuterScope() {
 *     addFoo(1) // okay
 *     bar {
 *        add("hello") // okay
 *        addFoo(2) // Compiler error - addFoo is out of scope b/c of the DslMarker
 *     }
 * }
 * ```
 */
annotation class TenantModuleBootstrapperDsl

/**
 * A Kotlin DSL for writing end-to-end operation execution test cases
 * that use _only_ the engine API and not any tenant-api features.
 * This is the engine-level equivalent of the tenant runtime FeatureTest.
 *
 * Usage:
 * ```kotlin
 *    MockTenantModuleBootstrapper("""
 *       type Query {
 *           hello: String
 *           world: String
 *           greeting: String
 *           calc(n: Int): Int
 *           answer: Int
 *       }"""
 *    ) {
 *        fieldWithValue("Query" to "hello", "Hello")  // resolves to a constant value
 *        fieldWithValue("Query" to "world", "World")  // resolves to a constant value
 *
 *        field("Query" to "greeting") {
 *            // Resolver function for this field
 *            resolver {
 *                objectSelections("hello world")
 *                fn { args, obj, selections, context -> "${obj.fetch("hello")}, ${obj.fetch("world")}!" }
 *            }
 *        }
 *
 *        field("Query" to "calc") {
 *            resolver {
 *                fn { args, obj, selections, context -> args["n"] + 1 }
 *            }
 *        }
 *
 *        field("Query" to "answer") {
 *            // Resolver function for this field
 *            resolver {
 *                objectSelections("calc($v)") {
 *                    variables("v") { args, context -> mapOf("v" to 41) }
 *                }
 *                fn { args, obj, selections, context -> (obj.fetch("calc") as Int) + 1 }
 *            }
 *
 *            // Checker function for this field (optional)
 *            checker {
 *                fn { args, objectDataMap -> /* validation logic */ }
 *            }
 *        }
 *    }
 * ```
 *
 * Identifiers
 * in all scopes, `schema` is the schema passed to MockTenantModuleBootstrapper
 *
 * in MockTenantModuleBootstrapper scope:
 * * `fieldWithValue` creates a simple resolver returning a constant value
 * * `field` creates nested scope for configuring field-relate executors
 * * `node` creates a nested scope for configurating node-related executors
 *
 * in `field` scope:
 * * `coord` the Coordinate of the field
 * * `objectType` GraphQLObjectType of the type in the coordinate
 * * `fieldType` the GraphQLOutputType of the field in the cooridnate
 * * `queryType` GraphQLObjectType of "Query"
 * * `resolver` a function for creating a resolver for the field
 * * `checker` a function for creating a checker for the field
 * * `noAccess` a function that throws a SecurityException (for use in checker bodies)
 * you can provide a resolver or checker or both for a field
 *
 * the `node` scope is similar to the `field` scope, except that
 * it's intended for node resolvers/checkers.
 */
class MockTenantModuleBootstrapperDSL<F : Any>(
    val schema: ViaductSchema,
    val fac: F,
) {
    fun create() =
        MockTenantModuleBootstrapper(
            schema,
            fieldResolverExecutors,
            nodeResolverExecutors,
            checkerExecutors,
            typeCheckerExecutors,
        )

    internal val checkerExecutors = mutableMapOf<Coordinate, CheckerExecutor>()
    internal val typeCheckerExecutors = mutableMapOf<String, CheckerExecutor>()
    internal val fieldResolverExecutors = mutableListOf<Pair<Coordinate, FieldResolverExecutor>>()
    internal val nodeResolverExecutors = mutableListOf<Pair<String, NodeResolverExecutor>>()

    private fun <K : Any, V : Any?> MutableMap<K, V>.putIfMissingOrFail(
        key: K,
        value: () -> V
    ) {
        if (this.contains(key)) {
            throw IllegalArgumentException("$key already assigned to ${this[key]}")
        }
        this[key] = value()
    }

    private fun <K : Any, V : Any?> MutableList<Pair<K, V>>.putIfMissingOrFail(
        key: K,
        value: () -> V
    ) {
        if (this.any { it.first == key }) {
            throw IllegalArgumentException("$key already assigned to ${this.first { it.first == key }.second}")
        }
        this.add(Pair(key, value()))
    }

    val queryType: GraphQLObjectType get() = schema.schema.getQueryType()

    fun fieldWithValue(
        coord: Coordinate,
        value: Any?
    ) {
        fieldResolverExecutors.putIfMissingOrFail(coord) { MockFieldUnbatchedResolverExecutor(resolverId = coord.first + "." + coord.second) { _, _, _, _, _ -> value } }
    }

    fun field(
        coord: Coordinate,
        block: FieldScope.() -> Unit
    ) = block(FieldScope(coord))

    fun type(
        typeName: String,
        block: TypeScope.() -> Unit
    ) = block(TypeScope(typeName))

    @TenantModuleBootstrapperDsl
    inner class FieldScope(
        val coord: Coordinate,
    ) {
        // DSL marker hides these -- reintroduce them
        val schema: ViaductSchema get() = this@MockTenantModuleBootstrapperDSL.schema
        val fac: F get() = this@MockTenantModuleBootstrapperDSL.fac
        val queryType: GraphQLObjectType get() = this@MockTenantModuleBootstrapperDSL.queryType
        val objectType: GraphQLObjectType get() = schema.schema.getObjectType(coord.first)!!
        val fieldType: GraphQLOutputType get() = objectType.getFieldDefinition(coord.second)!!.getType()
        val resolverId: String get() = coord.first + "." + coord.second

        fun value(value: Any?) {
            resolverExecutor {
                MockFieldUnbatchedResolverExecutor(resolverId = resolverId) { _, _, _, _, _ -> value }
            }
        }

        fun valueFromContext(fn: (EngineExecutionContext) -> Any?) {
            resolverExecutor {
                MockFieldUnbatchedResolverExecutor(resolverId = resolverId) { _, _, _, _, ctx -> fn(ctx) }
            }
        }

        fun resolverExecutor(block: FieldScope.() -> FieldResolverExecutor) {
            fieldResolverExecutors.putIfMissingOrFail(coord) { block() }
        }

        /**
         * This function will create a [FieldResolverExecutor] from a [FieldUnbatchedResolverFn] lambda.
         * If you have an independant way of creating a [FieldResolverExecutor] use
         * [resolverExecutor] to insert it directly into the resolver registry.
         */
        fun resolver(block: ResolverScope.() -> Unit) {
            resolverExecutor {
                val r = ResolverScope().apply { block() }
                when {
                    r.unbatchedResolveFn != null && r.batchResolveFn != null -> {
                        throw IllegalArgumentException("resolver block cannot define both unbatched and batch resolver functions.")
                    }
                    r.unbatchedResolveFn != null -> {
                        MockFieldUnbatchedResolverExecutor(
                            r.objectSelections?.toRSS(),
                            r.querySelections?.toRSS(),
                            r.resolverName,
                            resolverId,
                            r.unbatchedResolveFn!!
                        )
                    }
                    r.batchResolveFn != null -> {
                        MockFieldBatchResolverExecutor(
                            r.objectSelections?.toRSS(),
                            r.querySelections?.toRSS(),
                            r.resolverName,
                            resolverId,
                            r.batchResolveFn!!
                        )
                    }
                    else -> {
                        throw IllegalArgumentException("resolver block must define either an unbatched or batch resolver function.")
                    }
                }
            }
        }

        fun checkerExecutor(block: FieldScope.() -> CheckerExecutor) {
            checkerExecutors.putIfMissingOrFail(coord) { block() }
        }

        /**
         * This function will create a [CheckerExecutor] from a [CheckerFn] lambda.
         * If you have an independant way of creating a [CheckerExecutor] use
         * [checkerExecutor] to insert it directly into the checker registry.
         */
        fun checker(block: CheckerScope.() -> Unit) {
            checkerExecutor {
                val c = CheckerScope().apply { block() }
                val fn = c.executeFn
                    ?: throw IllegalArgumentException("checker block must define a checker function.")
                MockCheckerExecutor(c.requiredSelectionSets, fn)
            }
        }

        @TenantModuleBootstrapperDsl
        inner class SelectionsScope(private val typeName: String, val objectSelectionsText: String, val forChecker: Boolean) {
            private var variableProviders: MutableList<VariablesResolver> = mutableListOf()

            // DSL marker hides these -- reintroduce them
            val coord: Coordinate get() = this@FieldScope.coord
            val schema: ViaductSchema get() = this@MockTenantModuleBootstrapperDSL.schema
            val fac: F get() = this@MockTenantModuleBootstrapperDSL.fac
            val queryType: GraphQLObjectType get() = this@MockTenantModuleBootstrapperDSL.queryType
            val objectType: GraphQLObjectType get() = this@FieldScope.objectType
            val fieldType: GraphQLOutputType get() = this@FieldScope.fieldType

            fun variables(
                vararg names: String,
                rss: RequiredSelectionSet? = null,
                resolveFn: VariablesResolverFn
            ) {
                variableProviders.add(MockVariablesResolver(*names, requiredSelectionSet = rss, resolveFn = resolveFn))
            }

            internal fun toRSS() = mkRSS(typeName, objectSelectionsText, variableProviders, forChecker = forChecker)
        }

        @TenantModuleBootstrapperDsl
        inner class ResolverScope {
            internal var objectSelections: SelectionsScope? = null
            internal var querySelections: SelectionsScope? = null
            internal var resolverName: String = "mock-resolver-name"
            internal var unbatchedResolveFn: FieldUnbatchedResolverFn? = null
            internal var batchResolveFn: FieldBatchResolverFn? = null
            internal var resolverId: String = coord.first + "." + coord.second

            // DSL marker hides these -- reintroduce them
            val coord: Coordinate get() = this@FieldScope.coord
            val schema: ViaductSchema get() = this@MockTenantModuleBootstrapperDSL.schema
            val fac: F get() = this@MockTenantModuleBootstrapperDSL.fac
            val queryType: GraphQLObjectType get() = this@MockTenantModuleBootstrapperDSL.queryType
            val objectType: GraphQLObjectType get() = this@FieldScope.objectType
            val fieldType: GraphQLOutputType get() = this@FieldScope.fieldType

            fun objectSelections(objectSelectionsText: String) = objectSelections(objectSelectionsText, { })

            fun objectSelections(
                objectSelectionsText: String,
                block: SelectionsScope.() -> Unit,
            ) {
                objectSelections = this@FieldScope.SelectionsScope(coord.first, objectSelectionsText, forChecker = false).apply { block() }
            }

            fun querySelections(querySelectionsText: String) = querySelections(querySelectionsText, { })

            fun querySelections(
                querySelectionsText: String,
                block: SelectionsScope.() -> Unit,
            ) {
                querySelections = this@FieldScope.SelectionsScope(queryType.name, querySelectionsText, forChecker = false).apply { block() }
            }

            fun resolverName(name: String) = apply { resolverName = name }

            fun fn(resolveFn: FieldUnbatchedResolverFn) {
                this.unbatchedResolveFn = resolveFn
            }

            fun fn(resolveFn: FieldBatchResolverFn) {
                this.batchResolveFn = resolveFn
            }
        }

        @TenantModuleBootstrapperDsl
        inner class CheckerScope {
            internal var requiredSelectionSets: MutableMap<String, RequiredSelectionSet?> = mutableMapOf()
            internal var executeFn: CheckerFn? = null

            // DSL marker hides these -- reintroduce them
            val coord: Coordinate get() = this@FieldScope.coord
            val schema: ViaductSchema get() = this@MockTenantModuleBootstrapperDSL.schema
            val fac: F get() = this@MockTenantModuleBootstrapperDSL.fac
            val queryType: GraphQLObjectType get() = this@MockTenantModuleBootstrapperDSL.queryType
            val objectType: GraphQLObjectType get() = this@FieldScope.objectType
            val fieldType: GraphQLOutputType get() = this@FieldScope.fieldType

            fun noAccess(msg: String = "Access Denied"): Nothing = throw SecurityException(msg)

            fun objectSelections(
                selectionsName: String,
                objectSelectionsText: String,
            ) = objectSelections(selectionsName, objectSelectionsText, { })

            fun objectSelections(
                selectionsName: String,
                objectSelectionsText: String,
                block: SelectionsScope.() -> Unit,
            ) = requiredSelectionSets.putIfMissingOrFail(selectionsName) {
                this@FieldScope.SelectionsScope(coord.first, objectSelectionsText, forChecker = true).apply { block() }.toRSS()
            }

            fun querySelections(
                selectionsName: String,
                querySelectionsText: String
            ) = querySelections(selectionsName, querySelectionsText, { })

            fun querySelections(
                selectionsName: String,
                querySelectionsText: String,
                block: SelectionsScope.() -> Unit,
            ) = requiredSelectionSets.putIfMissingOrFail(selectionsName) {
                this@FieldScope.SelectionsScope(queryType.name, querySelectionsText, forChecker = true).apply { block() }.toRSS()
            }

            fun fn(executeFn: CheckerFn) {
                this.executeFn = executeFn
            }
        }
    } // End of FieldScope

    @TenantModuleBootstrapperDsl
    inner class TypeScope(val typeName: String) {
        // DSL marker hides these -- reintroduce them
        val schema: ViaductSchema get() = this@MockTenantModuleBootstrapperDSL.schema
        val fac: F get() = this@MockTenantModuleBootstrapperDSL.fac
        val queryType: GraphQLObjectType get() = this@MockTenantModuleBootstrapperDSL.queryType
        val objectType: GraphQLObjectType get() = schema.schema.getObjectType(typeName)!!

        fun nodeBatchedExecutor(
            selective: Boolean = false,
            block: NodeBatchResolverFn
        ) {
            nodeResolverExecutors.putIfMissingOrFail(typeName) { MockNodeBatchResolverExecutor(typeName, selective, block) }
        }

        fun nodeUnbatchedExecutor(
            selective: Boolean = false,
            block: NodeUnbatchedResolverFn
        ) {
            nodeResolverExecutors.putIfMissingOrFail(typeName) { MockNodeUnbatchedResolverExecutor(typeName, selective, block) }
        }

        fun checker(block: CheckerScope.() -> Unit) =
            typeCheckerExecutors.putIfMissingOrFail(typeName) {
                val c = CheckerScope().apply { block() }
                val fn = c.executeFn
                MockCheckerExecutor(c.requiredSelectionSets, fn)
            }

        @TenantModuleBootstrapperDsl
        inner class SelectionsScope(private val typeName: String, val objectSelectionsText: String, val forChecker: Boolean) {
            private var variableProviders: MutableList<VariablesResolver> = mutableListOf()

            // DSL marker hides these -- reintroduce them
            val schema: ViaductSchema get() = this@MockTenantModuleBootstrapperDSL.schema
            val fac: F get() = this@MockTenantModuleBootstrapperDSL.fac
            val queryType: GraphQLObjectType get() = this@MockTenantModuleBootstrapperDSL.queryType
            val objectType: GraphQLObjectType get() = this@TypeScope.objectType

            fun variables(
                vararg names: String,
                resolveFn: VariablesResolverFn
            ) {
                variableProviders.add(MockVariablesResolver(*names, resolveFn = resolveFn))
            }

            internal fun toRSS() = mkRSS(typeName, objectSelectionsText, variableProviders, forChecker)
        }

        @TenantModuleBootstrapperDsl
        inner class CheckerScope {
            internal var requiredSelectionSets: MutableMap<String, RequiredSelectionSet?> = mutableMapOf()
            internal var executeFn: CheckerFn = { _, _ -> TODO() }

            // DSL marker hides these -- reintroduce them
            val schema: ViaductSchema get() = this@MockTenantModuleBootstrapperDSL.schema
            val fac: F get() = this@MockTenantModuleBootstrapperDSL.fac
            val queryType: GraphQLObjectType get() = this@MockTenantModuleBootstrapperDSL.queryType
            val typeName: String get() = this@TypeScope.typeName
            val objectType: GraphQLObjectType get() = this@TypeScope.objectType

            fun objectSelections(
                selectionsName: String,
                objectSelectionsText: String
            ) = objectSelections(selectionsName, objectSelectionsText, { })

            fun objectSelections(
                selectionsName: String,
                objectSelectionsText: String,
                block: SelectionsScope.() -> Unit,
            ) = requiredSelectionSets.putIfMissingOrFail(selectionsName) {
                this@TypeScope.SelectionsScope(this@TypeScope.typeName, objectSelectionsText, forChecker = true).apply { block() }.toRSS()
            }

            fun querySelections(
                selectionsName: String,
                querySelectionsText: String
            ) = querySelections(selectionsName, querySelectionsText, { })

            fun querySelections(
                selectionsName: String,
                querySelectionsText: String,
                block: SelectionsScope.() -> Unit,
            ) = requiredSelectionSets.putIfMissingOrFail(selectionsName) {
                this@TypeScope.SelectionsScope(queryType.name, querySelectionsText, forChecker = true).apply { block() }.toRSS()
            }

            fun fn(executeFn: CheckerFn) {
                this.executeFn = executeFn
            }
        }
    } // End of TypeScope
}
