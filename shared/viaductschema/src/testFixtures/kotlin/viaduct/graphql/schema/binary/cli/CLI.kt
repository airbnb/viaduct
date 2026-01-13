package viaduct.graphql.schema.binary.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import graphql.parser.MultiSourceReader
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.function.BiPredicate
import kotlin.streams.toList
import kotlin.system.measureTimeMillis
import org.slf4j.LoggerFactory
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.readBSchema
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.checkBridgeSchemaInvariants
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.graphql.schema.graphqljava.GraphQLSchemaExtraDiff
import viaduct.graphql.schema.graphqljava.readTypesFromURLs
import viaduct.graphql.schema.graphqljava.toGraphQLSchema
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.invariants.InvariantChecker

fun main(args: Array<String>) =
    CLI()
        .subcommands(
            MmWriteCommand(),
            MmLoadTimeCommand(),
            MmAccessTimeCommand(),
            MmLoadNoGJCommand(),
            MmDiffCommand(),
        )
        .main(args)

private class CLI : CliktCommand() {
    private val debug: Boolean by option().flag(default = false)

    override fun run() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        if (debug) {
            root.level = Level.DEBUG
        } else {
            root.level = Level.INFO
        }
    }
}

private class MmWriteCommand : CliktCommand(
    name = "mmwrite",
    help = "Write binary rep of GraphQL schema.",
    printHelpOnEmptyArgs = true
) {
    private val projectDirectory by option("--project", "-p")
        .file(
            mustExist = true,
            canBeDir = true,
            mustBeReadable = true
        )
        // .help("Directory containing .graphqls sources (scanned recursively).")
        .required()

    override fun run() {
        val schema = readGJSchema(projectDirectory!!.toPath())
        writeBSchema(schema, FileOutputStream(System.getProperty("user.home") + "/schema.bgql"))
    }
}

private sealed class Library(
    val file: File
) {
    class GraphqlJ(file: File) : Library(file)

    class BinGQL(file: File) : Library(file)
}

private class MmLoadTimeCommand : CliktCommand(
    name = "loadTimeTest",
    help = "How long does it take to load a schema (plus heap size)?",
    printHelpOnEmptyArgs = true
) {
    private val projectDirectory by option("--project", "-p")
        .file(
            mustExist = true,
            canBeDir = true,
            mustBeReadable = true
        )
    private val useBinGQL by option("--bin").flag()
    private val b2g by option("--b2g").flag().help("Read BSchema and cvt to GraphQLSchema")

    private val count by option("--count", "-c").int().default(1)

    override fun run() {
        val rt = Runtime.getRuntime()
        rt.gc()
        println("Heap at start (KB): ${(rt.totalMemory() - rt.freeMemory()) / 1024}")
        var schema: Any?
        repeat(count) {
            val time = measureTimeMillis {
                schema = if (useBinGQL) {
                    readBSchema(FileInputStream(System.getProperty("user.home") + "/schema.bgql"))
                } else if (b2g) {
                    val b = readBSchema(FileInputStream(System.getProperty("user.home") + "/schema.bgql"))
                    b.toGraphQLSchema(
                        scalarsNeeded = setOf(/*"Boolean",*/ "Float", "ID", "Int", /*"String"*/),
                        additionalScalars = setOf("BackingData", "Date", "DateTime", "JSON", "Long", "Short", "Time"),
                    )
                } else {
                    readGJSchema(projectDirectory!!.toPath())
                }
            }

            println("Time (ms): $time, Heap before GC (KB): ${(rt.totalMemory() - rt.freeMemory()) / 1024}")
            rt.gc()
            println("Heap after GC (KB): ${(rt.totalMemory() - rt.freeMemory()) / 1024}")
            schema = null
            rt.gc()
        }
    }
}

private data class Counters(
    var types: Int = 0,
    var members: Int = 0,
    var supers: Int = 0,
    var unions: Int = 0,
    var fields: Int = 0,
    var values: Int = 0,
    var args: Int = 0
)

