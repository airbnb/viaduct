package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.resolver.Resolver as ResolverAnnotation

private val KOTLIN_VERSION_1_9 = KotlinVersion(1, 9)

class RegistryExtractorProcessorTest {
    @Test
    fun `process writes descriptor file for each source file containing node resolvers`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val file = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "ExampleResolvers.kt",
            declarations = listOf(
                ksNodeResolver(
                    qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
                    simpleName = "ExampleNodeResolver",
                    packageName = "com.example.feature.resolvers",
                    typeName = "ExampleNode",
                    containingFile = null, // set after construction
                ),
            ),
        )

        val nodeResolver = ksNodeResolverWithFile(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            typeName = "ExampleNode",
            containingFile = file,
        )

        val fileWithResolver = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "ExampleResolvers.kt",
            declarations = listOf(nodeResolver),
        )

        val resolver = ksResolver(files = listOf(fileWithResolver), resolverAnnotated = listOf(nodeResolver))
        val processor = RegistryExtractorProcessor(environment)

        val deferred = processor.process(resolver)

        assertTrue(deferred.isEmpty())
        assertEquals(1, codeGenerator.outputs.size)
        val outputKey = codeGenerator.outputs.keys.single()
        assertTrue(outputKey.contains("viaduct-registry"), outputKey)
        assertTrue(outputKey.contains("ExampleResolvers"), outputKey)
    }

    @Test
    fun `process returns empty list when no resolver-bearing files exist`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val emptyFile = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "NotAResolver.kt",
            declarations = emptyList(),
        )

        val resolver = ksResolver(files = listOf(emptyFile))
        val processor = RegistryExtractorProcessor(environment)

        val deferred = processor.process(resolver)

        assertTrue(deferred.isEmpty())
        assertTrue(codeGenerator.outputs.isEmpty())
    }

    @Test
    fun `process skips second invocation when already generated`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val nodeResolver = ksNodeResolverWithFile(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            typeName = "ExampleNode",
            containingFile = ksFile(
                packageName = "com.example.feature.resolvers",
                fileName = "ExampleResolvers.kt",
            ),
        )

        val fileWithResolver = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "ExampleResolvers.kt",
            declarations = listOf(nodeResolver),
        )

        val resolver = ksResolver(files = listOf(fileWithResolver), resolverAnnotated = listOf(nodeResolver))
        val processor = RegistryExtractorProcessor(environment)

        // First call generates output
        processor.process(resolver)
        val firstOutputCount = codeGenerator.outputs.size

        // Second call should skip generation
        processor.process(resolver)

        assertEquals(firstOutputCount, codeGenerator.outputs.size)
        assertTrue(
            logger.infos.any { it.contains("already generated") },
            "Expected skip message on second invocation: ${logger.infos}",
        )
    }

    @Test
    fun `process logs number of generated descriptor files`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val nodeResolver = ksNodeResolverWithFile(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            typeName = "ExampleNode",
            containingFile = ksFile(
                packageName = "com.example.feature.resolvers",
                fileName = "ExampleResolvers.kt",
            ),
        )

        val fileWithResolver = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "ExampleResolvers.kt",
            declarations = listOf(nodeResolver),
        )

        val resolver = ksResolver(files = listOf(fileWithResolver), resolverAnnotated = listOf(nodeResolver))
        val processor = RegistryExtractorProcessor(environment)

        processor.process(resolver)

        assertTrue(
            logger.infos.any { it.contains("Generated") && it.contains("registry descriptor file") },
            "Expected generated count log: ${logger.infos}",
        )
    }

    @Test
    fun `process writes descriptor file for source files containing field resolvers`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val containingFile = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "FieldResolvers.kt",
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
            superDeclarations = emptyList(),
            containingFile = null,
            declarations = emptyList(),
        )

        val fieldResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
            superDeclarations = listOf(fieldBase),
            containingFile = containingFile,
            declarations = emptyList(),
        )

        val fileWithFieldResolver = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "FieldResolvers.kt",
            declarations = listOf(fieldResolver),
        )

        val resolver = ksResolver(files = listOf(fileWithFieldResolver), resolverAnnotated = listOf(fieldResolver))
        val processor = RegistryExtractorProcessor(environment)

        processor.process(resolver)

        assertEquals(
            setOf("viaduct-registry/com/example/feature/resolvers/FieldResolvers.json"),
            codeGenerator.outputs.keys,
        )
        val json = codeGenerator.outputs.values.single()
        assertTrue(json.contains("\"fieldName\" : \"name\""), json)
        assertTrue(json.contains("\"typeName\" : \"ExampleNode\""), json)
    }

    @Test
    fun `process captures bootstrapClass from TenantBootstrapper-annotated class in descriptor`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

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
        val processor = RegistryExtractorProcessor(environment)

        processor.process(resolver)

        assertEquals(1, codeGenerator.outputs.size)
        val json = codeGenerator.outputs.values.single()
        assertTrue(json.contains("com.example.feature.FeatureTenantBootstrapper"), json)
        assertTrue(json.contains("bootstrapClass"), json)
    }

    @Test
    fun `process emits no bootstrapClass when no TenantBootstrapper annotation present`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val nodeResolver = ksNodeResolverWithFile(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            typeName = "ExampleNode",
            containingFile = ksFile(
                packageName = "com.example.feature.resolvers",
                fileName = "ExampleResolvers.kt",
            ),
        )
        val fileWithResolver = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "ExampleResolvers.kt",
            declarations = listOf(nodeResolver),
        )

        val resolver = ksResolver(files = listOf(fileWithResolver), resolverAnnotated = listOf(nodeResolver))
        val processor = RegistryExtractorProcessor(environment)

        processor.process(resolver)

        val json = codeGenerator.outputs.values.single()
        assertFalse(json.contains("bootstrapClass"), json)
    }

    @Test
    fun `process produces no output when node resolvers lack @Resolver`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val containingFile = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "DraftResolvers.kt",
        )

        val nodeBase = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.Widget",
            simpleName = "Widget",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "Widget", "isBatching" to false, "isSelective" to false),
                ),
            ),
            superDeclarations = emptyList(),
            containingFile = null,
            declarations = emptyList(),
        )

        val draftResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.WidgetResolverDraft",
            simpleName = "WidgetResolverDraft",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
            superDeclarations = listOf(nodeBase),
            containingFile = containingFile,
            declarations = emptyList(),
        )

        val fileWithDraft = ksFile(
            packageName = "com.example.feature.resolvers",
            fileName = "DraftResolvers.kt",
            declarations = listOf(draftResolver),
        )

        val resolver = ksResolver(files = listOf(fileWithDraft))
        val processor = RegistryExtractorProcessor(environment)

        val deferred = processor.process(resolver)

        assertTrue(deferred.isEmpty())
        assertTrue(codeGenerator.outputs.isEmpty(), "Expected no descriptor output for draft resolvers without @Resolver")
    }
}

