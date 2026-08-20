package viaduct.java.registry.apt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.charset.StandardCharsets
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Golden tests that run [JavaRegistryExtractorProcessor] in-process via the JDK Java compiler over
 * fixture resolver sources and assert the emitted `viaduct-registry/.../<Class>.json` descriptors.
 *
 * The fixtures stand in for the generated `@ResolverFor` bases plus hand-written `@Resolver` impls.
 * Real GRT classes are not needed: the processor reads type *names* and packages from the source's
 * type mirrors, which the compiler resolves from the (here, locally declared) GRT stubs.
 */
class JavaRegistryExtractorProcessorTest {
    private val mapper = ObjectMapper()

    @Test
    fun `emits a field resolver descriptor with the expected shape`(
        @TempDir tempDir: File
    ) {
        val descriptors = compileAndReadDescriptors(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, QUERY_RESOLVER_SOURCE)

        val json = descriptors.getValue("com/example/tenant/Resolvers.json")
        json.path("fields").shouldHaveSize(1)
        val field = json.path("fields")[0]
        field.path("typeName").asText() shouldBe "Query"
        field.path("fieldName").asText() shouldBe "greeting"
        field.path("isBatching").asBoolean().shouldBeFalse()
        field.path("isSelective").asBoolean().shouldBeFalse()
        field.path("hasArguments").asBoolean().shouldBeFalse()
        field.path("queryTypeName").asText() shouldBe "MyQuery"
        field.path("returnTypeName").asText() shouldBe "String"
        field.path("implFqn").asText() shouldBe "com.example.tenant.Resolvers\$GreetingResolver"
        field.path("resolverBaseClass").asText() shouldBe "com.example.tenant.QueryResolvers\$Greeting"
        json.path("grtPackagePrefix").asText() shouldBe "com.example.grts"
        json.has("bootstrapClass").shouldBeFalse()
    }

