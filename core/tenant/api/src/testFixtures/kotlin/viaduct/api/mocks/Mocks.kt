@file:OptIn(viaduct.apiannotations.InternalApi::class)

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

/**
 * Parses [sdl] and builds an executable [GraphQLSchema] backed by mocked wiring.
 *
 * Intended for tests that need a real schema object but do not care about runtime resolvers.
 */
fun createSchema(sdl: String): GraphQLSchema {
    val tdr = SchemaParser().parse(sdl)
    return SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
}

/**
 * Creates a [ReflectionLoader] that resolves GRT types by loading `$packageName.$name${'$'}Reflection`
 * companion objects at runtime via [Class.forName].
 *
 * Requires that generated GRT classes are on the classpath under [packageName]. The optional
 * [classLoader] defaults to the system class loader.
 */
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

/** Converts this [GraphQLSchema] to a [ViaductSchema] using the default factory. */
val GraphQLSchema.viaduct: ViaductSchema
    get() =
        ViaductSchema.fromGraphQLSchema(this)

/**
 * Test-only [Type] that associates an arbitrary [name] with a [kcls] without requiring a
 * generated `Reflection` companion object.
 */
class MockType<T : GRT>(override val name: String, override val kcls: KClass<T>) : Type<T> {
    companion object {
        fun mkNodeObject(name: String): Type<NodeObject> = MockType(name, NodeObject::class)
    }
}

/**
 * Test-only [ReflectionLoader] backed by a pre-supplied list of [Type] objects.
 *
 * Looks up types by name from the provided vararg list. Throws [UnsupportedOperationException]
 * if the requested name is not found.
 */
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

/**
 * Simpler test-only [ReflectionLoader] that exposes the provided [types] as a public property.
 *
 * Throws [NoSuchElementException] when a type name is not found, listing the available names in
 * the error message.
 */
class MockReflectionLoader(vararg val types: Type<*>) : ReflectionLoader {
    override fun reflectionFor(name: String): Type<*> = types.firstOrNull { it.name == name } ?: throw NoSuchElementException("$name not in { ${types.joinToString(",")} }")

    override fun getGRTKClassFor(name: String): KClass<*> {
        return reflectionFor(name).kcls
    }
}
