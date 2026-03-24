package viaduct.graphql.schema.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.function.BiPredicate
import kotlin.streams.toList
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.unparseWrappers
import viaduct.graphql.utils.DefaultSchemaFactory

/**
 * Command to output attributes about types and fields into CSV files for
 * easy analysis.
 *
 * The output files are (columns are described below):
 *
 * - types.csv: one row per type, no directive info
 * - typedirs.csv: one row per directive per type
 * - fields.csv: one row per field, no directive info
 * - fielddirs.csv: one row per directive per field
 *
 * The *dirs.csv files have one line for every directive applied
 * for every type/field in the schema. If a type or field has two
 * directives applied to it, then it will have two lines in the
 * corresponding *dirs.csv file. If a type or field has no directives,
 * it will still appear with "NONE,NONE" for the DirectiveName and
 * AppliedDirective columns.
 */
class Schema2CsvCommand : CliktCommand(
    name = "schema2csv",
    help = """
       Output attributes about types and fields into CSV files for
       easy analysis.  The output files are (columns are described
       below):

       - types.csv: one row per type, no directive info
       - typedirs.csv: one row per directive per type
       - fields.csv: one row per field, no directive info
       - fielddirs.csv: one row per directive per field

       The *dirs.csv files have one line for every directive applied
       for every types in the schema.  That is, if a type has two
       directives applied to it, then it will have two lines in
       typedirs.csv.  If a type or field have no directives on them,
       they will still appear in the *dirs.csv file, with "NONE,NONE"
       for the DirectiveName and AppliedDirective columns.

       Here are the initial columns in all files (in order):

       - Tenant: the module path for the type's source file. This is
       extracted as everything before "/src/" in the source file path,
       with the longest common prefix across all source files stripped
       to produce cleaner relative paths. For example, if all source
       files share a prefix like "/home/user/repo/modules/", that prefix
       is removed, leaving paths like "data/user" instead of
       "/home/user/repo/modules/data/user". Returns "NO_TENANT" if the
       source location doesn't match the expected pattern.

       - Kind: the kind of type, one of ENUM, INPUT, INTERFACE,
       SCALAR, OBJECT, or UNION.

       - IsNode: true iff the type implements Node.

       - Type: the name of the type.

       These columns are only in the field*.csv files:

       - Extension: true iff this field has been defined in an
       inter-module extension, i.e., in a type-extension appearing in
       a module different from the type containing the field.

       - FieldTenant: module path for the field's definition, similar
       to Tenant but for the field's containing extension rather than
       the type's base definition. Extension can be true even when
       Tenant and FieldTenant are different, because a field might be
       defined in an extension in the same module as the type itself.
       Extension is true only for inter-module extensions.

       - IsRoot: true iff this field is a root operation.  For
       Mutation and Subscription, this means the field is a field of
       those types.  For Query, the definition of root is extended to
       include argument-free fields of singletons.

       - Field: the name of the field.

       - FieldBaseTenant: module path for the field's base type
       ("base-type" means the type stripped of all [] and ! wrappers).

       - FieldBaseKind: similar to Kind, but for base-type of the field.

       - IsExternal: true iff the base-type of a field is defined in
       a module different from the module defining the field.

       - FieldBase: the name of the base-type of the field.

       - FieldWrappers: the wrappers on the field type.  This is a
       string of "?" and "!" characters where each character indicates
       nullability at one depth level, read left-to-right from outer
       to inner.  "?" means nullable, "!" means non-null.  The length
       equals FieldListDepth + 1.  Examples: "?" = nullable scalar,
       "!" = non-null scalar, "??" = nullable list of nullable,
       "!!" = non-null list of non-null.

       - FieldListDepth: how many list-wrappers the field's type has
       around its base type.

       These are the last two columns of *dirs.csv files:

       - DirectiveName: recall there is one row per directive.  This
       column gives the name of the directive for this line, e.g., if
       the directive was "@owners(...)", DirectiveName will be
       "owners".  Iff a type has no directives on it, then we will
       insert a row for the type where the directive name is NONE (and
       AppliedDirective is null).

       - AppliedDirective: this is the full text of the directive,
       including arguments, associated with this row, e.g.,
       "@otherData" or "@privacy(delegateToParent:true)".
    """.trimIndent(),
    printHelpOnEmptyArgs = true
) {
    private val projectDirectory by option("--project", "-p")
        .file(
            mustExist = true,
            canBeDir = true,
            mustBeReadable = true
        )
        .required()

    private val outputPath by option("--output", "-o")
        .file(
            mustExist = true,
            canBeDir = true,
            mustBeWritable = true
        )
        .default(File("."))

    override fun run() {
        val schema = loadSchema(projectDirectory.toPath())
        val (types, fields) = makeTables(schema)
        types.writeTo(outputPath)
        fields.writeTo(outputPath)
        echo("Wrote ${types.baseName}s.csv, ${types.baseName}dirs.csv, ${fields.baseName}s.csv, ${fields.baseName}dirs.csv to $outputPath")
    }

    private fun loadSchema(inputPath: Path): ViaductSchema {
        val urls = findGraphQLFiles(inputPath)
        val reader = MultiSourceReader.newMultiSourceReader().apply {
            urls.forEach {
                this.reader(it.openStream().reader(Charsets.UTF_8), it.path)
            }
        }.trackData(true).build()

        val registry = SchemaParser().parse(reader).apply {
            DefaultSchemaFactory.addDefaults(this, allowExisting = true)
        }

        return ViaductSchema.fromTypeDefinitionRegistry(registry)
    }

    private fun findGraphQLFiles(inputPath: Path): List<URL> =
        Files.find(inputPath, 100, GRAPHQL_FILE_FILTER)
            .map { it.toUri().toURL() }
            .toList()

    private fun makeTables(schema: ViaductSchema): Pair<CsvTable, CsvTable> = generateCsvTables(schema)

    companion object {
        private val GRAPHQL_FILE_FILTER = object : BiPredicate<Path, BasicFileAttributes> {
            override fun test(
                p: Path,
                attrs: BasicFileAttributes
            ) = attrs.isRegularFile() &&
                p.fileName.toString().endsWith(".graphqls") &&
                p.toString().contains("src/")
        }
    }
}