    @Test
    fun `emits the generated field resolver base metadata through an intermediate class`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, INDIRECT_FIELD_RESOLVER_SOURCE)

        val json = descriptors.getValue("com/example/tenant/IndirectFieldResolvers.json")
        val field = json.path("fields").single()
        field.path("typeName").asText() shouldBe "Query"
        field.path("fieldName").asText() shouldBe "greeting"
        field.path("implFqn").asText() shouldBe "com.example.tenant.IndirectFieldResolvers\$GreetingResolver"
        field.path("resolverBaseClass").asText() shouldBe "com.example.tenant.QueryResolvers\$Greeting"
        field.path("hasArguments").asBoolean().shouldBeFalse()
        field.path("queryTypeName").asText() shouldBe "MyQuery"
        field.path("returnTypeName").asText() shouldBe "String"
        json.path("grtPackagePrefix").asText() shouldBe "com.example.grts"
    }

    @Test
    fun `captures objectValueFragment and a fromArgument variable`(
        @TempDir tempDir: File
    ) {
        val descriptors = compileAndReadDescriptors(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, FRAGMENT_RESOLVER_SOURCE)

        val json = descriptors.getValue("com/example/tenant/FragResolvers.json")
        val field = json.path("fields")[0]
        field.path("hasArguments").asBoolean().shouldBeTrue()
        field.path("objectSelections").path("selections").asText() shouldBe "name"
        val variable = field.path("objectSelections").path("variablesProviders")[0]
        variable.path("kind").asText() shouldBe "fromArgument"
        variable.path("name").asText() shouldBe "v"
        variable.path("path").asText() shouldBe "limit"
    }

    @Test
    fun `captures a fromObjectField variable`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, FROM_OBJECT_FIELD_RESOLVER_SOURCE)

        val variable = descriptors.getValue("com/example/tenant/ObjFieldResolvers.json")
            .path("fields")[0].path("objectSelections").path("variablesProviders")[0]
        variable.path("kind").asText() shouldBe "fromObjectField"
        variable.path("name").asText() shouldBe "v"
        variable.path("path").asText() shouldBe "author.id"
    }

    @Test
    fun `captures queryValueFragment with a fromQueryField variable on querySelections`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, QUERY_FRAGMENT_RESOLVER_SOURCE)

        val field = descriptors.getValue("com/example/tenant/QueryFragResolvers.json").path("fields")[0]
        field.has("objectSelections").shouldBeFalse()
        field.path("querySelections").path("selections").asText() shouldBe "currentUser"
        val variable = field.path("querySelections").path("variablesProviders")[0]
        variable.path("kind").asText() shouldBe "fromQueryField"
        variable.path("name").asText() shouldBe "u"
        variable.path("path").asText() shouldBe "currentUser.id"
    }

    @Test
    fun `keeps variables on objectSelections when both object and query fragments are present`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, BOTH_FRAGMENTS_RESOLVER_SOURCE)

        val field = descriptors.getValue("com/example/tenant/BothFragResolvers.json").path("fields")[0]
        field.path("objectSelections").path("variablesProviders").shouldHaveSize(1)
        field.path("objectSelections").path("variablesProviders")[0].path("name").asText() shouldBe "v"
        field.path("querySelections").path("selections").asText() shouldBe "currentUser"
        field.path("querySelections").path("variablesProviders").shouldBeEmpty()
    }

    @Test
    fun `resolves provided variable types from a nested VariablesProvider`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, VARIABLES_PROVIDER_RESOLVER_SOURCE)

        val variable = descriptors.getValue("com/example/tenant/VarProviderResolvers.json")
            .path("fields")[0].path("objectSelections").path("variablesProviders")[0]
        variable.path("name").asText() shouldBe "limit"
        variable.path("providedVariables").path("limit").asText() shouldBe "Int!"
    }

    @Test
    fun `unwraps a List return type to its element simple name`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, LIST_RETURN_RESOLVER_BASES, LIST_RETURN_RESOLVER_SOURCE)

        val field = descriptors.getValue("com/example/tenant/ListResolvers.json").path("fields")[0]
        field.path("returnTypeName").asText() shouldBe "User"
    }

    @Test
    fun `marks isBatching and isSelective true when the field base declares them`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, BATCHING_FIELD_BASES, BATCHING_FIELD_SOURCE)

        val field = descriptors.getValue("com/example/tenant/BatchResolvers.json").path("fields")[0]
        field.path("isBatching").asBoolean().shouldBeTrue()
        field.path("isSelective").asBoolean().shouldBeTrue()
    }

    @Test
    fun `emits a node resolver descriptor`(
        @TempDir tempDir: File
    ) {
        val descriptors = compileAndReadDescriptors(tempDir, GRT_STUBS, NODE_RESOLVER_BASES, NODE_RESOLVER_SOURCE)

        val json = descriptors.getValue("com/example/tenant/NodeResolvers.json")
        json.path("nodes").shouldHaveSize(1)
        val node = json.path("nodes")[0]
        node.path("typeName").asText() shouldBe "User"
        node.path("isBatching").asBoolean().shouldBeFalse()
        node.path("resolverBaseClass").asText() shouldBe "com.example.tenant.UserNodeResolvers\$UserNode"
        json.path("grtPackagePrefix").asText() shouldBe "com.example.grts"
    }

    @Test
    fun `emits the generated node resolver base metadata through an intermediate class`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, NODE_RESOLVER_BASES, INDIRECT_NODE_RESOLVER_SOURCE)

        val json = descriptors.getValue("com/example/tenant/IndirectNodeResolvers.json")
        val node = json.path("nodes").single()
        node.path("typeName").asText() shouldBe "User"
        node.path("implFqn").asText() shouldBe "com.example.tenant.IndirectNodeResolvers\$UserNodeResolver"
        node.path("resolverBaseClass").asText() shouldBe "com.example.tenant.UserNodeResolvers\$UserNode"
        json.path("grtPackagePrefix").asText() shouldBe "com.example.grts"
    }

    @Test
    fun `marks isBatching and isSelective true when the node base declares them`(
        @TempDir tempDir: File
    ) {
        val descriptors =
            compileAndReadDescriptors(tempDir, GRT_STUBS, BATCHING_NODE_BASES, BATCHING_NODE_SOURCE)

        val node = descriptors.getValue("com/example/tenant/BatchNodeResolvers.json").path("nodes")[0]
        node.path("isBatching").asBoolean().shouldBeTrue()
        node.path("isSelective").asBoolean().shouldBeTrue()
    }

    @Test
    fun `reports an error when a node resolver declares a required selection set`(
        @TempDir tempDir: File
    ) {
        val (success, diagnostics) =
            compile(tempDir, GRT_STUBS, NODE_RESOLVER_BASES, NODE_WITH_FRAGMENT_SOURCE)

        success.shouldBeFalse()
        assertTrue(
            diagnostics.any {
                it.contains("Node resolvers do not support required selection sets")
            }
        )
    }

    @Test
    fun `reports an error when no annotated resolver base ancestor exists`(
        @TempDir tempDir: File
    ) {
        val (success, diagnostics) = compile(tempDir, MISSING_RESOLVER_BASE_SOURCE)

        success.shouldBeFalse()
        assertTrue(
            diagnostics.any {
                it.contains("must inherit from exactly one resolver base") &&
                    it.contains("none were found")
            }
        )
    }

    @Test
    fun `reports an error when multiple annotated resolver base ancestors exist`(
        @TempDir tempDir: File
    ) {
        val (success, diagnostics) = compile(tempDir, GRT_STUBS, AMBIGUOUS_RESOLVER_BASES_SOURCE)

        success.shouldBeFalse()
        assertTrue(
            diagnostics.any {
                it.contains("must inherit from exactly one resolver base") &&
                    it.contains("but found 2") &&
                    it.contains("AmbiguousResolverBases\$First") &&
                    it.contains("AmbiguousResolverBases\$Second")
            }
        )
    }

    @Test
    fun `reports an error when a variable declares no source field`(
        @TempDir tempDir: File
    ) {
        val (success, diagnostics) =
            compile(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, EMPTY_VARIABLE_RESOLVER_SOURCE)

        success.shouldBeFalse()
        assertTrue(
            diagnostics.any {
                it.contains("Variable named `v` must set exactly one")
            }
        )
    }

    @Test
    fun `reports an error when a variable declares multiple source fields`(
        @TempDir tempDir: File
    ) {
        val (success, diagnostics) =
            compile(tempDir, GRT_STUBS, QUERY_RESOLVER_BASES, MULTIPLE_SOURCE_VARIABLE_RESOLVER_SOURCE)

        success.shouldBeFalse()
        assertTrue(
            diagnostics.any {
                it.contains("Variable named `v` must set exactly one") &&
                    it.contains("fromObjectField=author.id") &&
                    it.contains("fromArgument=limit")
            }
        )
    }

    @Test
    fun `emits named fragments and typed operations from a document-only source file`(
        @TempDir tempDir: File
    ) {
        val descriptors = compileAndReadDescriptors(tempDir, GRT_STUBS, DOCUMENTS_SOURCE)

        val json = descriptors.getValue("com/example/tenant/Documents.json")
        json.path("fields").shouldBeEmpty()
        json.path("nodes").shouldBeEmpty()
        json.path("grtPackagePrefix").asText() shouldBe "com.example.grts"

        val fragment = json.path("namedFragments").single()
        fragment.path("text").asText() shouldBe "fragment UserFields on User { id }"
        fragment.path("grtTypeName").asText() shouldBe "User"

        json.path("namedOperations").shouldHaveSize(2)
        val query = json.path("namedOperations")[0]
        query.path("implFqn").asText() shouldBe "com.example.tenant.Documents\$EchoQuery"
        query.path("kind").asText() shouldBe "QUERY"
        query.path("text").asText() shouldBe "query(\$value: String!) { echo(value: \$value) }"
        val mutation = json.path("namedOperations")[1]
        mutation.path("implFqn").asText() shouldBe "com.example.tenant.Documents\$RecordMutation"
        mutation.path("kind").asText() shouldBe "MUTATION"
    }

    @Test
    fun `reports an error when GraphQLOperation does not extend an operation base`(
        @TempDir tempDir: File
    ) {
        val (success, diagnostics) = compile(tempDir, INVALID_OPERATION_SOURCE)

        success.shouldBeFalse()
        assertTrue(
            diagnostics.any {
                it.contains("must extend QueryFromAnnotation or MutationFromAnnotation")
            }
        )
    }

    // ── Compilation harness ───────────────────────────────────────────────────

    private fun compileAndReadDescriptors(
        tempDir: File,
        vararg sources: SourceFile,
    ): Map<String, JsonNode> {
        val classOutput = File(tempDir, "classes").apply { mkdirs() }
        val (success, diagnostics) = runProcessor(classOutput, sources)
        assertTrue(success) { "annotation processing/compilation failed:\n" + diagnostics.joinToString("\n") }

        val registryRoot = File(classOutput, DESCRIPTOR_ROOT)
        if (!registryRoot.exists()) return emptyMap()
        return registryRoot.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .associate { file ->
                file.relativeTo(registryRoot).path.replace(File.separatorChar, '/') to
                    mapper.readTree(file.readText())
            }
    }

    /** Compiles [sources] without asserting success, returning the compilation result and diagnostics. */
    private fun compile(
        tempDir: File,
        vararg sources: SourceFile,
    ): Pair<Boolean, List<String>> {
        val classOutput = File(tempDir, "classes").apply { mkdirs() }
        return runProcessor(classOutput, sources)
    }

    private fun runProcessor(
        classOutput: File,
        sources: Array<out SourceFile>,
    ): Pair<Boolean, List<String>> {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(classOutput))

        val units: List<JavaFileObject> = sources.map { it.toFileObject() }
        val diagnostics = javax.tools.DiagnosticCollector<JavaFileObject>()
        val task = compiler.getTask(null, fileManager, diagnostics, listOf("-proc:full"), null, units)
        task.setProcessors(listOf(JavaRegistryExtractorProcessor()))
        val success = task.call()
        return success to diagnostics.diagnostics.map { it.toString() }
    }

    private data class SourceFile(val fqn: String, val content: String) {
        fun toFileObject(): JavaFileObject =
            object : SimpleJavaFileObject(
                java.net.URI.create("string:///" + fqn.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE,
            ) {
                override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = content
            }
    }

    private companion object {
        // Minimal GRT stubs. Real Viaduct API types (FieldResolverBase, annotations, …) are on the
        // test classpath; only the schema-specific GRTs need stubbing to keep the test hermetic.
        val GRT_STUBS = SourceFile(
            "com.example.grts.Grts",
            """
            package com.example.grts;

            import viaduct.java.api.types.Query;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.types.NodeObject;

            public final class Grts {
                public interface MyQuery extends Query {}
                public interface Query_Greeting_Arguments extends Arguments {}
                public interface User extends NodeObject {}
            }
            """.trimIndent(),
        )

        // A field resolver base whose return type (T) is List<User>, exercising the List-unwrapping
        // path that reports the element's simple name as the returnTypeName.
        val LIST_RETURN_RESOLVER_BASES = SourceFile(
            "com.example.tenant.ListResolverBases",
            """
            package com.example.tenant;

            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.ResolverFor;
            import viaduct.java.api.resolvers.FieldResolverBase;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.types.CompositeOutput;
            import com.example.grts.Grts;

            public final class ListResolverBases {
                @ResolverFor(typeName = "Query", fieldName = "users", isSelective = false)
                public abstract static class Users
                    implements FieldResolverBase<List<Grts.User>, Grts.MyQuery, Grts.MyQuery, Arguments.NoArguments, CompositeOutput> {
                    public static final class Context {}
                    public abstract CompletableFuture<List<Grts.User>> resolve(Context ctx);
                }
            }
            """.trimIndent(),
        )

        // A field resolver base with isBatching = true and isSelective = true on @ResolverFor.
        val BATCHING_FIELD_BASES = SourceFile(
            "com.example.tenant.BatchResolverBases",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.ResolverFor;
            import viaduct.java.api.resolvers.FieldResolverBase;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.types.CompositeOutput;
            import com.example.grts.Grts;

            public final class BatchResolverBases {
                @ResolverFor(typeName = "Query", fieldName = "batched", isSelective = true, isBatching = true)
                public abstract static class Batched
                    implements FieldResolverBase<String, Grts.MyQuery, Grts.MyQuery, Arguments.NoArguments, CompositeOutput> {
                    public static final class Context {}
                    public abstract CompletableFuture<String> resolve(Context ctx);
                }
            }
            """.trimIndent(),
        )

        // A node resolver base with isBatching = true and isSelective = true on @NodeResolverFor.
        val BATCHING_NODE_BASES = SourceFile(
            "com.example.tenant.BatchNodeResolverBases",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.NodeResolverFor;
            import viaduct.java.api.resolvers.NodeResolverBase;
            import com.example.grts.Grts;

            public final class BatchNodeResolverBases {
                @NodeResolverFor(typeName = "User", isBatching = true, isSelective = true)
                public abstract static class UserNode implements NodeResolverBase<Grts.User> {
                    public static final class Context {}
                    public abstract CompletableFuture<Grts.User> resolve(Context ctx);
                }
            }
            """.trimIndent(),
        )

        // Generated-style field resolver base: @ResolverFor with FieldResolverBase<T,O,Q,A,S> and a
        // nested Context. Greeting has no arguments (Arguments.NoArguments); Frag has an Arguments GRT.
        val QUERY_RESOLVER_BASES = SourceFile(
            "com.example.tenant.QueryResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.ResolverFor;
            import viaduct.java.api.resolvers.FieldResolverBase;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.types.CompositeOutput;
            import com.example.grts.Grts;

            // Stand-in for generated bases: the APT only reads @ResolverFor + the FieldResolverBase
            // type arguments, so the nested Context can be a plain placeholder class.
            public final class QueryResolvers {
                @ResolverFor(typeName = "Query", fieldName = "greeting", isSelective = false)
                public abstract static class Greeting
                    implements FieldResolverBase<String, Grts.MyQuery, Grts.MyQuery, Arguments.NoArguments, CompositeOutput> {
                    public static final class Context {}
                    public abstract CompletableFuture<String> resolve(Context ctx);
                }

                @ResolverFor(typeName = "Query", fieldName = "frag", isSelective = false)
                public abstract static class Frag
                    implements FieldResolverBase<String, Grts.MyQuery, Grts.MyQuery, Grts.Query_Greeting_Arguments, CompositeOutput> {
                    public static final class Context {}
                    public abstract CompletableFuture<String> resolve(Context ctx);
                }
            }
            """.trimIndent(),
        )

        val NODE_RESOLVER_BASES = SourceFile(
            "com.example.tenant.UserNodeResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.NodeResolverFor;
            import viaduct.java.api.resolvers.NodeResolverBase;
            import com.example.grts.Grts;

            public final class UserNodeResolvers {
                @NodeResolverFor(typeName = "User")
                public abstract static class UserNode implements NodeResolverBase<Grts.User> {
                    public static final class Context {}
                    public abstract CompletableFuture<Grts.User> resolve(Context ctx);
                }
            }
            """.trimIndent(),
        )

        val QUERY_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.Resolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;

            public final class Resolvers {
                @Resolver
                public static class GreetingResolver extends QueryResolvers.Greeting {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("hi");
                    }
                }
            }
            """.trimIndent(),
        )

        val INDIRECT_FIELD_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.IndirectFieldResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;

            public final class IndirectFieldResolvers {
                public abstract static class SharedResolver extends QueryResolvers.Greeting {}

                @Resolver
                public static class GreetingResolver extends SharedResolver {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("hi");
                    }
                }
            }
            """.trimIndent(),
        )

        val FRAGMENT_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.FragResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.Variable;

            public final class FragResolvers {
                @Resolver(
                    objectValueFragment = "name",
                    variables = { @Variable(name = "v", fromArgument = "limit") }
                )
                public static class WithFrag extends QueryResolvers.Frag {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }
                }
            }
            """.trimIndent(),
        )

        val FROM_OBJECT_FIELD_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.ObjFieldResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.Variable;

            public final class ObjFieldResolvers {
                @Resolver(
                    objectValueFragment = "author { id }",
                    variables = { @Variable(name = "v", fromObjectField = "author.id") }
                )
                public static class WithObjField extends QueryResolvers.Frag {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }
                }
            }
            """.trimIndent(),
        )

        val QUERY_FRAGMENT_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.QueryFragResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.Variable;

            public final class QueryFragResolvers {
                @Resolver(
                    queryValueFragment = "currentUser",
                    variables = { @Variable(name = "u", fromQueryField = "currentUser.id") }
                )
                public static class WithQueryFrag extends QueryResolvers.Frag {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }
                }
            }
            """.trimIndent(),
        )

        val BOTH_FRAGMENTS_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.BothFragResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.Variable;

            public final class BothFragResolvers {
                @Resolver(
                    objectValueFragment = "name",
                    queryValueFragment = "currentUser",
                    variables = { @Variable(name = "v", fromArgument = "limit") }
                )
                public static class WithBothFrags extends QueryResolvers.Frag {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }
                }
            }
            """.trimIndent(),
        )

        val EMPTY_VARIABLE_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.EmptyVarResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.Variable;

            public final class EmptyVarResolvers {
                @Resolver(
                    objectValueFragment = "name",
                    variables = { @Variable(name = "v") }
                )
                public static class WithEmptyVar extends QueryResolvers.Frag {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }
                }
            }
            """.trimIndent(),
        )

        val MULTIPLE_SOURCE_VARIABLE_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.MultipleSourceVarResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.Variable;

            public final class MultipleSourceVarResolvers {
                @Resolver(
                    objectValueFragment = "author { id }",
                    variables = {
                        @Variable(
                            name = "v",
                            fromObjectField = "author.id",
                            fromArgument = "limit"
                        )
                    }
                )
                public static class WithMultipleSourceVar extends QueryResolvers.Frag {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }
                }
            }
            """.trimIndent(),
        )

        // A nested @Variables-annotated VariablesProvider supplies the GraphQL types for the
        // variables referenced from the fragment, exercising the variablesTypeMap discovery path.
        val VARIABLES_PROVIDER_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.VarProviderResolvers",
            """
            package com.example.tenant;

            import java.util.Map;
            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.Variable;
            import viaduct.java.api.annotations.Variables;
            import viaduct.java.api.context.VariablesProviderContext;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.variables.VariablesProvider;

            public final class VarProviderResolvers {
                @Resolver(
                    objectValueFragment = "posts(limit: ${'$'}limit) { id }",
                    variables = { @Variable(name = "limit", fromArgument = "max") }
                )
                public static class WithProvider extends QueryResolvers.Frag {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }

                    @Variables(types = "limit: Int!")
                    public static class LimitProvider implements VariablesProvider<Arguments.NoArguments> {
                        @Override
                        public CompletableFuture<Map<String, Object>> provide(
                            VariablesProviderContext<Arguments.NoArguments> ctx) {
                            return CompletableFuture.completedFuture(Map.of("limit", 10));
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val LIST_RETURN_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.ListResolvers",
            """
            package com.example.tenant;

            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import com.example.grts.Grts;

            public final class ListResolvers {
                @Resolver
                public static class UsersResolver extends ListResolverBases.Users {
                    @Override
                    public CompletableFuture<List<Grts.User>> resolve(Context ctx) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            }
            """.trimIndent(),
        )

        val BATCHING_FIELD_SOURCE = SourceFile(
            "com.example.tenant.BatchResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;

            public final class BatchResolvers {
                @Resolver
                public static class BatchedResolver extends BatchResolverBases.Batched {
                    @Override
                    public CompletableFuture<String> resolve(Context ctx) {
                        return CompletableFuture.completedFuture("x");
                    }
                }
            }
            """.trimIndent(),
        )

        val BATCHING_NODE_SOURCE = SourceFile(
            "com.example.tenant.BatchNodeResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import com.example.grts.Grts;

            public final class BatchNodeResolvers {
                @Resolver
                public static class BatchedUserNodeResolver extends BatchNodeResolverBases.UserNode {
                    @Override
                    public CompletableFuture<Grts.User> resolve(Context ctx) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            }
            """.trimIndent(),
        )

        val NODE_WITH_FRAGMENT_SOURCE = SourceFile(
            "com.example.tenant.BadNodeResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import com.example.grts.Grts;

            public final class BadNodeResolvers {
                @Resolver(objectValueFragment = "id")
                public static class UserNodeResolver extends UserNodeResolvers.UserNode {
                    @Override
                    public CompletableFuture<Grts.User> resolve(Context ctx) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            }
            """.trimIndent(),
        )

        val MISSING_RESOLVER_BASE_SOURCE = SourceFile(
            "com.example.tenant.MissingResolverBase",
            """
            package com.example.tenant;

            import viaduct.java.api.annotations.Resolver;

            @Resolver
            public final class MissingResolverBase {}
            """.trimIndent(),
        )

        val AMBIGUOUS_RESOLVER_BASES_SOURCE = SourceFile(
            "com.example.tenant.AmbiguousResolverBases",
            """
            package com.example.tenant;

            import viaduct.java.api.annotations.Resolver;
            import viaduct.java.api.annotations.ResolverFor;
            import viaduct.java.api.resolvers.FieldResolverBase;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.types.CompositeOutput;
            import com.example.grts.Grts;

            public final class AmbiguousResolverBases {
                @ResolverFor(typeName = "Query", fieldName = "first", isSelective = false)
                public interface First
                    extends FieldResolverBase<String, Grts.MyQuery, Grts.MyQuery, Arguments.NoArguments, CompositeOutput> {}

                @ResolverFor(typeName = "Query", fieldName = "second", isSelective = false)
                public interface Second
                    extends FieldResolverBase<String, Grts.MyQuery, Grts.MyQuery, Arguments.NoArguments, CompositeOutput> {}

                @Resolver
                public abstract static class AmbiguousResolver implements First, Second {}
            }
            """.trimIndent(),
        )

        val DOCUMENTS_SOURCE = SourceFile(
            "com.example.tenant.Documents",
            """
            package com.example.tenant;

            import com.example.grts.Grts;
            import viaduct.java.api.annotations.GraphQLFragment;
            import viaduct.java.api.annotations.GraphQLOperation;
            import viaduct.java.api.documents.FragmentFromAnnotation;
            import viaduct.java.api.documents.MutationFromAnnotation;
            import viaduct.java.api.documents.QueryFromAnnotation;

            public final class Documents {
                @GraphQLFragment("fragment UserFields on User { id }")
                public static final class UserFields extends FragmentFromAnnotation<Grts.User> {}

                @GraphQLOperation("query(${ '$' }value: String!) { echo(value: ${ '$' }value) }")
                public static final class EchoQuery extends QueryFromAnnotation {}

                @GraphQLOperation("mutation { record }")
                public static final class RecordMutation extends MutationFromAnnotation {}
            }
            """.trimIndent(),
        )

        val INVALID_OPERATION_SOURCE = SourceFile(
            "com.example.tenant.InvalidOperation",
            """
            package com.example.tenant;

            import viaduct.java.api.annotations.GraphQLOperation;

            @GraphQLOperation("{ echo }")
            public final class InvalidOperation {}
            """.trimIndent(),
        )

        val NODE_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.NodeResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import com.example.grts.Grts;

            public final class NodeResolvers {
                @Resolver
                public static class UserNodeResolver extends UserNodeResolvers.UserNode {
                    @Override
                    public CompletableFuture<Grts.User> resolve(Context ctx) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            }
            """.trimIndent(),
        )

        val INDIRECT_NODE_RESOLVER_SOURCE = SourceFile(
            "com.example.tenant.IndirectNodeResolvers",
            """
            package com.example.tenant;

            import java.util.concurrent.CompletableFuture;
            import viaduct.java.api.annotations.Resolver;
            import com.example.grts.Grts;

            public final class IndirectNodeResolvers {
                public abstract static class SharedResolver extends UserNodeResolvers.UserNode {}

                @Resolver
                public static class UserNodeResolver extends SharedResolver {
                    @Override
                    public CompletableFuture<Grts.User> resolve(Context ctx) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            }
            """.trimIndent(),
        )
    }
}
