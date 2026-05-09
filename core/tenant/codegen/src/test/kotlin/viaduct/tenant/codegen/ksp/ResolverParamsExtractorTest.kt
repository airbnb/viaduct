package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.resolver.Resolver as ResolverAnnotation
import viaduct.service.api.spi.TenantBootstrapper

class ResolverParamsExtractorTest {
    @Test
    fun `extractByFile groups node resolvers by file and sorts them`() {
        val logger = RecordingKspLogger()

        val file = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "ExampleResolvers.kt",
        )

        val exampleNodeBase = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            containingFile = null,
            declarations = emptyList(),
        )

        val accountBase = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.Account",
            simpleName = "Account",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "Account", "isBatching" to false, "isSelective" to false),
                ),
            ),
            containingFile = null,
            declarations = emptyList(),
        )

        val resolverAnnotation = listOf(
            ksAnnotation(
                simpleName = "Resolver",
                args = mapOf(
                    "objectValueFragment" to "",
                    "queryValueFragment" to "",
                    "variables" to emptyList<Any>(),
                ),
            ),
        )

        val exampleResolverB = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ZExampleNodeResolver",
            simpleName = "ZExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            annotations = resolverAnnotation,
            superDeclarations = listOf(exampleNodeBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val exampleResolverA = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.AExampleNodeResolver",
            simpleName = "AExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            annotations = resolverAnnotation,
            superDeclarations = listOf(exampleNodeBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val accountResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.AccountResolver",
            simpleName = "AccountResolver",
            packageName = "com.example.feature.resolvers",
            annotations = resolverAnnotation,
            superDeclarations = listOf(accountBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val resolver = ksResolver(
            files = listOf(
                ksFile(
                    packageName = "com.example.feature.resolvers",
                    fileName = "ExampleResolvers.kt",
                    declarations = listOf(
                        exampleResolverB,
                        exampleResolverA,
                        accountResolver,
                    ),
                ),
            ),
            resolverAnnotated = listOf(exampleResolverB, exampleResolverA, accountResolver),
        )

        val result = ResolverParamsExtractor(
            resolver = resolver,
            logger = logger,
        ).extractByFile()

        assertEquals(1, result.size)

        val descriptor = result.values.single()
        assertTrue(descriptor.fields.isEmpty())
        assertEquals(3, descriptor.nodes.size)

        assertEquals("Account", descriptor.nodes[0].typeName)
        assertEquals(
            "com.example.feature.resolvers.AccountResolver",
            descriptor.nodes[0].implFqn,
        )

        assertEquals("ExampleNode", descriptor.nodes[1].typeName)
        assertEquals(
            "com.example.feature.resolvers.AExampleNodeResolver",
            descriptor.nodes[1].implFqn,
        )

        assertEquals("ExampleNode", descriptor.nodes[2].typeName)
        assertEquals(
            "com.example.feature.resolvers.ZExampleNodeResolver",
            descriptor.nodes[2].implFqn,
        )
    }

    @Test
    fun `extractByFile extracts field resolvers and ignores non resolvers`() {
        val logger = RecordingKspLogger()

        val file = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "ExampleResolvers.kt",
        )

        val fieldBase = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf(
                        "typeName" to "ExampleNode",
                        "fieldName" to "name",
                        "isBatching" to false,
                        "isSelective" to false,
                    ),
                ),
            ),
            containingFile = null,
            declarations = emptyList(),
        )

        val fieldResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
            superDeclarations = listOf(fieldBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val nonResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.NotAResolver",
            simpleName = "NotAResolver",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
            containingFile = file,
            declarations = emptyList(),
        )

        val resolver = ksResolver(
            files = listOf(
                ksFile(
                    packageName = "com.example.feature.resolvers",
                    fileName = "ExampleResolvers.kt",
                    declarations = listOf(fieldResolver, nonResolver),
                ),
            ),
            resolverAnnotated = listOf(fieldResolver),
        )

        val result = ResolverParamsExtractor(
            resolver = resolver,
            logger = logger,
        ).extractByFile()

        assertEquals(1, result.size)
        val descriptor = result.values.single()
        assertTrue(descriptor.nodes.isEmpty())
        assertEquals(1, descriptor.fields.size)
        assertEquals("ExampleNode", descriptor.fields.single().typeName)
        assertEquals("name", descriptor.fields.single().fieldName)
        assertEquals("com.example.feature.resolvers.ExampleNameResolver", descriptor.fields.single().implFqn)
    }

    @Test
    fun `extractByFile skips declarations without containing file`() {
        val logger = RecordingKspLogger()

        val exampleNodeBase = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            containingFile = null,
            declarations = emptyList(),
        )

        val resolverWithoutFile = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "",
                        "queryValueFragment" to "",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            superDeclarations = listOf(exampleNodeBase),
            containingFile = null,
            declarations = emptyList(),
        )

        val resolver = ksResolver(
            files = listOf(
                ksFile(
                    packageName = "com.example.feature.resolvers",
                    fileName = "ExampleResolvers.kt",
                    declarations = listOf(resolverWithoutFile),
                ),
            ),
            resolverAnnotated = listOf(resolverWithoutFile),
        )

        val result = ResolverParamsExtractor(
            resolver = resolver,
            logger = logger,
        ).extractByFile()

        assertTrue(result.isEmpty())
        assertTrue(
            logger.warns.any { it.contains("Skipping resolver without containing file") },
            logger.warns.joinToString("\n"),
        )
    }

    @Test
    fun `extractByFile skips node resolvers without @Resolver and includes those with it`() {
        val logger = RecordingKspLogger()

        val file = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "MixedResolvers.kt",
        )

        val nodeBase = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.Item",
            simpleName = "Item",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "Item", "isBatching" to false, "isSelective" to false),
                ),
            ),
            containingFile = null,
            declarations = emptyList(),
        )

        val resolverAnnotation = listOf(
            ksAnnotation(
                simpleName = "Resolver",
                args = mapOf(
                    "objectValueFragment" to "",
                    "queryValueFragment" to "",
                    "variables" to emptyList<Any>(),
                ),
            ),
        )

        val activeResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ItemResolver",
            simpleName = "ItemResolver",
            packageName = "com.example.feature.resolvers",
            annotations = resolverAnnotation,
            superDeclarations = listOf(nodeBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val draftResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ItemResolverV2",
            simpleName = "ItemResolverV2",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
            superDeclarations = listOf(nodeBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val resolver = ksResolver(
            files = listOf(
                ksFile(
                    packageName = "com.example.feature.resolvers",
                    fileName = "MixedResolvers.kt",
                    declarations = listOf(activeResolver, draftResolver),
                ),
            ),
            resolverAnnotated = listOf(activeResolver),
        )

        val result = ResolverParamsExtractor(
            resolver = resolver,
            logger = logger,
        ).extractByFile()

        assertEquals(1, result.size)
        val descriptor = result.values.single()
        assertEquals(1, descriptor.nodes.size)
        assertEquals("com.example.feature.resolvers.ItemResolver", descriptor.nodes.single().implFqn)
        assertTrue(descriptor.fields.isEmpty())
    }

    @Test
    fun `extractByFile sets bootstrapClass when a TenantBootstrapper-annotated class is found`() {
        val logger = RecordingKspLogger()

        val bootstrapperFile = ksFile(
            packageName = "com.example.feature",
            fileName = "FeatureTenantBootstrapper.kt",
        )
        val bootstrapperClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.FeatureTenantBootstrapper",
            simpleName = "FeatureTenantBootstrapper",
            packageName = "com.example.feature",
            containingFile = bootstrapperFile,
        )
        val fileWithBootstrapper = ksFile(
            packageName = "com.example.feature",
            fileName = "FeatureTenantBootstrapper.kt",
            declarations = listOf(bootstrapperClass),
        )

        val resolver = ksResolver(
            files = listOf(fileWithBootstrapper),
            bootstrapperAnnotated = listOf(bootstrapperClass),
        )

        val result = ResolverParamsExtractor(resolver = resolver, logger = logger).extractByFile()

        assertEquals(1, result.size)
        assertEquals(
            "com.example.feature.FeatureTenantBootstrapper",
            result.values.single().bootstrapClass,
        )
        assertTrue(logger.errors.isEmpty(), "Expected no errors: ${logger.errors}")
    }

    @Test
    fun `extractByFile logs error and excludes file when two TenantBootstrapper classes are in same file`() {
        val logger = RecordingKspLogger()

        val bootstrapperFile = ksFile(
            packageName = "com.example.feature",
            fileName = "Bootstrappers.kt",
        )
        val firstClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.BootstrapperA",
            simpleName = "BootstrapperA",
            packageName = "com.example.feature",
            containingFile = bootstrapperFile,
        )
        val secondClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.BootstrapperB",
            simpleName = "BootstrapperB",
            packageName = "com.example.feature",
            containingFile = bootstrapperFile,
        )
        val fileWithTwo = ksFile(
            packageName = "com.example.feature",
            fileName = "Bootstrappers.kt",
            declarations = listOf(firstClass, secondClass),
        )

        val resolver = ksResolver(
            files = listOf(fileWithTwo),
            bootstrapperAnnotated = listOf(firstClass, secondClass),
        )

        val result = ResolverParamsExtractor(resolver = resolver, logger = logger).extractByFile()

        assertTrue(result.isEmpty(), "Expected no descriptors when file has two bootstrappers")
        assertTrue(
            logger.errors.any { it.contains("at most one") },
            "Expected 'at most one' error: ${logger.errors}",
        )
    }
}

