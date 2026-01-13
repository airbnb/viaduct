package viaduct.api.mocks

import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.reflect.KClass
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.api.types.Mutation
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

fun mkSchema(sdl: String): GraphQLSchema {
    val tdr = SchemaParser().parse(sdl)
    return SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
}

@Suppress("NO_REFLECTION_IN_CLASS_PATH")
fun mockReflectionLoader(
    packageName: String,
    classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
) = object : ReflectionLoader {
    override fun reflectionFor(name: String): Type<*> {
        return Class.forName("$packageName.$name\$Reflection", true, classLoader).kotlin.objectInstance as Type<*>
    }

    override fun getGRTKClassFor(name: String): KClass<*> {
        return Class.forName("$packageName.$name", true, classLoader).kotlin
    }
}

val GraphQLSchema.viaduct: ViaductSchema
    get() =
        GJSchema.fromSchema(this)

class MockType<T : GRT>(override val name: String, override val kcls: KClass<T>) : Type<T> {
    companion object {
        fun mkNodeObject(name: String): Type<NodeObject> = MockType(name, NodeObject::class)
    }
}

class MockReflectionLoaderImpl(vararg types: Type<*>) : ReflectionLoader {
    private val map: Map<String, Type<*>> = types.associateBy { it.name }

    override fun reflectionFor(name: String): Type<*> {
        return map[name] ?: throw UnsupportedOperationException("Deserialization not supported in tests")
    }

    override fun getGRTKClassFor(name: String): KClass<*> {
        return reflectionFor(name).kcls
    }
}

/**
 * Extension function to create a serialized GlobalID string for testing.
 *
 * This is a convenience method that combines GlobalID creation and serialization
 * in a single call, which is a common pattern in tests.
 *
 * @param internalId The internal ID string
 * @return A Base64-encoded GlobalID string
 */
fun <T : NodeCompositeOutput> Type<T>.testGlobalId(internalId: String): String = GlobalIDCodecDefault.serialize(this.name, internalId)

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
    override fun reflectionFor(name: String): Type<*> = types.firstOrNull { it.name == name } ?: throw NoSuchElementException("$name not in { ${types.joinToString(",")} }")

    override fun getGRTKClassFor(name: String): KClass<*> {
        return reflectionFor(name).kcls
    }
}
