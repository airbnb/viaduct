package viaduct.tenant.codegen.bytecode.exercise

import com.google.common.io.Resources
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import viaduct.api.internal.ReflectionLoader
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.schema.graphqljava.readTypesFromURLs
import viaduct.invariants.InvariantChecker
import viaduct.schema.base.ValueBase

internal fun InvariantChecker.tryResolveClass(
    msg: String,
    classResolver: ClassResolver,
    fn: ClassResolver.() -> Class<*>
): Class<*>? {
    var c: Class<*>? = null
    this.doesNotThrow(msg) {
        classResolver.apply { c = this.fn() }
    }
    return c
}

internal fun InvariantChecker.hasMethod(
    msg: String,
    actualClass: Class<*>,
    methodFilter: (Method) -> Boolean
): Method? {
    return try {
        actualClass.declaredMethods.first(methodFilter)
    } catch (e: Exception) {
        addExceptionFailure(e, msg)
        null
    }
}

internal fun InvariantChecker.areInstancesOf(
    msg: String,
    expectedClasses: Array<Class<*>>,
    vararg actuals: Any?
): Boolean {
    if (!isEqualTo(
            expectedClasses.size,
            actuals.size,
            "{0}: expected ({1}) and actual ({2}) must match",
            arrayOf(msg, expectedClasses.size.toString(), actuals.size.toString())
        )
    ) {
        return false
    }
    var result = true
    for (i in 0 until expectedClasses.size) {
        withContext("arg$i") {
            result = isInstanceOf(expectedClasses[i].kotlin, actuals[i], msg) && result
        }
    }
    return result
}

/**
 * Check that the `outer` class nests a class with the provided name.
 * The provided `fn` will be run on the nested class if it exists
 */
internal fun InvariantChecker.withNestedClass(
    outer: KClass<*>,
    nestedSimpleName: String,
    checkLabel: String,
    fn: (KClass<*>) -> Unit = {}
) {
    val nested = outer.nestedClasses.firstOrNull { it.simpleName == nestedSimpleName }
    if (isNotNull(nested, checkLabel)) {
        fn(nested!!)
    }
}

/**
 * Check that the given class has an object instance.
 * The provided `fn` will be run on the object instance if it exists
 */
internal fun <T : Any> InvariantChecker.withObjectInstance(
    cls: KClass<T>,
    checkLabel: String,
    fn: (T) -> Unit = {}
) {
    val obj = cls.objectInstance
    if (isNotNull(obj, checkLabel)) {
        fn(obj!!)
    }
}

/**
 * Check that the provided instance has a property with the provided name.
 * The provided `fn` will be run on the property if it exists
 */
internal inline fun <reified T> InvariantChecker.withProperty(
    instance: Any,
    propName: String,
    checkLabel: String,
    fn: (T) -> Unit = {}
) {
    val cls = instance::class
    val prop = cls.memberProperties.firstOrNull { it.name == propName }
    if (isNotNull(prop, checkLabel)) {
        val value = prop!!.getter.call(instance)
        if (isInstanceOf(T::class, value, checkLabel)) {
            fn(value as T)
        }
    }
}

internal fun ValueBase.toMap(): Map<String, Any?> = allFieldNames.associateWith(::getField).toMap()

fun loadGraphQLSchemaAsGraphQLSchema(schemaResourcePath: String? = null): ViaductSchema {
    val paths = if (schemaResourcePath != null) {
        // Load from specific resource path
        listOf(Resources.getResource(schemaResourcePath))
    } else {
        // scan all the graphqls files in the classloader and load them as the schema
        val reflections = Reflections(
            ConfigurationBuilder()
                .setUrls(
                    ClasspathHelper.forPackage(
                        "graphql",
                        ClasspathHelper.contextClassLoader(),
                        ClasspathHelper.staticClassLoader()
                    )
                )
                .addScanners(Scanners.Resources)
        )
        val graphqlsResources = Scanners.Resources.with(".*\\.graphqls")
        reflections.get(graphqlsResources).map { Resources.getResource(it) }
    }
    if (paths.isEmpty()) throw IllegalStateException("Could not find any graphqls files in the classpath")

    return ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema((readTypesFromURLs(paths))))
}

fun reflectionLoaderForClassResolver(classResolver: ClassResolver): ReflectionLoader =
    object : ReflectionLoader {
        override fun reflectionFor(name: String) = classResolver.reflectionFor(name)

        override fun getGRTKClassFor(name: String) = classResolver.mainClassFor(name).kotlin
    }
