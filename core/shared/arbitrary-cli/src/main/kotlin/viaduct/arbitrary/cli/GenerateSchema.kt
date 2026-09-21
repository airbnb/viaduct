package viaduct.arbitrary.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import graphql.language.AstPrinter
import graphql.language.DirectiveDefinition
import graphql.language.SchemaDefinition
import graphql.language.TypeDefinition
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Arrays
import kotlin.random.Random
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.AppliedDirectiveWeight
import viaduct.arbitrary.graphql.BanFieldNames
import viaduct.arbitrary.graphql.ConnectionCount
import viaduct.arbitrary.graphql.DedupeCaseInsensitiveNames
import viaduct.arbitrary.graphql.DefaultValueWeight
import viaduct.arbitrary.graphql.DescriptionLength
import viaduct.arbitrary.graphql.DirectiveHasArgs
import viaduct.arbitrary.graphql.EnumTypeSize
import viaduct.arbitrary.graphql.FieldArgumentWeight
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.IdOfFieldWeight
import viaduct.arbitrary.graphql.IncludeBuiltinDirectives
import viaduct.arbitrary.graphql.IncludeTypes
import viaduct.arbitrary.graphql.InputObjectTypeSize
import viaduct.arbitrary.graphql.InterfaceImplementsInterface
import viaduct.arbitrary.graphql.InterfaceTypeSize
import viaduct.arbitrary.graphql.MaxInterfaceNestingDepth
import viaduct.arbitrary.graphql.ObjectImplementsInterface
import viaduct.arbitrary.graphql.ObjectTypeSize
import viaduct.arbitrary.graphql.OneOfTypeWeight
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.TypeType
import viaduct.arbitrary.graphql.TypeTypeWeights
import viaduct.arbitrary.graphql.UnionTypeSize
import viaduct.arbitrary.graphql.asDocument
import viaduct.arbitrary.graphql.builtinDirectiveNames
import viaduct.arbitrary.graphql.viaductDefaultNames
import viaduct.arbitrary.graphql.viaductDirectiveSchema
import viaduct.arbitrary.graphql.viaductNodeTypes

/** Kotlin's hard keywords -- see kotlinlang.org's keyword reference. */
private val RESERVED_KEYWORDS = setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
)

/** Generates an arbitrary GraphQL schema (SDL) and writes it to a file; invoked out-of-process so consumers avoid a compile-time dependency on `shared/arbitrary`. */
class GenerateSchema : CliktCommand(name = "generate-schema") {
    object Main {
        @JvmStatic
        fun main(args: Array<String>) = GenerateSchema().main(args)
    }

    private val output: File by option("--output", help = "File to write the generated SDL to")
        .file(mustExist = false, canBeDir = false)
        .required()

    private val seed: Long? by option(
        "--seed",
        help = "Exact seed to use. Mutually exclusive with --seed-input and --seed-variant."
    ).long()

    private val seedInputs: List<File> by option(
        "--seed-input",
        help = "File whose contents are hashed into the seed. Repeatable."
    ).file(mustExist = true, canBeDir = false).multiple()

    private val seedVariant: Long? by option(
        "--seed-variant",
        help = "Variant hashed with the input contents to choose another reproducible schema. Defaults to 0."
    ).long()

