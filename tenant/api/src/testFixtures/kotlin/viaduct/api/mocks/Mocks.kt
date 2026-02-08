package viaduct.api.mocks

import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.reflect.KClass
import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.types.GRT
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
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
        ViaductSchema.fromGraphQLSchema(this)

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

class MockReflectionLoader(vararg val types: Type<*>) : ReflectionLoader {
    override fun reflectionFor(name: String): Type<*> = types.firstOrNull { it.name == name } ?: throw NoSuchElementException("$name not in { ${types.joinToString(",")} }")

    override fun getGRTKClassFor(name: String): KClass<*> {
        return reflectionFor(name).kcls
    }
}
