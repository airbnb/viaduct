package model.testing

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Provides
import com.google.inject.ProvisionException
import jakarta.inject.Singleton
import java.util.IdentityHashMap
import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.ObjectEngineResult
import model.SelectionForest
import model.emptyFragmentOf
import model.lowering.LOWERED_TYPENAME_FIELD
import model.lowering.ViaductAndGJSchema
import model.parsing.selectionsFrom
import model.registry.FieldCheckerResolver
import model.registry.FieldResolverDefinition
import model.registry.NodeResolverFunction
import model.registry.ResolverRegistry
import model.registry.TypeCheckerResolver
import model.registry.VariableDeclaration
import model.registry.fieldResolverOf
import model.registry.resolverRegistryOf
import model.requireQueryTypeDef
import viaduct.graphql.schema.ViaductSchema

private val testRoots = IdentityHashMap<ViaductSchema, ObjectEngineResult>()
private val fieldTestRoots =
    IdentityHashMap<ViaductSchema.Object, ObjectEngineResult>()

/** Returns one stable synthetic Query root for occurrence-identity unit tests on this schema. */
fun ViaductSchema.testRoot(): ObjectEngineResult =
    synchronized(testRoots) {
        testRoots.getOrPut(this) {
            ObjectEngineResult.of(
                type = requireQueryTypeDef(),
                values = emptyMap(),
            )
        }
    }

/** Returns one stable synthetic occurrence root for isolated tests involving this field. */
fun ViaductSchema.ObjectField.testRoot(): ObjectEngineResult =
    synchronized(fieldTestRoots) {
        fieldTestRoots.getOrPut(containingDef) {
            ObjectEngineResult.of(
                type = containingDef,
                values = emptyMap(),
            )
        }
    }

/**
 * One Guice-assembled reasoning world for model and semantics tests.
 */
class TestWorld private constructor(
    private val injector: Injector,
) {
    val schemas: ViaductAndGJSchema = injector.getInstance(ViaductAndGJSchema::class.java)
    val schema: ViaductSchema = schemas.loweredSchema
    val resolverRegistry: ResolverRegistry =
        injector.getInstance(ResolverRegistry::class.java)
    val assumptions: Assumptions =
        injector.getInstance(Assumptions::class.java)

    /** Creates another immutable world configuration over this world's schema and registry. */
    fun newAssumptions(selectiveResolvers: Boolean = assumptions.selectiveResolvers): Assumptions =
        Assumptions.of(
            schema = schema,
            resolverRegistry = resolverRegistry,
            selectiveResolvers = selectiveResolvers,
        )

    fun <T : Any> instance(type: Class<T>): T = injector.getInstance(type)

    fun selectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> = schemas.selectionsFrom(fragment)

    companion object {
        /**
         * Composes ordinary GraphQL and raw resolver inputs into one canonical reasoning world.
         *
         * GraphQL SDL and fragments remain external source text. Raw [nodeResolvers] are installed
         * behind the built-in `Query.node`, and node-valued source output is normalized into root
         * references to that field before [Assumptions] is constructed. Missing Query field
         * resolvers are filled with nullability-aware fallback producers before supplied field
         * resolvers are overlaid.
         */
        fun fromSDL(
            schemaSDL: String,
            nodeResolvers: (ViaductAndGJSchema) -> Map<ViaductSchema.Object, NodeResolverFunction> = { emptyMap() },
            fieldResolvers: ((ViaductAndGJSchema) -> Map<ViaductSchema.Field, FieldResolverDefinition>)? = null,
            fieldCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.ObjectField, FieldCheckerResolver> = { emptyMap() },
            typeCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.Object, TypeCheckerResolver> = { emptyMap() },
            variableProviders: (ViaductAndGJSchema) -> Map<Arguments.Variable, VariableDeclaration> = { emptyMap() },
            selectiveResolvers: Boolean = true,
        ): TestWorld =
            create(
                schemaSDL = schemaSDL,
                nodeResolvers = nodeResolvers,
                fieldResolvers = fieldResolvers,
                fieldCheckers = fieldCheckers,
                typeCheckers = typeCheckers,
                variableProviders = variableProviders,
                selectiveResolvers = selectiveResolvers,
            )

        private fun create(
            schemaSDL: String,
            nodeResolvers: (ViaductAndGJSchema) -> Map<ViaductSchema.Object, NodeResolverFunction>,
            fieldResolvers: ((ViaductAndGJSchema) -> Map<ViaductSchema.Field, FieldResolverDefinition>)?,
            fieldCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.ObjectField, FieldCheckerResolver>,
            typeCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.Object, TypeCheckerResolver>,
            variableProviders: (ViaductAndGJSchema) -> Map<Arguments.Variable, VariableDeclaration>,
            selectiveResolvers: Boolean,
        ): TestWorld {
            val injector =
                Guice.createInjector(
                    TestWorldModule(
                        schemaSDL = schemaSDL,
                        nodeResolvers = nodeResolvers,
                        fieldResolvers = fieldResolvers,
                        fieldCheckers = fieldCheckers,
                        typeCheckers = typeCheckers,
                        variableProviders = variableProviders,
                        selectiveResolvers = selectiveResolvers,
                    ),
                )
            return try {
                TestWorld(injector)
            } catch (exception: ProvisionException) {
                val cause = exception.cause
                if (cause is RuntimeException) throw cause
                throw exception
            }
        }

        /**
         * Composes one schema-embedded deterministic resolver world.
         *
         * Resolver-test directives are compiled into the ordinary fixture registry and stripped
         * before the retained source schema is decoded. For readability, present [schemaSDL]
         * top-down: start with `extend type Query`, then define the types reached from those root
         * fields, followed by the types they reach.
         */
        fun fromDSL(
            schemaSDL: String,
            selectiveResolvers: Boolean = true,
            fieldCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.ObjectField, FieldCheckerResolver> = { emptyMap() },
            typeCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.Object, TypeCheckerResolver> = { emptyMap() },
        ): TestWorld {
            val dsl = ResolverTestDsl.parse(schemaSDL)
            return create(
                schemaSDL = dsl.schemaSDL,
                nodeResolvers = dsl::nodeResolvers,
                fieldResolvers = dsl::fieldResolvers,
                fieldCheckers = fieldCheckers,
                typeCheckers = typeCheckers,
                variableProviders = dsl::variableProviders,
                selectiveResolvers = selectiveResolvers,
            )
        }
    }
}

