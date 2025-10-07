package viaduct.api.mocks

import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.reflect.KClass
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.api.types.Mutation
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.graphql.schema.graphqljava.GJSchema

/**
 * Re-project this InternalContext back to an [ExecutionContext].
 * If this InternalContext was originally extracted from an ExecutionContext,
 * then the original ExecutionContext will be returned. Otherwise, a minimal
 * ExecutionContext will be returned.
 */
val InternalContext.executionContext: ExecutionContext
    get() =
        this as? ExecutionContext ?: MockExecutionContext(this)

/**
 * Re-project this InternalContext back to an [ResolverExecutionContext].
 * If this InternalContext was originally extracted from an ExecutionContext,
 * then the original ExecutionContext will be returned. Otherwise, a minimal
 * ExecutionContext will be returned.
 */
val InternalContext.resolverExecutionContext: ResolverExecutionContext
    get() =
        this as? ResolverExecutionContext ?: MockResolverExecutionContext(this)

fun mkSchema(sdl: String): GraphQLSchema {
    val tdr = SchemaParser().parse(sdl)
    return SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
}

@Suppress("NO_REFLECTION_IN_CLASS_PATH")
fun mockReflectionLoader(packageName: String) =
    object : ReflectionLoader {
        override fun reflectionFor(name: String): Type<*> {
            return Class.forName("$packageName.$name\$Reflection").kotlin.objectInstance as Type<*>
        }
    }

val GraphQLSchema.viaduct: ViaductExtendedSchema
    get() =
        GJSchema.fromSchema(this)

class MockInternalContext(
    override val schema: ViaductSchema,
    override val globalIDCodec: GlobalIDCodec = MockGlobalIDCodec(),
    override val reflectionLoader: ReflectionLoader = mockReflectionLoader("viaduct.api.grts")
) : InternalContext {
    companion object {
        fun mk(
            schema: ViaductSchema,
            grtPackage: String = "viaduct.api.grts"
        ): MockInternalContext = MockInternalContext(schema, MockGlobalIDCodec(), mockReflectionLoader(grtPackage))
    }
}

open class MockExecutionContext(
    internalContext: InternalContext,
    override val requestContext: Any? = null
) : ExecutionContext, InternalContext by internalContext {
    override fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ) = throw UnsupportedOperationException()

    companion object {
        fun mk(schema: ViaductSchema = MockSchema.minimal): MockResolverExecutionContext = MockResolverExecutionContext(MockInternalContext.mk(schema))
    }
}

class MockResolverExecutionContext(internalContext: InternalContext) : MockExecutionContext(internalContext), ResolverExecutionContext {
    override fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> = throw UnsupportedOperationException()

    override suspend fun <T : Query> query(selections: SelectionSet<T>): T = throw UnsupportedOperationException()

    override fun <T : NodeObject> nodeFor(id: GlobalID<T>): T = throw UnsupportedOperationException()

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String
    ): String = throw UnsupportedOperationException()

    companion object {
        fun mk(schema: ViaductSchema = MockSchema.minimal): MockResolverExecutionContext = MockResolverExecutionContext(MockInternalContext.mk(schema))
    }
}

@Suppress("UNCHECKED_CAST")
class MockGlobalIDCodec : GlobalIDCodec {
    override fun <T : NodeCompositeOutput> serialize(id: GlobalID<T>): String = "${id.type.name}:${id.internalID}"

    override fun <T : NodeCompositeOutput> deserialize(str: String): GlobalID<T> =
        str.split(":", limit = 2).let { (typeName, internalId) ->
            MockGlobalID(
                MockType(typeName, NodeObject::class),
                internalId
            ) as GlobalID<T>
        }
}

class MockGlobalID<T : NodeObject>(
    override val type: Type<T>,
    override val internalID: String
) : GlobalID<T> {
    override fun toString(): String = "${type.name}:$internalID"

    override fun equals(other: Any?): Boolean = other is GlobalID<*> && type.name == other.type.name && internalID == other.internalID

    override fun hashCode(): Int = toString().hashCode()
}

class MockType<T : GRT>(override val name: String, override val kcls: KClass<T>) : Type<T> {
    companion object {
        fun mkNodeObject(name: String): Type<NodeObject> = MockType(name, NodeObject::class)
    }
}

@Suppress("UNCHECKED_CAST")
data class MockSelectionsLoader<T : CompositeOutput>(val t: T) : SelectionsLoader<T> {
    class Factory(
        val query: Query,
        val mutation: Mutation?
    ) : SelectionsLoader.Factory {
        override fun forQuery(resolverId: String): SelectionsLoader<Query> = MockSelectionsLoader(query)

        override fun forMutation(resolverId: String): SelectionsLoader<Mutation> = MockSelectionsLoader(mutation!!)
    }

    override suspend fun <U : T> load(
        ctx: ExecutionContext,
        selections: SelectionSet<U>
    ): U = t as U
}

class MockReflectionLoader(vararg val types: Type<*>) : ReflectionLoader {
    override fun reflectionFor(name: String): Type<*> = types.first { it.name == name }
}

@Suppress("UNCHECKED_CAST")
class MockSelectionSetFactory(val selectionSet: SelectionSet<*> = SelectionSet.NoSelections) : SelectionSetFactory {
    override fun <T : CompositeOutput> selectionsOn(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> = selectionSet as SelectionSet<T>
}