    /**
     * Maximizes schema "arbitrariness" so the generated fragment is a good stress test for
     * consumers that splice it into an existing schema: every TypeType is represented, every
     * interface is guaranteed an implementing object, and type/field counts are large.
     *
     * [IncludeBuiltinDirectives] and custom scalars (left at their `false` default) stay off
     * because the fragment is merged into a schema that already declares its own directives and
     * can't declare custom scalars.
     *
     * [DefaultValueWeight] and [DirectiveHasArgs] stay off: `ScalarValueGen` can't synthesize a
     * literal for a runtime-generated custom scalar name, and would throw. [BanFieldNames] excludes
     * "_" (Kotlin reserves it as a declaration name), "of" (the Kotlin GRT codegen's `_Arguments`
     * class always declares a nested `object of`, which a field literally named "of" collides with),
     * and [RESERVED_KEYWORDS]. Field names generally get backtick-escaped by the Kotlin codegen (see
     * `viaduct.tenant.codegen.kotlingen.bytecode.getEscapedFieldName`), but enum *values* don't go
     * through that path, so an enum value literally named e.g. `is` fails to compile as generated
     * Kotlin source. Banning them here is the narrow fix within this generator's scope; the missing
     * escape call for enum values is a separate, pre-existing kotlingen gap worth fixing on its own.
     *
     * The Viaduct directive shapes ([IdOfFieldWeight] and friends) are left at their defaults; they
     * take effect at all only because [run] seeds the `Node` interface and Viaduct's directives via
     * [IncludeTypes]. [ConnectionCount] is the exception, raised off its zero default so every
     * fragment carries connections. [MaxInterfaceNestingDepth] is kept modest -- a couple of levels
     * of interface nesting exercises the paths that care about it without a "very hard" schema.
     *
     * [DedupeCaseInsensitiveNames] is on because GRT codegen writes one file per type name, and
     * "Object_i" and "Object_I" are one path on a case-insensitive filesystem.
     */
    private val extensiveSchemaFragmentConfig: Config = Config.default +
        (SchemaSize to 300) +
        (BanFieldNames to RESERVED_KEYWORDS + setOf("_", "of")) +
        (DedupeCaseInsensitiveNames to true) +
        (
            // Types not listed here fall back to the default weight of 1.0.
            TypeTypeWeights to mapOf(
                TypeType.Object to 4.0,
                TypeType.Interface to 1.5,
                TypeType.Input to 1.5
            )
        ) +
        (GenInterfaceStubsIfNeeded to true) +
        (ObjectImplementsInterface to CompoundingWeight(.6, 4)) +
        (InterfaceImplementsInterface to CompoundingWeight(.4, 3)) +
        (ObjectTypeSize to 4..10) +
        (InterfaceTypeSize to 3..8) +
        (InputObjectTypeSize to 3..8) +
        (UnionTypeSize to 3..8) +
        (EnumTypeSize to 3..8) +
        (FieldArgumentWeight to CompoundingWeight(.5, 3)) +
        (DefaultValueWeight to 0.0) +
        (AppliedDirectiveWeight to CompoundingWeight(.4, 3)) +
        (DirectiveHasArgs to CompoundingWeight.Never) +
        (OneOfTypeWeight to 0.0) +
        (DescriptionLength to 0..0) +
        (IncludeBuiltinDirectives to false) +
        (MaxInterfaceNestingDepth to 2) +
        (ConnectionCount to 1..3)

    override fun run() {
        val usedSeed = resolveSeed()
        echo("[GenerateSchema] generating $output (seed=$usedSeed)")
        val rs = RandomSource.seeded(usedSeed)

        val cfg = extensiveSchemaFragmentConfig + (IncludeTypes to viaductNodeTypes())
        val schema = Arb.viaductDirectiveSchema(cfg).next(rs)
        val document = SchemaPrinter().print(schema).asDocument
        val fragment = document.transform { builder ->
            builder.definitions(
                document.definitions.filterNot {
                    when (it) {
                        is SchemaDefinition -> true
                        is DirectiveDefinition -> it.name in builtinDirectiveNames || it.name in viaductDefaultNames
                        is TypeDefinition<*> -> it.name in viaductDefaultNames
                        else -> false
                    }
                }
            )
        }
        val sdl = AstPrinter.printAst(fragment)

        // Fail loudly here, with the seed available, rather than as a confusing downstream error.
        SchemaParser().parse(sdl)

        output.parentFile?.mkdirs()
        output.writeText(
            "# AUTO-GENERATED by viaduct.arbitrary.cli.GenerateSchema -- do not edit.\n" +
                "# seed=$usedSeed\n" +
                "# Rerun with --seed=$usedSeed to reproduce this exact schema.\n\n" +
                sdl
        )

        echo("[GenerateSchema] wrote $output (seed=$usedSeed)")
    }

    private fun resolveSeed(): Long {
        val derived = seedInputs.isNotEmpty() || seedVariant != null
        require(seed == null || !derived) { "--seed cannot be combined with --seed-input or --seed-variant" }
        return when {
            seed != null -> seed!!
            derived -> deriveSeed()
            else -> Random.nextLong()
        }
    }

    private fun deriveSeed(): Long {
        val inputHashes = seedInputs.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest()
        }.sortedWith { left, right -> Arrays.compareUnsigned(left, right) }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(seedVariant ?: 0L).array())
        inputHashes.forEach { digest.update(it) }
        return ByteBuffer.wrap(digest.digest()).long
    }
}