// ========== CSV Formatting Utilities ==========

/**
 * Regex pattern for finding double-quote characters that need escaping in CSV.
 */
val CSV_QUOTE_PATTERN = Regex("[\"]")

/**
 * Escapes a string for CSV output by doubling any double-quote characters.
 */
fun escapeForCsv(text: String): String = CSV_QUOTE_PATTERN.replace(text, "\"\"")

/**
 * Formats a single applied directive for CSV output.
 * Returns a string in the format: DirectiveName,"@directiveName(args...)"
 */
fun formatDirectiveForCsv(appliedDir: ViaductSchema.AppliedDirective<*>): String {
    val dirText = appliedDir.toString()
    val quotedDirText = escapeForCsv(dirText)
    return "${appliedDir.name},\"$quotedDirText\""
}

/**
 * Formats a list of applied directives for CSV output.
 * Returns a list of strings, each in the format: DirectiveName,"@directiveName(args...)"
 *
 * If the input list is empty, returns a single "NONE," entry to ensure
 * at least one row per entity in the output CSV.
 */
fun formatDirectivesForCsv(appliedDirectives: List<ViaductSchema.AppliedDirective<*>>): List<String> {
    if (appliedDirectives.isEmpty()) {
        return listOf("NONE,")
    }
    return appliedDirectives.map { formatDirectiveForCsv(it) }
}

// ========== CSV Table Data Structures ==========

/**
 * Represents a row in a CSV table with base data and extended (directive) data.
 */
data class CsvRow(
    val baseData: String,
    val extendedData: List<String>,
)

/**
 * Represents a CSV table with headers and rows.
 */
