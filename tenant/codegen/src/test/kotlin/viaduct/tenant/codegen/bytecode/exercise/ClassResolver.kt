package viaduct.tenant.codegen.bytecode.exercise

import kotlin.reflect.KClass
import viaduct.api.internal.MissingReflection
import viaduct.api.reflect.Type
import viaduct.codegen.utils.JavaName
import viaduct.tenant.codegen.bytecode.config.cfg.argumentTypeName
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl

/** ClassResolver mediates loading classes related to GraphQL types */
interface ClassResolver {
    /**
     * Resolve the "Value" class of a provided GraphQL type.
     * Only valid for types that support Value classes.
     *
     * For this class hierarchy:
     * ```kotlin
     *   interface Foo {
     *     class Value {
     *       class Builder
     *     }
     *   }
     * ```
     * Calling `valueClassFor("Foo")` will return Foo.Value
     */
    fun valueClassFor(name: String): Class<*>

    /**
     * Resolve the "Builder" class of a provided GraphQL type.
     * Only valid for types that support Builder classes
     *
     * For this class hierarchy:
     * ```kotlin
     *   interface Foo {
     *     class Value {
     *       class Builder
     *     }
     *   }
     * ```
     * Calling `builderClassFor("Foo") will return Foo.Value.Builder
     */
    fun builderClassFor(name: String): Class<*>

    /**
     * Resolve the v2 "Builder" class of a provided GraphQL type.
     * Valid for both v2 input and object GRTs Builder classes
     *
     * For this class hierarchy:
     * ```kotlin
     *   class Foo {
     *     class Builder
     *   }
     * ```
     * Calling `v2BuilderClassFor("Foo") will return Foo.Builder
     */
    fun v2BuilderClassFor(name: String): Class<*>

    /**
     * Resolve the "Main" class of a provided GraphQL type
     * Valid for both input and output types.
     *
     * For these classes:
     * ```kotlin
     *   interface Foo {
     *     class Value { ... }
     *   }
     *   data class Bar(...)
     * ```
     *
     * `mainClassFor` can be called with either "Foo" or "Bar" to
     * resolve the Foo or Bar classes
     */
    fun mainClassFor(name: String): Class<*>

    /**
     * Resolve the "Arguments" class of a provided GraphQL coordinate.
     *
     * For example, for this type:
     * ```graphql
     *   type Foo { field(i: Int) }
     * ```
     *
     * Calling `argumentClassFor("Foo", "field")` will return a
     * class Foo_Field_Arguments
     */
    fun argumentsClassFor(
        type: String,
        field: String
    ): Class<*>

    /**
     * Return a Type describing the reflected type information for the type with the provided name.
     * If no such Type information exists, a [viaduct.api.ViaductTenantException] exception will be thrown.
     */
    fun reflectionFor(type: String): Type<*>

    companion object {
        fun fromSystemClassLoader(pkg: JavaName): ClassResolver = fromClassLoader(pkg, ClassLoader.getSystemClassLoader())

        fun fromClassLoader(
            pkg: JavaName,
            loader: ClassLoader
        ): ClassResolver =
            object : ClassResolver {
                override fun valueClassFor(name: String) = Class.forName("$pkg.${name}\$Value", true, loader)

                override fun mainClassFor(name: String) = Class.forName("$pkg.$name", true, loader)

                override fun builderClassFor(name: String) = Class.forName("$pkg.${name}\$Value\$Builder", true, loader)

                override fun v2BuilderClassFor(name: String) = Class.forName("$pkg.${name}\$Builder", true, loader)

                override fun argumentsClassFor(
                    type: String,
                    field: String
                ) = Class.forName("$pkg.${argumentTypeName(type, field)}", true, loader)

                override fun reflectionFor(type: String): Type<*> = ReflectionLoaderImpl { name -> Class.forName("$pkg.$name", true, loader).kotlin }.reflectionFor(type)
            }

        /**
         * Create a [ClassResolver] from static maps. This is primarily intended to support testing.
         */
        fun fromMaps(
            valueClasses: Map<String, KClass<*>> = emptyMap(),
            argumentClasses: Map<Pair<String, String>, KClass<*>> = emptyMap(),
            dataClasses: Map<String, KClass<*>> = emptyMap(),
            builderClasses: Map<String, KClass<*>> = emptyMap(),
            reflections: Map<String, Type<*>> = emptyMap()
        ): ClassResolver =
            object : ClassResolver {
                private fun resolve(
                    label: String,
                    kcls: KClass<*>?
                ): Class<*> = kcls?.java ?: throw ClassNotFoundException("class for $label")

                private fun <T> resolve(
                    type: String,
                    key: T,
                    map: Map<T, KClass<*>>
                ): Class<*> = resolve("$type:$key", map[key])

                override fun valueClassFor(name: String) = resolve("Value", name, valueClasses)

                override fun mainClassFor(name: String) = resolve("Main", name, dataClasses)

                override fun builderClassFor(name: String) = resolve("Builder", name, builderClasses)

                override fun v2BuilderClassFor(name: String) = resolve("V2Builder", name, builderClasses)

                override fun argumentsClassFor(
                    type: String,
                    field: String
                ) = resolve("Arguments", type to field, argumentClasses)

                override fun reflectionFor(type: String): Type<*> = reflections[type] ?: throw MissingReflection(type, "reflection for $type")
            }
    }
}
