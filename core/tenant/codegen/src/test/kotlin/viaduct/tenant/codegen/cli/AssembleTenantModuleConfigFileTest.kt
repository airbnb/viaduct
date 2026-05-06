package viaduct.tenant.codegen.cli

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class AssembleTenantModuleConfigFileTest {
    @TempDir
    private lateinit var tempDir: File

    private fun descriptorDir(): File = File(tempDir, "descriptors").also { it.mkdirs() }

    private fun outputDir(): File = File(tempDir, "output")

    private fun runCli(
        descriptors: File = descriptorDir(),
        tenantPkg: String = "com.example.feature",
        executorFactory: String = "com.example.feature.ExampleExecutorFactory",
        out: File = outputDir(),
    ) {
        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                executorFactory,
                "--output-dir",
                out.absolutePath,
            ),
        )
    }

    @Test
    fun `writes output file under META-INF viaduct modules with tenant package name`() {
        val out = outputDir()
        runCli(out = out)

        val outputFile = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertTrue(outputFile.exists(), "Expected output file to be created at ${outputFile.path}")
    }

    @Test
    fun `output JSON contains empty registry when no descriptors present`() {
        val out = outputDir()
        runCli(out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("\"nodes\""), json)
        assertTrue(json.contains("\"fields\""), json)
    }

    @Test
    fun `output JSON contains executorFactory from CLI arg`() {
        val out = outputDir()
        runCli(executorFactory = "com.example.MyExecutorFactory", out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("com.example.MyExecutorFactory"), json)
    }

    @Test
    fun `output JSON contains version and executorFactory fields`() {
        val out = outputDir()
        runCli(executorFactory = "com.example.feature.ExampleExecutorFactory", out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("\"version\""), json)
        assertTrue(json.contains("\"executorFactory\""), json)
    }

    @Test
    fun `output JSON includes node entry assembled from descriptor`() {
        val descriptors = descriptorDir()
        File(descriptors, "ExampleResolvers.json").writeText(
            """
            {
              "nodes": [ {
                "attribution": "ExampleNodeResolver",
                "implFqn": "com.example.feature.resolvers.ExampleNodeResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
                "typeName": "ExampleNode"
              } ],
              "fields": [],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("ExampleNodeResolver"), json)
        assertTrue(json.contains("ExampleNode"), json)
        assertTrue(json.contains("\"typeName\""), json)
    }

    @Test
    fun `output JSON includes field entry assembled from descriptor`() {
        val descriptors = descriptorDir()
        File(descriptors, "FieldResolvers.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "ExampleNameResolver",
                "implFqn": "com.example.feature.resolvers.ExampleNameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.ExampleName",
                "typeName": "ExampleNode",
                "fieldName": "name"
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("ExampleNameResolver"), json)
        assertTrue(json.contains("\"fieldName\""), json)
        assertTrue(json.contains("\"name\""), json)
    }

    @Test
    fun `descriptors from multiple files are merged into single registry`() {
        val descriptors = descriptorDir()
        File(descriptors, "AResolvers.json").writeText(
            """{"nodes": [{"attribution":"ANodeResolver","implFqn":"com.example.ANodeResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.A","typeName":"A"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
        )
        File(descriptors, "BResolvers.json").writeText(
            """{"nodes": [{"attribution":"BNodeResolver","implFqn":"com.example.BNodeResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.B","typeName":"B"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("ANodeResolver"), json)
        assertTrue(json.contains("BNodeResolver"), json)
    }

    @Test
    fun `non-json files in descriptor dir are ignored`() {
        val descriptors = descriptorDir()
        File(descriptors, "something.txt").writeText("not json")
        File(descriptors, "MyResolver.json").writeText(
            """{"nodes":[],"fields":[]}""",
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertFalse(json.contains("something.txt"), json)
    }

    @Test
    fun `output dir is created if it does not exist`() {
        val out = File(tempDir, "nonexistent/deeply/nested/output")
        assertFalse(out.exists())
        runCli(out = out)

        val outputFile = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertTrue(outputFile.exists(), "Expected output file to be created even in non-existent nested dir")
    }

    @Test
    fun `descriptors in subdirectories are included`() {
        val descriptors = descriptorDir()
        val subDir = File(descriptors, "com/example/feature/resolvers").also { it.mkdirs() }
        File(subDir, "ExampleNodeResolver.json").writeText(
            """{"nodes": [{"attribution":"ExampleNodeResolver","implFqn":"com.example.feature.resolvers.ExampleNodeResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.feature.resolverbases.NodeResolvers.ExampleNode","typeName":"ExampleNode"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("ExampleNode"), json)
    }

    @Test
    fun `resolver class names are preserved in output`() {
        val descriptors = descriptorDir()
        File(descriptors, "ExampleResolvers.json").writeText(
            "{\"nodes\": [{\"attribution\":\"A\",\"implFqn\":\"com.example.resolvers.AResolver\",\"isBatching\":false,\"isSelective\":false,\"resolverBaseClass\":\"com.example.bases.A\",\"typeName\":\"A\"}],\"fields\":[],\"grtPackagePrefix\":\"viaduct.api.grts\"}",
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("com.example.resolvers.AResolver"), json)
    }

    @Test
    fun `bootstrapClass is present in output when descriptor file contains bootstrapClass`() {
        val descriptors = descriptorDir()
        File(descriptors, "FeatureTenantBootstrapper.json").writeText(
            """{"nodes":[],"fields":[],"bootstrapClass":"com.example.feature.FeatureTenantBootstrapper"}""",
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("com.example.feature.FeatureTenantBootstrapper"), json)
        assertTrue(json.contains("bootstrapClass"), json)
    }

    @Test
    fun `bootstrapClass is absent from output when no descriptor contains bootstrapClass`() {
        val out = outputDir()
        runCli(out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertFalse(json.contains("bootstrapClass"), json)
    }

    @Test
    fun `throws when two descriptor files both contain bootstrapClass`() {
        val descriptors = descriptorDir()
        File(descriptors, "BootstrapperA.json").writeText(
            """{"nodes":[],"fields":[],"bootstrapClass":"com.example.feature.BootstrapperA"}""",
        )
        File(descriptors, "BootstrapperB.json").writeText(
            """{"nodes":[],"fields":[],"bootstrapClass":"com.example.feature.BootstrapperB"}""",
        )

        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir())
        }
        assertTrue(exception.message!!.contains("at most one"), exception.message)
    }
}
