package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.Resolver
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
                    args = mapOf("typeName" to "ExampleNode"),
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
                    args = mapOf("typeName" to "Account"),
                ),
            ),
            containingFile = null,
            declarations = emptyList(),
        )

        val exampleResolverB = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ZExampleNodeResolver",
            simpleName = "ZExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
            superDeclarations = listOf(exampleNodeBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val exampleResolverA = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.AExampleNodeResolver",
            simpleName = "AExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
            superDeclarations = listOf(exampleNodeBase),
            containingFile = file,
            declarations = emptyList(),
        )

        val accountResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.AccountResolver",
            simpleName = "AccountResolver",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
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
    fun `extractByFile ignores field resolvers and non resolvers`() {
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
        )

        val result = ResolverParamsExtractor(
            resolver = resolver,
            logger = logger,
        ).extractByFile()

        assertTrue(result.isEmpty())
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
                    args = mapOf("typeName" to "ExampleNode"),
                ),
            ),
            containingFile = null,
            declarations = emptyList(),
        )

        val resolverWithoutFile = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            annotations = emptyList(),
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
}

private fun ksResolver(files: List<KSFile>,): Resolver {
    return proxy(Resolver::class.java) { method, _ ->
        when (method.name) {
            "getAllFiles" -> files.asSequence()
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

private fun ksTypeReference(declaration: KSClassDeclaration,): KSTypeReference {
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
