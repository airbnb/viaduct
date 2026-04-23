package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RegistryExtractorExtensionsTest {
    @Test
    fun `toResolverParams returns node params for node resolver`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertTrue(result is ResolverParams.Node)
        assertEquals("com.example.feature.resolvers.ExampleNodeResolver", result.implFqn)
        assertEquals("ExampleNode", result.typeName)
        assertEquals("com.example.feature.resolverbases.NodeResolvers.ExampleNode", result.resolverBaseClass)
        assertEquals("ExampleNodeResolver", result.attribution)
        assertEquals(false, result.isBatching)
        assertEquals(false, result.isSelective)
        assertTrue(logger.warns.isEmpty(), logger.warns.joinToString("\n"))
        assertTrue(logger.errors.isEmpty(), logger.errors.joinToString("\n"))
    }

    @Test
    fun `toResolverParams returns null for field resolver in node only pass`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf(
                        "typeName" to "ExampleNode",
                        "fieldName" to "name",
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
    }

    @Test
    fun `toResolverParams returns null when direct resolver base is not annotated`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.other.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.other",
            annotations = emptyList(),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.infos.any {
                it.contains("no direct supertype is annotated with @NodeResolverFor or @ResolverFor")
            },
            logger.infos.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs error for local class`() {
        val logger = RecordingKspLogger()

        // A local class has a non-KSClassDeclaration parent declaration (e.g., a function)
        val localParent = ksNonClassDeclaration(simpleName = "someFunction")
        val localDeclaration = ksLocalClassDeclaration(
            simpleName = "LocalResolver",
            parentDeclaration = localParent,
        )

        val result = localDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.errors.any { it.contains("not supported on local classes") },
            logger.errors.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs error when qualified name is null`() {
        val logger = RecordingKspLogger()

        val resolverDeclaration = ksClassDeclarationWithNullQualifiedName(
            simpleName = "AnonymousResolver",
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.errors.any { it.contains("must have a qualified name") },
            logger.errors.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null when direct supertype declaration is not a class`() {
        val logger = RecordingKspLogger()

        // A supertype whose declaration is not a KSClassDeclaration (e.g. a type alias)
        // is filtered out silently; if no annotated class supertype remains, an info is emitted.
        val nonClassDeclaration = ksNonClassDeclaration(simpleName = "SomeTypeAlias")
        val resolverDeclaration = ksClassDeclarationWithNonClassSupertype(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            supertypeDeclaration = nonClassDeclaration,
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.infos.any { it.contains("no direct supertype is annotated with") },
            logger.infos.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null when class has no supertypes`() {
        val logger = RecordingKspLogger()

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = emptyList(),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.infos.any { it.contains("no direct supertypes") },
            logger.infos.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs warn when isBatching is missing from NodeResolverFor`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.warns.any { it.contains("isBatching") },
            logger.warns.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs warn when isSelective is missing from NodeResolverFor`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.warns.any { it.contains("isSelective") },
            logger.warns.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs warn when base class has no qualified name`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksNodeBaseDeclarationWithNullQualifiedName(
            simpleName = "ExampleNode",
            typeName = "ExampleNode",
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.warns.any { it.contains("base class has no qualified name") },
            logger.warns.joinToString("\n"),
        )
    }
}

private fun ksClassDeclaration(
    qualifiedName: String,
    simpleName: String,
    packageName: String,
    annotations: List<KSAnnotation> = emptyList(),
    superDeclarations: List<KSClassDeclaration> = emptyList(),
    declarations: List<KSDeclaration> = emptyList(),
): KSClassDeclaration {
    val qualifiedNameValue = ksName(qualifiedName)
    val simpleNameValue = ksName(simpleName)
    val packageNameValue = ksName(packageName)
    val superTypes = superDeclarations.map { ksTypeReference(it) }

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getQualifiedName" -> qualifiedNameValue
            "getSimpleName" -> simpleNameValue
            "getPackageName" -> packageNameValue
            "getAnnotations" -> annotations.asSequence()
            "getSuperTypes" -> superTypes.asSequence()
            "getDeclarations" -> declarations.asSequence()
            "getContainingFile" -> null
            "getParentDeclaration" -> null
            "toString" -> qualifiedName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

private fun ksAnnotation(
    simpleName: String,
    args: Map<String, Any?>,
): KSAnnotation {
    val shortName = ksName(simpleName)
    val arguments = args.map { (name, value) -> ksValueArgument(name, value) }

    return proxy(KSAnnotation::class.java) { method, _ ->
        when (method.name) {
            "getShortName" -> shortName
            "getArguments" -> arguments
            "toString" -> "@$simpleName"
            else -> unsupported("KSAnnotation.${method.name}")
        }
    }
}

private fun ksValueArgument(
    name: String,
    value: Any?,
): KSValueArgument {
    val argumentName = ksName(name)

    return proxy(KSValueArgument::class.java) { method, _ ->
        when (method.name) {
            "getName" -> argumentName
            "getValue" -> value
            "toString" -> "$name=$value"
            else -> unsupported("KSValueArgument.${method.name}")
        }
    }
}

private fun ksTypeReference(declaration: KSClassDeclaration): KSTypeReference {
    val type = proxy(KSType::class.java) { method, _ ->
        when (method.name) {
            "getDeclaration" -> declaration
            "toString" -> declaration.toString()
            else -> unsupported("KSType.${method.name}")
        }
    }

    return proxy(KSTypeReference::class.java) { method, _ ->
        when (method.name) {
            "resolve" -> type
            "toString" -> declaration.toString()
            else -> unsupported("KSTypeReference.${method.name}")
        }
    }
}

private fun ksName(value: String): KSName {
    return proxy(KSName::class.java) { method, _ ->
        when (method.name) {
            "asString" -> value
            "getShortName" -> value.substringAfterLast('.', value)
            "getQualifier" -> value.substringBeforeLast('.', "")
            "toString" -> value
            else -> unsupported("KSName.${method.name}")
        }
    }
}

/** Creates a KSClassDeclaration whose parentDeclaration is a non-KSClassDeclaration (making it local). */
private fun ksLocalClassDeclaration(
    simpleName: String,
    parentDeclaration: KSDeclaration,
): KSClassDeclaration {
    val simpleNameValue = ksName(simpleName)

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getParentDeclaration" -> parentDeclaration
            "toString" -> simpleName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

/** Creates a KSClassDeclaration with null qualifiedName (and non-local, so isLocal() = false). */
private fun ksClassDeclarationWithNullQualifiedName(simpleName: String,): KSClassDeclaration {
    val simpleNameValue = ksName(simpleName)

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getQualifiedName" -> null
            "getParentDeclaration" -> null
            "toString" -> simpleName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

/** Creates a non-KSClassDeclaration (e.g. a function) to act as parent or supertype declaration. */
private fun ksNonClassDeclaration(simpleName: String): KSDeclaration {
    val simpleNameValue = ksName(simpleName)

    return proxy(KSFunctionDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getParentDeclaration" -> null
            "toString" -> simpleName
            else -> unsupported("KSFunctionDeclaration.${method.name}")
        }
    }
}

/** Creates a KSClassDeclaration whose first supertype has a non-KSClassDeclaration declaration. */
private fun ksClassDeclarationWithNonClassSupertype(
    qualifiedName: String,
    simpleName: String,
    packageName: String,
    supertypeDeclaration: KSDeclaration,
): KSClassDeclaration {
    val qualifiedNameValue = ksName(qualifiedName)
    val simpleNameValue = ksName(simpleName)
    val packageNameValue = ksName(packageName)

    val type = proxy(KSType::class.java) { method, _ ->
        when (method.name) {
            "getDeclaration" -> supertypeDeclaration
            "toString" -> supertypeDeclaration.toString()
            else -> unsupported("KSType.${method.name}")
        }
    }
    val typeRef = proxy(KSTypeReference::class.java) { method, _ ->
        when (method.name) {
            "resolve" -> type
            "toString" -> supertypeDeclaration.toString()
            else -> unsupported("KSTypeReference.${method.name}")
        }
    }

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getQualifiedName" -> qualifiedNameValue
            "getSimpleName" -> simpleNameValue
            "getPackageName" -> packageNameValue
            "getAnnotations" -> emptySequence<KSAnnotation>()
            "getSuperTypes" -> sequenceOf(typeRef)
            "getDeclarations" -> emptySequence<KSDeclaration>()
            "getContainingFile" -> null
            "getParentDeclaration" -> null
            "toString" -> qualifiedName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

/** Creates a node resolver base class (annotated with @NodeResolverFor) with null qualifiedName. */
private fun ksNodeBaseDeclarationWithNullQualifiedName(
    simpleName: String,
    typeName: String,
): KSClassDeclaration {
    val simpleNameValue = ksName(simpleName)
    val annotation = ksAnnotation(
        simpleName = "NodeResolverFor",
        args = mapOf("typeName" to typeName, "isBatching" to false, "isSelective" to false),
    )

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getQualifiedName" -> null
            "getAnnotations" -> sequenceOf(annotation)
            "getParentDeclaration" -> null
            "toString" -> simpleName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

private fun unsupported(name: String): Nothing {
    throw UnsupportedOperationException("Unexpected proxy call: $name")
}

private fun <T> proxy(
    type: Class<T>,
    handler: (method: java.lang.reflect.Method, args: Array<out Any?>?) -> Any?,
): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, args ->
        handler(method, args)
    } as T
}
