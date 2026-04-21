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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val outputFile = out.resolve("META-INF/viaduct/modules/$tenantPkg.json")
        assertTrue(outputFile.exists(), "Expected output file to be created at ${outputFile.path}")
    }

    @Test
    fun `output JSON contains tenant package and zero descriptor count when no descriptors present`() {
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"tenantPackage\": \"com.example.feature\""), json)
        assertTrue(json.contains("\"descriptorCount\": 0"), json)
        assertTrue(json.contains("\"descriptors\": ["), json)
    }

    @Test
    fun `output JSON includes schema file name and escaped content`() {
        val descriptors = descriptorDir()
        val schemaContent = "type Query { hello: String }"
        val schema = schemaFile(schemaContent)
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"name\": \"schema.graphql\""), json)
        assertTrue(json.contains("type Query { hello: String }"), json)
    }

    @Test
    fun `output JSON descriptor count reflects number of json files in descriptor dir`() {
        val descriptors = descriptorDir()
        File(descriptors, "alpha.json").writeText("{\"nodes\": [], \"fields\": []}")
        File(descriptors, "beta.json").writeText("{\"nodes\": [], \"fields\": []}")
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"descriptorCount\": 2"), json)
    }

    @Test
    fun `output JSON descriptors array contains relative path and content of each descriptor file`() {
        val descriptors = descriptorDir()
        val descriptorContent = "{\"nodes\": [], \"fields\": []}"
        File(descriptors, "MyResolver.json").writeText(descriptorContent)
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"path\": \"MyResolver.json\""), json)
        assertTrue(json.contains("\"content\": $descriptorContent"), json)
    }

    @Test
    fun `descriptors are sorted by relative path in the output`() {
        val descriptors = descriptorDir()
        File(descriptors, "ZResolver.json").writeText("{\"nodes\": [], \"fields\": []}")
        File(descriptors, "AResolver.json").writeText("{\"nodes\": [], \"fields\": []}")
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        val aIndex = json.indexOf("AResolver.json")
        val zIndex = json.indexOf("ZResolver.json")
        assertTrue(aIndex < zIndex, "Expected AResolver.json to appear before ZResolver.json in sorted output")
    }

    @Test
    fun `non-json files in descriptor dir are ignored`() {
        val descriptors = descriptorDir()
        File(descriptors, "something.txt").writeText("not json")
        File(descriptors, "MyResolver.json").writeText("{\"nodes\": [], \"fields\": []}")
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"descriptorCount\": 1"), json)
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val outputFile = out.resolve("META-INF/viaduct/modules/$tenantPkg.json")
        assertTrue(outputFile.exists(), "Expected output file to be created even in non-existent nested dir")
    }

    @Test
    fun `schema content with special characters is properly escaped in output`() {
        val descriptors = descriptorDir()
        val schemaContent = "type Query {\n  field: String # with \"quotes\" and backslash \\\n}"
        val schema = schemaFile(schemaContent)
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        // Newlines should be escaped as \n in the JSON string
        assertTrue(json.contains("\\n"), "Expected newlines to be escaped as \\n in JSON output")
        // Quotes should be escaped
        assertTrue(json.contains("\\\"quotes\\\""), "Expected quotes to be escaped in JSON output")
        // Backslashes should be escaped
        assertTrue(json.contains("\\\\"), "Expected backslashes to be escaped in JSON output")
    }

    @Test
    fun `descriptors in subdirectories are included with their relative path`() {
        val descriptors = descriptorDir()
        val subDir = File(descriptors, "com/example/feature/resolvers").also { it.mkdirs() }
        File(subDir, "ExampleNodeResolver.json").writeText("{\"nodes\": [], \"fields\": []}")
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
                "--output-dir",
                out.absolutePath,
            ),
        )

        val json = out.resolve("META-INF/viaduct/modules/$tenantPkg.json").readText()
        assertTrue(json.contains("\"descriptorCount\": 1"), json)
        assertTrue(json.contains("ExampleNodeResolver.json"), json)
    }
}