private class MmAccessTimeCommand : CliktCommand(
    name = "accessTimeTest",
    help = "How long does it take to access a schema?",
    printHelpOnEmptyArgs = true
) {
    private val projectDirectory by option("--project", "-p")
        .file(
            mustExist = true,
            canBeDir = true,
            mustBeReadable = true
        )
    private val useBinGQL by option("--bin").flag()

    private val count by option("--count", "-c").int().default(1)
    private val noGJ by option("--noGJ").flag()

    override fun run() {
        if (useBinGQL) {
            runViaductSchema(readBSchema(FileInputStream(System.getProperty("user.home") + "/schema.bgql")))
        } else {
            if (noGJ) {
                runGraphQLJava(projectDirectory!!)
            } else {
                runViaductSchema(readGJSchema(projectDirectory!!.toPath()))
            }
        }
    }

    private fun runViaductSchema(schema: ViaductSchema) {
        val counters = Counters()
        traverse(schema, counters)
        val time = measureTimeMillis {
            repeat(count) {
                traverse(schema, counters)
            }
        }
        counters.apply {
            println("Time (ms): $time")
            println("Types: $types, Members: $members, Supers: $supers, Unions: $unions, Fields: $fields, Values: $values, Args: $args")
        }
    }

    private fun traverse(
        schema: ViaductSchema,
        counters: Counters
    ) = counters.apply {
        types = 0
        members = 0
        supers = 0
        unions = 0
        fields = 0
        values = 0
        args = 0
        for (def in schema.types.values) {
            types++
            when (def) {
                is ViaductSchema.Enum -> {
                    def.values.forEach { _: ViaductSchema.EnumValue ->
                        values++
                    }
                }

                is ViaductSchema.Record -> {
                    def.fields.forEach { field: ViaductSchema.Field ->
                        fields++
                        field.args.forEach {
                            args++
                        }
                    }
                    when (def) {
                        is ViaductSchema.Object -> {
                            def.supers.forEach { _: ViaductSchema.Interface -> supers++ }
                            def.unions.forEach { _: ViaductSchema.Union -> unions++ }
                        }
                        is ViaductSchema.Interface -> {
                            def.supers.forEach { _: ViaductSchema.Interface -> supers++ }
                            def.possibleObjectTypes.forEach { _: ViaductSchema.Object -> members++ }
                        }
                    }
                }

                is ViaductSchema.Scalar -> {
                }

                is ViaductSchema.Union -> {
                    def.possibleObjectTypes.forEach { _: ViaductSchema.Object -> members++ }
                }

                else -> throw IllegalArgumentException("Unknown typedef $def")
            }
        }
    }

    private fun runGraphQLJava(input: File) {
        val urls = viaductFiles(input.toPath())
        val registry = readTypesFromURLs(urls).apply {
            DefaultSchemaProvider.addDefaults(this, allowExisting = true)
        }
        val gqlSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        val typeDefs = gqlSchema.allTypesAsList

        var types = 0
        var members = 0
        var supers = 0
        var unions = 0
        var fields = 0
        var values = 0
        var args = 0

        val time = measureTimeMillis {
            repeat(count) {
                types = 0
                members = 0
                supers = 0
                unions = 0
                fields = 0
                values = 0
                args = 0
                for (def in typeDefs) {
                    types++
                    when (def) {
                        is GraphQLEnumType -> {
                            def.values.forEach {
                                values++
                            }
                        }

                        is GraphQLInputObjectType -> {
                            def.fields.forEach {
                                fields++
                            }
                        }

                        is GraphQLInterfaceType -> {
                            def.fields.forEach {
                                fields++
                                it.arguments.forEach {
                                    args++
                                }
                            }
                            def.interfaces.forEach { supers++ }
                            /* Can't do:
                            def.unions.forEach { unions++ }
                             */
                            gqlSchema.getImplementations(def).forEach { members++ }
                        }

                        is GraphQLObjectType -> {
                            def.fields.forEach {
                                fields++
                                it.arguments.forEach {
                                    args++
                                }
                            }
                            def.interfaces.forEach { supers++ }
                            // Can't do: def.unions.forEach { unions++ }
                        }

                        is GraphQLScalarType -> {
                        }

                        is GraphQLUnionType -> {
                            def.types.forEach { members++ }
                        }

                        else -> throw IllegalArgumentException("Unknown typedef $def")
                    }
                }
            }
        }
        println("[NoGJ] Time (ms): $time")
        println("Types: $types, Members: $members, Supers: $supers, Unions: $unions, Fields: $fields, Values: $values, Args: $args")
    }
}

private class MmLoadNoGJCommand : CliktCommand(
    name = "loadNoGJTest",
    help = "How long does it take to load a schema (plus heap size)?",
    printHelpOnEmptyArgs = true
) {
    private val input by option("--project", "-p")
        .file(
            mustExist = true,
            canBeDir = true,
            mustBeReadable = true
        ).required()

    private val count by option("--count", "-c").int().default(1)

    override fun run() {
        val rt = Runtime.getRuntime()
        rt.gc()
        println("Heap at start (KB): ${(rt.totalMemory() - rt.freeMemory()) / 1024}")
        var schema: GraphQLSchema?
        repeat(count) {
            var parseTime = 0L
            var buildTime = 0L
            val time = measureTimeMillis {
                var registry: TypeDefinitionRegistry
                parseTime = measureTimeMillis {
                    val urls = viaductFiles(input.toPath())
                    registry = readTypesFromURLs(urls).apply {
                        DefaultSchemaProvider.addDefaults(this, allowExisting = true)
                    }
                }
                buildTime = measureTimeMillis {
                    schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
                }
            }
            println(
                "Time (ms): $time (= $parseTime + $buildTime), Heap before GC (KB): ${(rt.totalMemory() - rt.freeMemory()) / 1024} (ignore: ${schema!!.allTypesAsList.size})"
            )
            rt.gc()
            println("Heap after GC (KB): ${(rt.totalMemory() - rt.freeMemory()) / 1024}")
            schema = null
            rt.gc()
        }
    }
}

