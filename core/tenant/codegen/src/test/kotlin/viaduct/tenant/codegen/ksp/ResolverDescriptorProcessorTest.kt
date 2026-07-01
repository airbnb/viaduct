package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSName
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResolverDescriptorProcessorTest {
    @Test
    fun `write stores descriptor under viaduct-registry package path`() {
        val codeGenerator = RecordingCodeGenerator()
        val writer = ResolverDescriptorProcessor(
            codeGenerator = codeGenerator,
            jsonCodec = ResolverParamsJsonCodec(),
        )

        writer.write(
            sourceFile = ksFile(
                packageName = "com.example.feature.resolvers",
                fileName = "ExampleNodeResolver.kt",
            ),
            descriptorFile = PerSourceDescriptorFile(
                nodes = listOf(
                    ResolverParams.Node(
                        implFqn = "com.example.feature.resolvers.ExampleNodeResolver",
                        typeName = "ExampleNode",
                        resolverBaseClass = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
                        attribution = "ExampleNodeResolver",
                        isBatching = false,
                        isSelective = false,
                    ),
                ),
                fields = emptyList(),
            ),
        )

        assertEquals(
            setOf("viaduct-registry/com/example/feature/resolvers/ExampleNodeResolver.json"),
            codeGenerator.outputs.keys,
        )

        val json = codeGenerator.outputs.getValue(
            "viaduct-registry/com/example/feature/resolvers/ExampleNodeResolver.json",
        )

        assertTrue(json.contains("\"typeName\" : \"ExampleNode\""), json)
        assertTrue(
            json.contains("\"implFqn\" : \"com.example.feature.resolvers.ExampleNodeResolver\""),
            json,
        )
    }

    @Test
    fun `write stores descriptor under viaduct-registry root when package is blank`() {
        val codeGenerator = RecordingCodeGenerator()
        val writer = ResolverDescriptorProcessor(
            codeGenerator = codeGenerator,
            jsonCodec = ResolverParamsJsonCodec(),
        )

        writer.write(
            sourceFile = ksFile(
                packageName = "",
                fileName = "RootResolver.kt",
            ),
            descriptorFile = PerSourceDescriptorFile(
                nodes = listOf(
                    ResolverParams.Node(
                        implFqn = "RootResolver",
                        typeName = "RootType",
                        resolverBaseClass = "resolverbases.NodeResolvers.RootType",
                        attribution = "RootResolver",
                        isBatching = false,
                        isSelective = false,
                    ),
                ),
                fields = emptyList(),
            ),
        )

        assertEquals(
            setOf("viaduct-registry/RootResolver.json"),
            codeGenerator.outputs.keys,
        )
    }

    @Test
    fun `write skips empty descriptor`() {
        val codeGenerator = RecordingCodeGenerator()
        val writer = ResolverDescriptorProcessor(
            codeGenerator = codeGenerator,
            jsonCodec = ResolverParamsJsonCodec(),
        )

        writer.write(
            sourceFile = ksFile(
                packageName = "com.example.feature.resolvers",
                fileName = "ExampleNodeResolver.kt",
            ),
            descriptorFile = PerSourceDescriptorFile(
                nodes = emptyList(),
                fields = emptyList(),
            ),
        )

        assertTrue(codeGenerator.outputs.isEmpty())
    }
}

private fun ksFile(
    packageName: String,
    fileName: String,
): KSFile {
    val ksName = Proxy.newProxyInstance(
        KSName::class.java.classLoader,
        arrayOf(KSName::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "asString" -> packageName
            "getShortName" -> packageName.substringAfterLast('.', packageName)
            "getQualifier" -> packageName.substringBeforeLast('.', "")
            "toString" -> packageName
            else -> unsupported("KSName.${method.name}")
        }
    } as KSName

    return Proxy.newProxyInstance(
        KSFile::class.java.classLoader,
        arrayOf(KSFile::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPackageName" -> ksName
            "getFileName" -> fileName
            "toString" -> "$packageName/$fileName"
            else -> unsupported("KSFile.${method.name}")
        }
    } as KSFile
}

private fun unsupported(name: String): Nothing {
    throw UnsupportedOperationException("Unexpected proxy call: $name")
}