private fun ksResolver(
    files: List<KSFile>,
    bootstrapperAnnotated: List<KSAnnotated> = emptyList(),
    resolverAnnotated: List<KSAnnotated> = emptyList(),
): Resolver {
    val tenantBootstrapperFqn = requireNotNull(TenantBootstrapper::class.qualifiedName)
    val resolverAnnotationFqn = requireNotNull(ResolverAnnotation::class.qualifiedName)
    return proxy(Resolver::class.java) { method, args ->
        when (method.name) {
            "getAllFiles" -> files.asSequence()
            "getSymbolsWithAnnotation" -> {
                when (args?.firstOrNull() as? String) {
                    tenantBootstrapperFqn -> bootstrapperAnnotated.asSequence()
                    resolverAnnotationFqn -> resolverAnnotated.asSequence()
                    else -> emptySequence()
                }
            }
            else -> unsupported("Resolver.${method.name}")
        }
    }
}

private fun ksFile(
    packageName: String,
    fileName: String,
    declarations: List<KSDeclaration> = emptyList(),
): KSFile {
    val packageNameValue = ksName(packageName)
    val identity = "$packageName/$fileName"
    val filePath = if (packageName.isBlank()) fileName else "${packageName.replace('.', '/')}/$fileName"

    return proxy(KSFile::class.java) { method, args ->
        when (method.name) {
            "getPackageName" -> packageNameValue
            "getFileName" -> fileName
            "getFilePath" -> filePath
            "getDeclarations" -> declarations.asSequence()
            "toString" -> identity
            "hashCode" -> identity.hashCode()
            "equals" -> {
                val other = args?.singleOrNull()
                other?.toString() == identity
            }
            else -> unsupported("KSFile.${method.name}")
        }
    }
}

private fun ksClassDeclaration(
    qualifiedName: String,
    simpleName: String,
    packageName: String,
    annotations: List<KSAnnotation> = emptyList(),
    superDeclarations: List<KSClassDeclaration> = emptyList(),
    containingFile: KSFile?,
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
            "getContainingFile" -> containingFile
            "getDeclarations" -> declarations.asSequence()
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

private fun unsupported(name: String): Nothing {
    throw UnsupportedOperationException("Unexpected proxy call: $name")
}

@Suppress("UNCHECKED_CAST")
private fun <T> proxy(
    type: Class<T>,
    handler: (method: java.lang.reflect.Method, args: Array<out Any?>?) -> Any?,
): T {
    return Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, args ->
        handler(method, args)
    } as T
}
