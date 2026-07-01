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
        tenantPackagePrefix: String? = "com.example",
        executorFactory: String = "com.example.feature.ExampleExecutorFactory",
        out: File = outputDir(),
        schemaSdl: File? = null,
    ) {
        val args = mutableListOf(
            "--descriptor-dir",
            descriptors.absolutePath,
            "--tenant-package",
            tenantPkg,
            "--executor-factory",
            executorFactory,
            "--output-dir",
            out.absolutePath,
        )
        if (tenantPackagePrefix != null) {
            args += listOf("--tenant-package-prefix", tenantPackagePrefix)
        }
        if (schemaSdl != null) {
            args += listOf("--schema-sdl", schemaSdl.absolutePath)
        }
        AssembleTenantModuleConfigFile().main(args)
    }

    private fun schemaFile(sdl: String): File = File(tempDir, "schema.graphqls").also { it.writeText(sdl) }

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
        assertTrue(json.contains("\"tenantName\""), json)
        assertTrue(json.contains("feature"), json)
    }

    @Test
    fun `output JSON contains slash separated tenant module name derived from package prefix`() {
        val out = outputDir()
        runCli(
            tenantPkg = "com.example.presentation.pdp.stays",
            tenantPackagePrefix = "com.example",
            out = out,
        )

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.presentation.pdp.stays.json").readText()
        assertTrue(json.contains("\"tenantName\" : \"presentation/pdp/stays\""), json)
    }

    @Test
    fun `fails with clear error when tenant package matches package prefix`() {
        val exception = assertThrows<IllegalArgumentException> {
            runCli(
                tenantPkg = "com.example",
                tenantPackagePrefix = "com.example",
            )
        }

        assertTrue(exception.message!!.contains("Tenant module name must not be empty"), exception.message)
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

    @Test
    fun `named fragments are carried to the registry for runtime operation resolution`() {
        val descriptors = descriptorDir()
        File(descriptors, "FieldResolvers.json").writeText(
            """{"nodes":[],"fields":[{"attribution":"AResolver","implFqn":"com.example.AResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.A","typeName":"A","fieldName":"f"}],"grtPackagePrefix":"viaduct.api.grts"}""",
        )
        File(descriptors, "FragmentDefs.json").writeText(
            """{"nodes":[],"fields":[],"namedFragments":["fragment AFields on A { id }"]}""",
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        // The registry carries named fragments so ctx.query/ctx.mutation operation strings can
        // resolve their spreads at the tenant boundary.
        assertTrue(json.contains("\"namedFragments\""), json)
        assertTrue(json.contains("fragment AFields on A { id }"), json)
    }

    @Test
    fun `named fragment spread is appended into objectSelections at assembly time`() {
        val descriptors = descriptorDir()
        File(descriptors, "FieldAndFragments.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "LabelResolver",
                "implFqn": "com.example.feature.resolvers.LabelResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Label",
                "typeName": "User",
                "fieldName": "label",
                "objectSelections": {
                  "selections": "fragment _ on User { ...UserFields }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts",
              "namedFragments": [ "fragment UserFields on User { id name }" ]
            }
            """.trimIndent(),
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("fragment UserFields on User { id name }"), json)
    }

    @Test
    fun `named fragment spread is appended into querySelections at assembly time`() {
        val descriptors = descriptorDir()
        File(descriptors, "FragAndQuery.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "GreetingResolver",
                "implFqn": "com.example.feature.resolvers.GreetingResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Greeting",
                "typeName": "User",
                "fieldName": "greeting",
                "querySelections": {
                  "selections": "fragment _ on Query { ...ViewerFields }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts",
              "namedFragments": [ "fragment ViewerFields on Query { viewer { name } }" ]
            }
            """.trimIndent(),
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("fragment ViewerFields on Query { viewer { name } }"), json)
    }

    @Test
    fun `named fragment from one descriptor is inlined into field selections from another descriptor`() {
        val descriptors = descriptorDir()
        File(descriptors, "FieldResolvers.json").writeText(
            """{"nodes":[],"fields":[{"attribution":"AResolver","implFqn":"com.example.AResolver","isBatching":false,"isSelective":false,"resolverBaseClass":"com.example.bases.A","typeName":"A","fieldName":"f","objectSelections":{"selections":"fragment _ on A { ...AFields }","variablesProviders":[]}}],"grtPackagePrefix":"viaduct.api.grts"}""",
        )
        File(descriptors, "FragmentDefs.json").writeText(
            """{"nodes":[],"fields":[],"namedFragments":["fragment AFields on A { id }"]}""",
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("fragment AFields on A { id }"), json)
    }

    @Test
    fun `fragment without matching spread is not appended into selections`() {
        val descriptors = descriptorDir()
        File(descriptors, "UnusedFrag.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.bases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on User { id }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts",
              "namedFragments": [ "fragment UnusedFrag on User { email }" ]
            }
            """.trimIndent(),
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        // Not appended into the resolver's objectSelections (no matching spread): the fragment is
        // only ever spread, never inlined into a selections block.
        assertFalse(json.contains("...UnusedFrag"), json)
        // ...but still carried in the registry's namedFragments for runtime operation resolution.
        assertTrue(json.contains("fragment UnusedFrag on User { email }"), json)
    }

    @Test
    fun `entry fragment named _ is renamed to Main when named fragments are appended`() {
        val descriptors = descriptorDir()
        File(descriptors, "UnderscoreEntry.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "LabelResolver",
                "implFqn": "com.example.feature.resolvers.LabelResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Label",
                "typeName": "User",
                "fieldName": "label",
                "objectSelections": {
                  "selections": "fragment _ on User { ...UserFields }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts",
              "namedFragments": [ "fragment UserFields on User { id name }" ]
            }
            """.trimIndent(),
        )
        val out = outputDir()
        runCli(descriptors = descriptors, out = out)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("fragment Main on User"), "entry fragment should be renamed to Main: $json")
        assertFalse(json.contains("fragment _ on"), "_ entry fragment should have been renamed: $json")
        assertTrue(json.contains("fragment UserFields on User { id name }"), json)
    }

    @Test
    fun `duplicate @GraphQLFragment names across descriptors fail at assembly`() {
        val descriptors = descriptorDir()
        File(descriptors, "FragA.json").writeText(
            """{"nodes":[],"fields":[],"namedFragments":["fragment UserFields on User { id }"]}""",
        )
        File(descriptors, "FragB.json").writeText(
            """{"nodes":[],"fields":[],"namedFragments":["fragment UserFields on User { name }"]}""",
        )
        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir())
        }
        assertTrue(exception.message!!.contains("UserFields"), exception.message)
        assertTrue(exception.message!!.contains("Duplicate"), exception.message)
    }

    @Test
    fun `RSS-local fragment name conflicting with @GraphQLFragment name fails at assembly`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "TitleResolver",
                "implFqn": "com.example.TitleResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.bases.Title",
                "typeName": "Listing",
                "fieldName": "title",
                "objectSelections": {
                  "selections": "fragment SharedName on Listing { id }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts",
              "namedFragments": [ "fragment SharedName on Listing { name }" ]
            }
            """.trimIndent(),
        )
        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir())
        }
        assertTrue(exception.message!!.contains("SharedName"), exception.message)
        assertTrue(exception.message!!.contains("conflicts"), exception.message)
    }

    @Test
    fun `schema-sdl validates RSS object selections against schema - valid fields pass`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on User { id name }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val schema = schemaFile("type Query { viewer: User } type User { id: ID name: String }")
        runCli(descriptors = descriptors, out = outputDir(), schemaSdl = schema)
    }

    @Test
    fun `schema-sdl rejects RSS object selections referencing unknown field`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on User { notAField }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val schema = schemaFile("type Query { viewer: User } type User { id: ID name: String }")
        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir(), schemaSdl = schema)
        }
        assertTrue(exception.message!!.contains("RSS validation failed"), exception.message)
        // Field-undefined errors carry a hint pointing at the compilation-schema docs.
        assertTrue(exception.message!!.contains("missing field or type in your tenant's compilation schema"), exception.message)
        assertTrue(exception.message!!.contains("tenant-compilation-schemas"), exception.message)
    }

    @Test
    fun `schema-sdl validates cross-leaf named fragment spread after assembly`() {
        val descriptors = descriptorDir()
        File(descriptors, "FieldResolvers.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on User { ...UserFields }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        File(descriptors, "FragmentDefs.json").writeText(
            """{"nodes":[],"fields":[],"namedFragments":["fragment UserFields on User { id name }"]}""",
        )
        val schema = schemaFile("type Query { viewer: User } type User { id: ID name: String }")
        val out = outputDir()
        runCli(descriptors = descriptors, out = out, schemaSdl = schema)

        val json = out.resolve("$REGISTRY_RESOURCE_PATH/com.example.feature.json").readText()
        assertTrue(json.contains("fragment UserFields on User { id name }"), json)
    }

    @Test
    fun `schema-sdl rejects cross-leaf named fragment that references unknown field`() {
        val descriptors = descriptorDir()
        File(descriptors, "FieldResolvers.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on User { ...UserFields }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        File(descriptors, "FragmentDefs.json").writeText(
            """{"nodes":[],"fields":[],"namedFragments":["fragment UserFields on User { bogusField }"]}""",
        )
        val schema = schemaFile("type Query { viewer: User } type User { id: ID name: String }")
        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir(), schemaSdl = schema)
        }
        assertTrue(exception.message!!.contains("RSS validation failed"), exception.message)
    }

    @Test
    fun `schema-sdl rejects objectValueFragment on the wrong parent type`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on Photo { url }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val schema = schemaFile("type Query { viewer: User } type User { id: ID name: String } type Photo { url: String }")
        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir(), schemaSdl = schema)
        }
        assertTrue(exception.message!!.contains("must be on the parent type (User)"), exception.message)
    }

    @Test
    fun `schema-sdl rejects queryValueFragment not on the root query type`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "querySelections": {
                  "selections": "fragment _ on User { id }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val schema = schemaFile("type Query { viewer: User } type User { id: ID name: String }")
        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir(), schemaSdl = schema)
        }
        assertTrue(exception.message!!.contains("must be on the root query type (Query)"), exception.message)
    }

    @Test
    fun `schema-sdl rejects mutation resolver that sets objectValueFragment`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "CreateUserResolver",
                "implFqn": "com.example.feature.resolvers.CreateUserResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.CreateUser",
                "typeName": "Mutation",
                "fieldName": "createUser",
                "objectSelections": {
                  "selections": "fragment _ on Mutation { createUser { id } }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val schema = schemaFile(
            "type Query { viewer: User } type Mutation { createUser: User } type User { id: ID }",
        )
        val exception = assertThrows<IllegalStateException> {
            runCli(descriptors = descriptors, out = outputDir(), schemaSdl = schema)
        }
        assertTrue(exception.message!!.contains("should not set objectValueFragment"), exception.message)
    }

    @Test
    fun `schema-sdl accepts valid object and query fragments on the correct types`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on User { id }",
                  "variablesProviders": []
                },
                "querySelections": {
                  "selections": "fragment _ on Query { viewer { id } }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        val schema = schemaFile("type Query { viewer: User } type User { id: ID name: String }")
        runCli(descriptors = descriptors, out = outputDir(), schemaSdl = schema)
    }

    @Test
    fun `omitting schema-sdl skips schema validation even for invalid selections`() {
        val descriptors = descriptorDir()
        File(descriptors, "Resolver.json").writeText(
            """
            {
              "nodes": [],
              "fields": [ {
                "attribution": "NameResolver",
                "implFqn": "com.example.feature.resolvers.NameResolver",
                "isBatching": false,
                "isSelective": false,
                "resolverBaseClass": "com.example.feature.resolverbases.Name",
                "typeName": "User",
                "fieldName": "name",
                "objectSelections": {
                  "selections": "fragment _ on User { notAField }",
                  "variablesProviders": []
                }
              } ],
              "grtPackagePrefix": "viaduct.api.grts"
            }
            """.trimIndent(),
        )
        runCli(descriptors = descriptors, out = outputDir())
    }
}