data class CsvTable(
    val baseName: String,
    val baseHeaders: String,
    val extendedHeaders: String,
    val rows: List<CsvRow>,
) {
    /**
     * Writes the table to CSV files in the given output directory.
     * Creates two files: {baseName}s.csv and {baseName}dirs.csv
     */
    fun writeTo(outputDir: File) {
        val out = PrintStream(FileOutputStream(File(outputDir, "${baseName}s.csv")))
        out.println(baseHeaders)
        for (row in rows) {
            out.println(row.baseData)
        }
        out.close()

        val outDir = PrintStream(FileOutputStream(File(outputDir, "${baseName}dirs.csv")))
        outDir.println("$baseHeaders,$extendedHeaders")
        for (row in rows) {
            for (extData in row.extendedData) {
                outDir.println("${row.baseData},$extData")
            }
        }
        outDir.close()
    }

    /**
     * Returns the base CSV content as a string (header + data rows).
     * Rows are sorted by baseData for deterministic output.
     */
    fun toSortedBaseContent(): String {
        val sortedRows = rows.sortedBy { it.baseData }
        return buildString {
            appendLine(baseHeaders)
            for (row in sortedRows) {
                appendLine(row.baseData)
            }
        }
    }

    /**
     * Returns the extended (dirs) CSV content as a string.
     * Rows are sorted by baseData + extendedData for deterministic output.
     */
    fun toSortedExtendedContent(): String {
        val sortedEntries = rows.flatMap { row ->
            row.extendedData.map { ext -> "${row.baseData},$ext" }
        }.sorted()
        return buildString {
            appendLine("$baseHeaders,$extendedHeaders")
            for (entry in sortedEntries) {
                appendLine(entry)
            }
        }
    }
}

// ========== CSV Generation Functions (for testing) ==========

/**
 * Generates CSV tables from a ViaductSchema.
 * This is the core table generation logic extracted for testability.
 *
 * @param schema The schema to generate tables from
 * @return A pair of (typesTable, fieldsTable)
 */
fun generateCsvTables(schema: ViaductSchema): Pair<CsvTable, CsvTable> {
    // Create a module path computer to handle common prefix stripping
    val pathComputer = ModulePathComputer(schema)

    // Collect all root fields
    val rootFields = schema.collectAllRootFields()

    val types = mutableListOf<CsvRow>()
    val fields = mutableListOf<CsvRow>()

    for (def in schema.types.values) {
        // Check if type implements Node
        val isNodeObject = if (def is ViaductSchema.Object) {
            def.supers.any { it.name == "Node" }
        } else {
            false
        }

        val typeData = "${pathComputer.tenantOf(def)},${def.kind},$isNodeObject,${def.name}"
        types.add(CsvRow(typeData, formatDirectivesForCsv(def.appliedDirectives.toList())))

        if (def is ViaductSchema.Record) {
            for (f in def.fields) {
                val isRoot = rootFields.contains(f)
                val isExternal = pathComputer.hasExternalType(f)

                val fieldData = listOf(
                    "${pathComputer.isInExtension(f)}",
                    pathComputer.fieldTenantOf(f),
                    "$isRoot",
                    f.name,
                    pathComputer.fieldBaseTenantOf(f),
                    "${f.type.baseTypeDef.kind}",
                    "$isExternal",
                    f.type.baseTypeDef.name,
                    f.type.unparseWrappers(),
                    "${f.type.listDepth}"
                )
                fields.add(CsvRow("$typeData,${fieldData.joinToString(",")}", formatDirectivesForCsv(f.appliedDirectives.toList())))
            }
        }
    }

    val typeHeaders = "Tenant,Kind,IsNode,Type"
    val fieldHeaders = "$typeHeaders,Extension,FieldTenant,IsRoot,Field,FieldBaseTenant,FieldBaseKind,IsExternal," +
        "FieldBase,FieldWrappers,FieldListDepth"
    val extendedHeaders = "DirectiveName,AppliedDirective"

    return Pair(
        CsvTable("type", typeHeaders, extendedHeaders, types),
        CsvTable("field", fieldHeaders, extendedHeaders, fields)
    )
}

/**
 * Generates CSV files from a ViaductSchema and writes them to the output directory.
 *
 * @param schema The schema to generate CSV from
 * @param outputDir The directory to write the CSV files to
 */
fun generateCsvFiles(
    schema: ViaductSchema,
    outputDir: File
) {
    val (types, fields) = generateCsvTables(schema)
    types.writeTo(outputDir)
    fields.writeTo(outputDir)
}