private fun fakeEnvironment(
    logger: RecordingKspLogger,
    codeGenerator: RecordingCodeGenerator,
): SymbolProcessorEnvironment {
    return SymbolProcessorEnvironment(
        options = emptyMap(),
        kotlinVersion = KOTLIN_VERSION_1_9,
        codeGenerator = codeGenerator,
        logger = logger,
    )
}

private fun ksResolver(
    files: List<KSFile>,
    bootstrapperAnnotated: List<KSAnnotated> = emptyList(),
    resolverAnnotated: List<KSAnnotated> = emptyList(),
): Resolver {
    val tenantBootstrapperFqn = requireNotNull(viaduct.service.api.spi.TenantBootstrapper::class.qualifiedName)
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

private fun ksNodeResolver(
    qualifiedName: String,
    simpleName: String,
    packageName: String,
    typeName: String,
    containingFile: KSFile?,
): KSClassDeclaration {
    val base = ksClassDeclaration(
        qualifiedName = "$packageName.resolverbases.NodeResolvers.$simpleName",
        simpleName = simpleName,
        packageName = "$packageName.resolverbases",
        annotations = listOf(
            ksAnnotation(simpleName = "NodeResolverFor", args = mapOf("typeName" to typeName, "isBatching" to false, "isSelective" to false)),
        ),
        superDeclarations = emptyList(),
        containingFile = null,
        declarations = emptyList(),
    )
    return ksClassDeclaration(
        qualifiedName = qualifiedName,
        simpleName = simpleName,
        packageName = packageName,
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
        superDeclarations = listOf(base),
        containingFile = containingFile,
        declarations = emptyList(),
    )
}

private fun ksNodeResolverWithFile(
    qualifiedName: String,
    simpleName: String,
    packageName: String,
    typeName: String,
    containingFile: KSFile,
): KSClassDeclaration {
    return ksNodeResolver(
        qualifiedName = qualifiedName,
        simpleName = simpleName,
        packageName = packageName,
        typeName = typeName,
        containingFile = containingFile,
    )
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
