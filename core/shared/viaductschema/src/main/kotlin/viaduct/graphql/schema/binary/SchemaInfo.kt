package viaduct.graphql.schema.binary

import com.google.common.base.Utf8
import viaduct.graphql.schema.ViaductSchema

/**
 * Represents a definition stub for the Definition Stubs section.
 *
 * @property identifierIndex Index into the identifier table for the definition name
 * @property kindCode The kind code (K_DIRECTIVE, K_ENUM, etc.)
 */
internal data class DefinitionStub(val identifierIndex: Int, val kindCode: Int)

internal class SchemaInfo(
    val inputSchema: ViaductSchema,
    private val constantsEncoderBuilder: ConstantsEncoder.Builder
) {
    //
    // Outputs

    val identifiers: Array<String> by lazy { identifierSet.toTypedArray() }

    val sourceNames: Array<String> by lazy { sourceNameSet.toTypedArray() }

    /** Count of source names including null placeholder */
    val sourceNameCount: Int get() = sourceNames.size + 1

    val typeExprs: Map<ViaductSchema.TypeExpr<*>, Int> by lazy {
        mutableMapOf<ViaductSchema.TypeExpr<*>, Int>().apply {
            var idx = 0
            typeExprMap.entries.sortedBy { -it.value }.forEach {
                put(it.key, idx++)
            }
        }
    }

    /**
     * Returns definition stubs for all definitions (directives + types) in identifier-sorted order.
     * Each stub contains the identifier index and kind code.
     */
    val definitionStubs: List<DefinitionStub> by lazy {
        val stubs = mutableListOf<DefinitionStub>()

        // Collect all definitions with their names and kind codes
        for (directive in inputSchema.directives.values) {
            stubs.add(DefinitionStub(identifierIndex(directive.name), K_DIRECTIVE))
        }
        for (typeDef in inputSchema.types.values) {
            val kindCode = when (typeDef) {
                is ViaductSchema.Enum -> K_ENUM
                is ViaductSchema.Input -> K_INPUT
                is ViaductSchema.Interface -> K_INTERFACE
                is ViaductSchema.Object -> K_OBJECT
                is ViaductSchema.Scalar -> K_SCALAR
                is ViaductSchema.Union -> K_UNION
                else -> throw IllegalArgumentException("Unknown type-def kind ($typeDef).")
            }
            stubs.add(DefinitionStub(identifierIndex(typeDef.name), kindCode))
        }

        // Sort by identifier index (which corresponds to identifier-sorted order)
        stubs.sortBy { it.identifierIndex }
        stubs
    }

    var maxIdentifierOrSourceNameLen = 0
        private set

    var identifierBytes = 0
        private set

    var sourceNameBytes = 0
        private set

    fun identifierIndex(name: String) =
        identifiers.binarySearch(name).also {
            if (it < 0) throw NoSuchElementException(name)
        }

    fun sourceNameIndex(sourceName: String?): Int {
        if (sourceName == null) return 0
        val idx = sourceNames.binarySearch(sourceName)
        if (idx < 0) throw NoSuchElementException("Source name not found: $sourceName")
        return idx + 1 // Add 1 because index 0 is reserved for null
    }

    //
    // Internal stuff (including initialization!!)

    private val identifierSet = sortedSetOf<String>()
    private val sourceNameSet = sortedSetOf<String>()
    private val typeExprMap = mutableMapOf<ViaductSchema.TypeExpr<*>, Int>()

    init {
        // Account for section magic number (4 bytes) in identifiers section
        identifierBytes = WORD_SIZE

        // Account for section magic number (4 bytes) and null placeholder (1 byte) in source locations
        sourceNameBytes = WORD_SIZE + 1

        // Visit all types and directives to collect identifiers, source names, and type expressions
        for (def in inputSchema.types.values) {
            when (def) {
                is ViaductSchema.Scalar -> visitScalar(def)
                is ViaductSchema.Enum -> visitEnum(def)
                is ViaductSchema.Input -> visitInput(def)
                is ViaductSchema.Interface -> visitInterface(def)
                is ViaductSchema.Object -> visitObject(def)
                is ViaductSchema.Union -> visitUnion(def)
                else -> throw IllegalArgumentException("Unknown type-def kind ($def).")
            }
        }
        for (directive in inputSchema.directives.values) {
            visitDirective(directive)
        }
    }

    //
    // Top-level visit functions

    private fun visitDirective(d: ViaductSchema.Directive) {
        addIdentifier(d.name)
        addSourceName(d.sourceLocation?.sourceName)
        for (a in d.args) visitArg(a)
    }

    private fun visitEnum(e: ViaductSchema.Enum) {
        addTypeDef(e)
        // Collect extension source locations and applied directives
        for (ext in e.extensions) {
            addSourceName(ext.sourceLocation?.sourceName)
            visitAppliedDirectives(ext.appliedDirectives)
        }
        for (v in e.values) {
            addIdentifier(v.name)
            visitAppliedDirectives(v.appliedDirectives)
        }
    }

    private fun visitInput(i: ViaductSchema.Input) {
        addTypeDef(i)
        // Collect extension source locations and applied directives
        for (ext in i.extensions) {
            addSourceName(ext.sourceLocation?.sourceName)
            visitAppliedDirectives(ext.appliedDirectives)
        }
        for (f in i.fields) visitField(f)
    }

    private fun visitInterface(i: ViaductSchema.Interface) {
        addTypeDef(i)
        // Collect extension source locations and applied directives
        for (ext in i.extensions) {
            addSourceName(ext.sourceLocation?.sourceName)
            visitAppliedDirectives(ext.appliedDirectives)
        }
        for (f in i.fields) visitField(f)
    }

    private fun visitObject(o: ViaductSchema.Object) {
        addTypeDef(o)
        // Collect extension source locations and applied directives
        for (ext in o.extensions) {
            addSourceName(ext.sourceLocation?.sourceName)
            visitAppliedDirectives(ext.appliedDirectives)
        }
        for (f in o.fields) visitField(f)
    }

    private fun visitScalar(s: ViaductSchema.Scalar) = addTypeDef(s)

    private fun visitUnion(u: ViaductSchema.Union) {
        addTypeDef(u)
        // Collect extension source locations and applied directives
        for (ext in u.extensions) {
            addSourceName(ext.sourceLocation?.sourceName)
            visitAppliedDirectives(ext.appliedDirectives)
        }
    }

    //
    // Second-level visit functions

    private fun visitAppliedDirectives(appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>) {
        for (ad in appliedDirectives) {
            addIdentifier(ad.name)
            for ((argName, argValue) in ad.arguments) {
                addIdentifier(argName)
                addConstantValue(argValue)
            }
        }
    }

    private fun visitArg(a: ViaductSchema.Arg) {
        addIdentifier(a.name)
        addTypeExpr(a.type)
        addConstant(a)
        visitAppliedDirectives(a.appliedDirectives)
    }

    private fun visitField(f: ViaductSchema.Field) {
        addIdentifier(f.name)
        addTypeExpr(f.type)
        // For input type fields, collect default values
        if (f.containingDef is ViaductSchema.Input && f.hasDefault) {
            addConstantValue(f.defaultValue)
        }
        for (a in f.args) visitArg(a)
        visitAppliedDirectives(f.appliedDirectives)
    }

    //
    // Helper functions

    private fun addIdentifier(s: String) {
        val l = s.length
        if (maxIdentifierOrSourceNameLen < l) maxIdentifierOrSourceNameLen = l
        if (identifierSet.add(s)) identifierBytes += (l + 1)
    }

    private fun addSourceName(sourceName: String?) {
        if (sourceName != null && sourceNameSet.add(sourceName)) {
            val byteLen = Utf8.encodedLength(sourceName)
            if (maxIdentifierOrSourceNameLen < byteLen) maxIdentifierOrSourceNameLen = byteLen
            sourceNameBytes += (byteLen + 1)
        }
    }

    private fun addConstant(arg: ViaductSchema.Arg) {
        if (arg.hasDefault) {
            addConstantValue(arg.defaultValue)
        }
    }

    /**
     * Adds a constant value, extracting and registering any field names from
     * InputObjectConstant values as identifiers.
     */
    private fun addConstantValue(value: ViaductSchema.Literal) {
        val constantRepr = ValueStringConverter.valueToString(value)
        extractIdentifiersFromConstant(constantRepr)
        constantsEncoderBuilder.addValue(constantRepr)
    }

    /**
     * Recursively extracts field names from compound constants and registers
     * them as identifiers. This is needed because ObjectValue literals in SDL
     * can use arbitrary field names that aren't otherwise declared in the schema.
     */
    private fun extractIdentifiersFromConstant(repr: Any?) {
        when (repr) {
            null, is String -> { /* Simple values have no identifiers to extract */ }
            is ListConstant -> repr.elements.forEach { extractIdentifiersFromConstant(it) }
            is InputObjectConstant -> {
                for ((fieldName, fieldValue) in repr.fieldPairs) {
                    addIdentifier(fieldName)
                    extractIdentifiersFromConstant(fieldValue)
                }
            }
        }
    }

    private fun addTypeExpr(te: ViaductSchema.TypeExpr<*>) {
        typeExprMap.put(te, 1 + typeExprMap.getOrDefault(te, 0))
    }

    private fun addTypeDef(td: ViaductSchema.TypeDef) {
        addIdentifier(td.name)
        addSourceName(td.sourceLocation?.sourceName)
        visitAppliedDirectives(td.appliedDirectives)
    }
}