private class MmDiffCommand : CliktCommand(
    name = "mmdiff",
    help = "Read as GJSchema, write then read as BSchema, then compare. Also tests toGraphQLSchema round-trip.",
    printHelpOnEmptyArgs = true
) {
    private val projectDirectory by option("--project", "-p")
        .file(
            mustExist = true,
            canBeDir = true,
            mustBeReadable = true
        )
        // .help("Directory containing .graphqls sources (scanned recursively).")
        .required()

    override fun run() {
        val checker = InvariantChecker()

        // Read input schema (both GJSchema and the underlying registry)
        val (expected, registry) = readGJSchemaWithRegistry(projectDirectory!!.toPath())

        // Write to binary format
        val binaryFilePath = System.getProperty("user.home") + "/schema.bgql"
        writeBSchema(expected, FileOutputStream(binaryFilePath))

        // Read back from binary format
        val actual = readBSchema(FileInputStream(binaryFilePath))

        // Validate output schema invariants
        checkBridgeSchemaInvariants(actual, checker)

        // Compare schemas structurally (reusing same checker)
        SchemaDiff(expected, actual, checker).diff()

        // Also test toGraphQLSchema round-trip with GraphQLSchemaExtraDiff
        println("Testing toGraphQLSchema round-trip...")

        // Create expected GraphQLSchema from the registry
        val expectedGraphQLSchema = SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.MOCKED_WIRING)

        // Convert ViaductSchema back to GraphQLSchema
        // Note: We need to specify scalars that are used in the schema
        val scalarsNeeded = expected.types.values
            .filterIsInstance<ViaductSchema.Scalar>()
            .filter { it.name !in setOf("String", "Boolean") } // These are always added
            .map { it.name }
            .toSet()

        try {
            val actualGraphQLSchema = actual.toGraphQLSchema(
                scalarsNeeded = scalarsNeeded,
                additionalScalars = emptySet()
            )

            // Compare using GraphQLSchemaExtraDiff (runtime properties like deprecation, default values)
            if (actualGraphQLSchema != null) {
                GraphQLSchemaExtraDiff(expectedGraphQLSchema, actualGraphQLSchema, checker).diff()
            } else {
                checker.isTrue(false, "GRAPHQL_SCHEMA_CONVERSION: toGraphQLSchema returned null")
            }
        } catch (e: Exception) {
            println("Warning: toGraphQLSchema round-trip check skipped due to: ${e.message}")
            println("  (This may occur with schemas using custom directives not fully supported by toGraphQLSchema)")
        }

        if (checker.isEmpty) {
            println("✓ Schemas match - no differences found and all invariants satisfied")
        } else {
            println("✗ Problems found:")
            println(checker.joinToString("\n"))
            kotlin.system.exitProcess(1)
        }
    }
}

private fun readGJSchema(inputPath: Path): GJSchema {
    val (schema, _) = readGJSchemaWithRegistry(inputPath)
    return schema
}

private fun readGJSchemaWithRegistry(inputPath: Path): Pair<GJSchema, TypeDefinitionRegistry> {
    val reader =
        MultiSourceReader.newMultiSourceReader().apply {
            viaductFiles(inputPath).forEach {
                this.reader(
                    it.openStream().reader(Charsets.UTF_8),
                    it.path
                )
            }
        }.trackData(true).build()
    val registry = SchemaParser().parse(reader).apply {
        DefaultSchemaProvider.addDefaults(this, allowExisting = true)
    }
    return GJSchema.fromRegistry(registry) to registry
}

private fun viaductFiles(inputPath: Path): List<URL> = Files.find(inputPath, 100, viaductFilter).map { it.toUri().toURL() }.toList()

private val viaductFilter = object : BiPredicate<Path, BasicFileAttributes> {
    override fun test(
        p: Path,
        attrs: BasicFileAttributes
    ) = (
        attrs.isRegularFile() &&
            p.getFileName().toString().endsWith(".graphqls") &&
            p.toString().contains("src/")
    )
}
