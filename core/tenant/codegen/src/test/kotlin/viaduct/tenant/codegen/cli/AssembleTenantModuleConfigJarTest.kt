package viaduct.tenant.codegen.cli

import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.bootstrap.ExecutionRegistryConfigFile

class AssembleTenantModuleConfigJarTest {
    @TempDir
    private lateinit var tempDir: File

    private fun descriptorJar(
        name: String,
        entries: Map<String, String>
    ): File {
        val jarFile = File(tempDir, name)
        ZipOutputStream(jarFile.outputStream()).use { out ->
            entries.toSortedMap().forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return jarFile
    }

    private fun outputJar(): File = File(tempDir, "module-config.jar")

    private fun jarsListFile(jars: List<File>): File {
        val listFile = File(tempDir, "descriptor-jars.list")
        listFile.writeText(jars.joinToString(separator = "\n") { it.absolutePath })
        return listFile
    }

    private fun runCli(
        jars: List<File>,
        tenantPkg: String = "com.example.feature",
        tenantPackagePrefix: String? = "com.example",
        out: File = outputJar(),
        requireNonEmpty: Boolean = false,
        apiName: String = "kotlin",
        tenantMetadata: String? = null,
    ) {
        val args = mutableListOf(
            "--descriptor-jars-list",
            jarsListFile(jars).absolutePath,
            "--tenant-package",
            tenantPkg,
            "--api-name",
            apiName,
            "--output-jar",
            out.absolutePath,
        )
        if (tenantPackagePrefix != null) {
            args += listOf("--tenant-package-prefix", tenantPackagePrefix)
        }
        if (tenantMetadata != null) {
            val metadataFile = File(tempDir, "tenant-metadata.json").also { it.writeText(tenantMetadata) }
            args += listOf("--tenant-metadata-file", metadataFile.absolutePath)
        }
        if (requireNonEmpty) {
            args += "--require-non-empty"
        }
        AssembleTenantModuleConfigJar().main(args)
    }

    private fun readJarEntry(
        jarFile: File,
        path: String
    ): String? {
        return JarFile(jarFile).use { jar ->
            jar.getJarEntry(path)?.let { entry ->
                jar.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }
    }

    private fun ownershipDescriptorJar(): File =
        descriptorJar(
            "ownership.jar",
            mapOf(
                "viaduct-registry/com/example/feature/Resolvers.json" to """
                {
                  "nodes": [
                    {"implFqn":"com.example.feature.Node", "typeName":"Exact", "resolverBaseClass":"Base", "isBatching":false, "isSelective":false},
                    {"implFqn":"com.example.feature.nested.Node", "typeName":"Nested", "resolverBaseClass":"Base", "isBatching":false, "isSelective":false},
                    {"implFqn":"com.example.featureish.Node", "typeName":"Sibling", "resolverBaseClass":"Base", "isBatching":false, "isSelective":false}
                  ],
                  "fields": [
                    {"implFqn":"com.example.imported.Child${'$'}Resolver", "typeName":"Query", "fieldName":"imported", "resolverBaseClass":"Base", "isBatching":false, "isSelective":false}
                  ]
                }
                """.trimIndent(),
            ),
        )

    private fun readRegistry(): ExecutionRegistryConfigFile =
        requireNotNull(readJarEntry(outputJar(), "$REGISTRY_RESOURCE_PATH/com.example.feature.json"))
            .byteInputStream().use(ExecutionRegistryConfigFile::parse)

    @Test
    fun `applies tenant metadata to every resolver regardless of implementation package`() {
        runCli(
            jars = listOf(ownershipDescriptorJar()),
            tenantMetadata = """{"name":"tenant-project"}""",
        )
        val registry = readRegistry()
        assertEquals("feature", registry.tenantName)
        assertEquals("kotlin", registry.apiName)
        assertEquals("viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory", registry.executorFactory)
        assertEquals(
            mapOf("Exact" to mapOf("name" to "tenant-project"), "Nested" to mapOf("name" to "tenant-project"), "Sibling" to mapOf("name" to "tenant-project")),
            registry.nodes.associate { it.typeName to it.tenantAPIData["tenantMetadata"] },
        )
        assertEquals(mapOf("name" to "tenant-project"), registry.fields.single().tenantAPIData["tenantMetadata"])
    }

    @Test
    fun `empty ownership map emits explicit unknown for every entry`() {
        runCli(jars = listOf(ownershipDescriptorJar()), tenantMetadata = "{}")
        val registry = readRegistry()
        (registry.nodes.map { it.tenantAPIData } + registry.fields.map { it.tenantAPIData }).forEach {
            assertTrue(it.containsKey("tenantMetadata"))
            assertEquals(emptyMap<String, String>(), it["tenantMetadata"])
        }
    }

    @Test
    fun `omitting ownership emits explicit unknown for every entry`() {
        runCli(jars = listOf(ownershipDescriptorJar()))
        val registry = readRegistry()
        (registry.nodes.map { it.tenantAPIData } + registry.fields.map { it.tenantAPIData }).forEach {
            assertTrue(it.containsKey("tenantMetadata"))
            assertEquals(emptyMap<String, String>(), it["tenantMetadata"])
        }
    }

    @Test
    fun `rejects invalid ownership declarations`() {
        listOf(
            """{"name":""}""",
            """{"name":" "}""",
            """{"name":42}""",
            """{"name":null}""",
            """{"com.example.Resolver":"owner"}""",
            """{"name":"tenant", "other":"value"}""",
        ).forEach { metadata ->
            assertThrows(IllegalArgumentException::class.java) {
                runCli(jars = listOf(ownershipDescriptorJar()), tenantMetadata = metadata)
            }
        }
    }

    @Test
    fun `merges matching descriptors from descriptor jars into output jar`() {
        val firstJar = descriptorJar(
            name = "leaf-a.jar",
            entries = mapOf(
                "viaduct-registry/com/example/feature/AResolvers.json" to
                    """{"nodes":[{"attribution":"ANodeResolver","implFqn":"com.example.ANodeResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.A","typeName":"A"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
            ),
        )
        val secondJar = descriptorJar(
            name = "leaf-b.jar",
            entries = mapOf(
                "viaduct-registry/com/example/feature/BResolvers.json" to
                    """{"nodes":[{"attribution":"BNodeResolver","implFqn":"com.example.BNodeResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.B","typeName":"B"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
                "viaduct-registry/com/example/other/IgnoredResolvers.json" to
                    """{"nodes":[{"attribution":"Ignored","implFqn":"com.example.IgnoredResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.Ignored","typeName":"Ignored"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
            ),
        )

        val out = outputJar()
        runCli(jars = listOf(firstJar, secondJar), out = out)

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertNotNull(json)
        assertTrue(json!!.contains("ANodeResolver"), json)
        assertTrue(json.contains("BNodeResolver"), json)
        assertFalse(json.contains("IgnoredResolver"), json)
    }

    @Test
    fun `writes empty jar when no descriptor entries match tenant package`() {
        val descriptorJar = descriptorJar(
            name = "leaf.jar",
            entries = mapOf(
                "viaduct-registry/com/example/other/Resolvers.json" to
                    """{"nodes":[{"attribution":"Ignored","implFqn":"com.example.IgnoredResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.Ignored","typeName":"Ignored"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
            ),
        )

        val out = outputJar()
        runCli(jars = listOf(descriptorJar), out = out)

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertNotNull(json)
        assertTrue(json!!.contains("\"version\""), json)
        assertTrue(json.contains("\"executorFactory\""), json)
        assertTrue(json.contains("\"tenantName\""), json)
        assertTrue(json.contains("\"apiName\" : \"kotlin\""), json)
        assertTrue(json.contains("feature"), json)
        assertTrue(json.contains("\"nodes\""), json)
        assertTrue(json.contains("\"fields\""), json)
    }

    @Test
    fun `writes slash separated tenant module name derived from package prefix`() {
        val out = outputJar()
        runCli(
            jars = emptyList(),
            tenantPkg = "com.example.presentation.pdp.stays",
            tenantPackagePrefix = "com.example",
            out = out,
        )

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.presentation.pdp.stays.json")
        assertNotNull(json)
        assertTrue(json!!.contains("\"tenantName\" : \"presentation/pdp/stays\""), json)
    }

    @Test
    fun `writes empty registry resource when descriptor jar list is empty`() {
        val out = outputJar()
        runCli(jars = emptyList(), out = out)

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertNotNull(json)
        assertTrue(json!!.contains("\"nodes\""), json)
        assertTrue(json.contains("\"fields\""), json)
    }

    @Test
    fun `includes bootstrapClass when descriptor jar contains only tenant bootstrap metadata`() {
        val descriptorJar = descriptorJar(
            name = "leaf.jar",
            entries = mapOf(
                "viaduct-registry/com/example/feature/FeatureTenantBootstrapper.json" to
                    """{"nodes":[],"fields":[],"bootstrapClass":"com.example.feature.FeatureTenantBootstrapper"}""",
            ),
        )

        val out = outputJar()
        runCli(jars = listOf(descriptorJar), out = out)

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertNotNull(json)
        assertTrue(json!!.contains("\"bootstrapClass\""), json)
        assertTrue(json.contains("FeatureTenantBootstrapper"), json)
        assertTrue(json.contains("\"nodes\""), json)
        assertTrue(json.contains("\"fields\""), json)
    }

    @Test
    fun `named fragments from descriptor jars are carried to the registry`() {
        val fieldJar = descriptorJar(
            name = "leaf.jar",
            entries = mapOf(
                "viaduct-registry/com/example/feature/Resolvers.json" to
                    """{"nodes":[],"fields":[{"attribution":"AResolver","implFqn":"com.example.AResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.A","typeName":"A","fieldName":"f"}],"grtPackagePrefix":"viaduct.api.grts","namedFragments":[{"text":"fragment AFields on A { id }"}]}""",
            ),
        )
        val out = outputJar()
        runCli(jars = listOf(fieldJar), out = out)

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertNotNull(json)
        assertTrue(json!!.contains("\"namedFragments\""), json)
        assertTrue(json.contains("fragment AFields on A { id }"), json)
    }

    @Test
    fun `throws when require-non-empty is set and no descriptors match tenant package`() {
        val descriptorJar = descriptorJar(
            name = "leaf.jar",
            entries = mapOf(
                "viaduct-registry/com/example/other/Resolvers.json" to
                    """{"nodes":[{"attribution":"Ignored","implFqn":"com.example.IgnoredResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.Ignored","typeName":"Ignored"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
            ),
        )

        val out = outputJar()
        val error = assertThrows(IllegalStateException::class.java) {
            runCli(jars = listOf(descriptorJar), out = out, requireNonEmpty = true)
        }

        assertTrue(error.message!!.contains("com.example.feature"), error.message)
        assertTrue(error.message!!.contains("--require-non-empty"), error.message)
        assertFalse(out.exists(), "output jar should not be written when require-non-empty fails")
    }

    @Test
    fun `writes registry without throwing when require-non-empty is set and descriptors are present`() {
        val descriptorJar = descriptorJar(
            name = "leaf.jar",
            entries = mapOf(
                "viaduct-registry/com/example/feature/AResolvers.json" to
                    """{"nodes":[{"attribution":"ANodeResolver","implFqn":"com.example.ANodeResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.NodeResolvers.A","typeName":"A"}],"fields":[],"grtPackagePrefix":"viaduct.api.grts"}""",
            ),
        )

        val out = outputJar()
        runCli(jars = listOf(descriptorJar), out = out, requireNonEmpty = true)

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertNotNull(json)
        assertTrue(json!!.contains("ANodeResolver"), json)
    }

    @Test
    fun `named fragment from descriptor jar is inlined into field objectSelections`() {
        val fragmentJar = descriptorJar(
            name = "leaf-frags.jar",
            entries = mapOf(
                "viaduct-registry/com/example/feature/FieldAndFragments.json" to
                    """{"nodes":[],"fields":[{"attribution":"AResolver","implFqn":"com.example.AResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.A","typeName":"A","fieldName":"f","objectSelections":{"selections":"fragment _ on A { ...UserFields }","variablesProviders":[]}}],"grtPackagePrefix":"viaduct.api.grts","namedFragments":[{"text":"fragment UserFields on User { id name }"}]}""",
            ),
        )
        val out = outputJar()
        runCli(jars = listOf(fragmentJar), out = out)

        val json = readJarEntry(out, "$REGISTRY_RESOURCE_PATH/com.example.feature.json")
        assertNotNull(json)
        assertTrue(json!!.contains("fragment UserFields on User { id name }"), json)
    }
}
