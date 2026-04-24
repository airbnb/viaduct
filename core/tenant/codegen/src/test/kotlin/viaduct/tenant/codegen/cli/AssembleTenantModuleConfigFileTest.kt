package viaduct.tenant.codegen.cli

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AssembleTenantModuleConfigFileTest {
    @TempDir
    private lateinit var tempDir: File

    private fun descriptorDir(): File = File(tempDir, "descriptors").also { it.mkdirs() }

    private fun outputDir(): File = File(tempDir, "output")

    private fun schemaFile(content: String = "type Query { hello: String }"): File {
        return File(tempDir, "schema.graphql").also { it.writeText(content) }
    }

    private fun nodeDescriptor(typeName: String = "MyType") =
        """{"nodes": [{"typeName": "$typeName", "implFqn": "com.example.$typeName", "resolverBaseClass": "com.example.Base$typeName"}], "fields": []}"""

    @Test
    fun `writes output file under META-INF viaduct modules with tenant package name`() {
        val descriptors = descriptorDir()
        val schema = schemaFile()
        val out = outputDir()
        val tenantPkg = "com.example.feature"

        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--schema-file",
                schema.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                "com.example.ExecutorFactory",
                "--output-dir",
                out.absolutePath,
            ),
        )

        val outputFile = out.resolve("META-INF/viaduct/modules/$tenantPkg.json")
        assertTrue(outputFile.exists(), "Expected output file to be created at ${outputFile.path}")
    }

    @Test
    fun `output JSON contains zero nodes when no descriptors present`() {
        val descriptors = descriptorDir()
        val schema = schemaFile()
        val out = outputDir()
        val tenantPkg = "com.example.feature"

        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--schema-file",
                schema.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                "com.example.ExecutorFactory",
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"nodes\" : [ ]"), json)
    }

    @Test
    fun `output JSON node count reflects number of node entries across descriptor files`() {
        val descriptors = descriptorDir()
        File(descriptors, "alpha.json").writeText(nodeDescriptor("Alpha"))
        File(descriptors, "beta.json").writeText(nodeDescriptor("Beta"))
        val schema = schemaFile()
        val out = outputDir()
        val tenantPkg = "com.example.feature"

        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--schema-file",
                schema.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                "com.example.ExecutorFactory",
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"typeName\" : \"Alpha\""), json)
        assertTrue(json.contains("\"typeName\" : \"Beta\""), json)
    }

    @Test
    fun `nodes are sorted by type name in the output`() {
        val descriptors = descriptorDir()
        File(descriptors, "ZResolver.json").writeText(nodeDescriptor("ZType"))
        File(descriptors, "AResolver.json").writeText(nodeDescriptor("AType"))
        val schema = schemaFile()
        val out = outputDir()
        val tenantPkg = "com.example.feature"

        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--schema-file",
                schema.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                "com.example.ExecutorFactory",
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        val aIndex = json.indexOf("AType")
        val zIndex = json.indexOf("ZType")
        assertTrue(aIndex < zIndex, "Expected AType to appear before ZType in sorted output")
    }

    @Test
    fun `non-json files in descriptor dir are ignored`() {
        val descriptors = descriptorDir()
        File(descriptors, "something.txt").writeText("not json")
        File(descriptors, "MyResolver.json").writeText(nodeDescriptor("MyType"))
        val schema = schemaFile()
        val out = outputDir()
        val tenantPkg = "com.example.feature"

        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--schema-file",
                schema.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                "com.example.ExecutorFactory",
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"typeName\" : \"MyType\""), json)
        assertFalse(json.contains("something.txt"), json)
    }

    @Test
    fun `output dir is created if it does not exist`() {
        val descriptors = descriptorDir()
        val schema = schemaFile()
        val out = File(tempDir, "nonexistent/deeply/nested/output")
        assertFalse(out.exists())
        val tenantPkg = "com.example.feature"

        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--schema-file",
                schema.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                "com.example.ExecutorFactory",
                "--output-dir",
                out.absolutePath,
            ),
        )

        val outputFile = out.resolve("META-INF/viaduct/modules/$tenantPkg.json")
        assertTrue(outputFile.exists(), "Expected output file to be created even in non-existent nested dir")
    }

    @Test
    fun `descriptors in subdirectories are included`() {
        val descriptors = descriptorDir()
        val subDir = File(descriptors, "com/example/feature/resolvers").also { it.mkdirs() }
        File(subDir, "ExampleNodeResolver.json").writeText(nodeDescriptor("ExampleNode"))
        val schema = schemaFile()
        val out = outputDir()
        val tenantPkg = "com.example.feature"

        AssembleTenantModuleConfigFile().main(
            listOf(
                "--descriptor-dir",
                descriptors.absolutePath,
                "--schema-file",
                schema.absolutePath,
                "--tenant-package",
                tenantPkg,
                "--executor-factory",
                "com.example.ExecutorFactory",
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"typeName\" : \"ExampleNode\""), json)
    }
}