@JvmSuppressWildcards
private class TestWorldModule(
    private val schemaSDL: String,
    private val nodeResolvers: (ViaductAndGJSchema) -> Map<ViaductSchema.Object, NodeResolverFunction>,
    private val fieldResolvers: ((ViaductAndGJSchema) -> Map<ViaductSchema.Field, FieldResolverDefinition>)?,
    private val fieldCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.ObjectField, FieldCheckerResolver>,
    private val typeCheckers: (ViaductAndGJSchema) -> Map<ViaductSchema.Object, TypeCheckerResolver>,
    private val variableProviders: (ViaductAndGJSchema) -> Map<Arguments.Variable, VariableDeclaration>,
    private val selectiveResolvers: Boolean,
) : AbstractModule() {
    override fun configure() {
        bind(String::class.java)
            .annotatedWith(SchemaSDL::class.java)
            .toInstance(schemaSDL)
    }

    @Provides
    @Singleton
    fun schema(
        @SchemaSDL schemaSDL: String,
    ): ViaductAndGJSchema = ViaductAndGJSchema.fromSDL(schemaSDL)

    @Provides
    @NodeResolvers
    fun nodeResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Object, NodeResolverFunction> = nodeResolvers.invoke(schema)

    @Provides
    @FieldResolvers
    fun fieldResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> = fallbackQueryResolvers(schema) + fieldResolvers?.invoke(schema).orEmpty()

    @Provides
    @VariableProviders
    fun variableProviders(schema: ViaductAndGJSchema): Map<Arguments.Variable, VariableDeclaration> = variableProviders.invoke(schema)

    @Provides
    @FieldCheckers
    fun fieldCheckers(schema: ViaductAndGJSchema): Map<ViaductSchema.ObjectField, FieldCheckerResolver> = fieldCheckers.invoke(schema)

    @Provides
    @TypeCheckers
    fun typeCheckers(schema: ViaductAndGJSchema): Map<ViaductSchema.Object, TypeCheckerResolver> = typeCheckers.invoke(schema)

    @Provides
    @Singleton
    fun resolverRegistry(
        schema: ViaductAndGJSchema,
        @NodeResolvers nodeResolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
        @FieldResolvers fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
        @FieldCheckers fieldCheckers: Map<ViaductSchema.ObjectField, FieldCheckerResolver>,
        @TypeCheckers typeCheckers: Map<ViaductSchema.Object, TypeCheckerResolver>,
        @VariableProviders
        variableProviders: Map<Arguments.Variable, VariableDeclaration>,
    ): ResolverRegistry =
        resolverRegistryOf(
            schema = schema,
            nodeResolvers = nodeResolvers,
            fieldResolvers = fieldResolvers,
            fieldCheckers = fieldCheckers,
            typeCheckers = typeCheckers,
            variableProviders = variableProviders,
        )

    @Provides
    @Singleton
    fun assumptions(
        schemas: ViaductAndGJSchema,
        resolverRegistry: ResolverRegistry,
    ): Assumptions =
        Assumptions.of(
            schema = schemas.loweredSchema,
            resolverRegistry = resolverRegistry,
            selectiveResolvers = selectiveResolvers,
        )

    private fun fallbackQueryResolvers(schemas: ViaductAndGJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> {
        val schema = schemas.loweredSchema
        val queryFragment = schema.emptyFragmentOf("Query")
        return schema.requireQueryTypeDef().fields
            .filter {
                it.name != LOWERED_TYPENAME_FIELD
            }
            .associateWith { field ->
                fieldResolverOf(
                    objectFragment = queryFragment,
                    function = { _, _ ->
                        if (field.type.isNullable) null else EngineErrorData.of()
                    },
                )
            }
    }
}
