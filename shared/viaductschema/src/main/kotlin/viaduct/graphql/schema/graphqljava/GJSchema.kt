@file:Suppress("ktlint:standard:indent")

package viaduct.graphql.schema.graphqljava

import graphql.language.Directive
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import java.net.URL
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.parseWrappers
import viaduct.utils.collections.BitVector
import viaduct.utils.timer.Timer

private typealias TypeMap = Map<String, GJSchema.TypeDef>

/** This is an implementation of the [ViaductSchema] classes that uses the
 *  `graphql.schema` classes from the graphql-java library as the underlying
 *  representation.
 *
 *  The [ViaductSchema] docs indicate that implementations should document
 *  how they represent "real" values for things like applied-directive arguments
 *  and input-field defaults.  As this class is based on graphql-java, it uses
 *  graphql-java's [graphql.language.Value] classes for this purpose, with a few
 *  twists.  First, with its [graphql.schema.InputValueWithState] class, graphql-java
 *  attempts to preserve the distinction between fields that have been explicitly set,
 *  and those that are null because they are nullable and have not been set.  We don't
 *  attempt to maintain this distinction.
 *
 *  Second, graphql-java has the type [graphql.language.NullValue] to represent an
 *  explicitly-set null value.  At the top level we convert these to actual null values
 *  (which is why our representation of "real values" is `Value<*>?` rather than `Value<*>`).
 *  However, we do _not_ do this recursively: if the top-level value is a list, for example,
 *  [graphql.language.NullValue] can be present as a list element.
 */
class GJSchema internal constructor(
    override val types: Map<String, TypeDef>,
    override val directives: Map<String, GJSchema.Directive>,
    gjSchema: GraphQLSchema
) : ViaductSchema {
    private fun rootDef(
        name: String?,
        stdName: String
    ) = run {
        val result = name?.let { types[it] }
        if (result != null) {
            if (result !is Object) throw IllegalArgumentException("$stdName type ($name) is not an object type.")
            result
        } else {
            null
        }
    }

    override val queryTypeDef: Object = rootDef(gjSchema.queryType.name, "Query")
        ?: throw IllegalStateException("Query name (${gjSchema.queryType.name}) not found.")

    override val mutationTypeDef: Object? = rootDef(gjSchema.mutationType?.name, "Mutation")
    override val subscriptionTypeDef: Object? = rootDef(gjSchema.subscriptionType?.name, "Subscription")

    override fun toString() = types.toString()

    fun toTypeExpr(
        wrappers: String,
        baseString: String
    ): TypeExpr {
        this.types[baseString]!! // Make sure type exists
        val listNullable = parseWrappers(wrappers) // Checks syntax for us
        val baseNullable = (wrappers.last() == '?')
        return TypeExpr(types, baseString, baseNullable, listNullable)
    }

    // Internal for testing (GJSchemaCheck)
    internal fun toTypeExpr(gtype: GraphQLType): TypeExpr = toTypeExpr(types, gtype)

    companion object {
        /** Convert collection of .graphqls files into a schema
         *  bridge.  (This function doesn't require callers to
         *  have a direct dependency on graphql-java.) */

        fun fromURLs(
            inputFiles: List<URL>,
            valueConverter: ValueConverter = ValueConverter.default
        ) = fromRegistry(readTypesFromURLs(inputFiles), valueConverter = valueConverter)

        fun fromFiles(
            inputFiles: List<File>,
            timer: Timer = Timer(),
            valueConverter: ValueConverter = ValueConverter.default,
        ): GJSchema {
            val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(inputFiles) }
            return fromRegistry(typeDefRegistry, timer, valueConverter)
        }

        /** Convert a graphql-java TypeDefinitionRegistry into
         *  a schema sketch. */
        fun fromRegistry(
            registry: TypeDefinitionRegistry,
            timer: Timer = Timer(),
            valueConverter: ValueConverter = ValueConverter.default,
        ): GJSchema {
            val unexecutableSchema =
                timer.time("makeUnexecutableSchema") {
                    UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
                }
            return timer.time("fromSchema") { fromSchema(unexecutableSchema, valueConverter) }
        }

        fun fromSchema(
            schema: GraphQLSchema,
            valueConverter: ValueConverter = ValueConverter.default
        ): GJSchema {
            val defs = schema.allTypesAsList
            val result = mutableMapOf<String, TypeDef>()

            // Take a first pass, translating unions and accumulating a
            // name-to-list-of-unions map for interfaces and object types
            val unionMap = mutableMapOf<String, MutableList<String>>()
            val interfaceMap = mutableMapOf<String, MutableList<GraphQLNamedType>>()
            for (def in defs) {
                when (def) {
                    is GraphQLUnionType -> {
                        val u = Union(schema, result, def, valueConverter)
                        def.types.forEach { unionMap.getOrPut(it.name) { mutableListOf() }.add(def.name) }
                        result[def.name] = u
                    }

                    is GraphQLImplementingType -> {
                        def.interfaces.forEach {
                            interfaceMap.getOrPut(it.name) { mutableListOf() }.add(def)
                        }
                    }
                }
            }

            // Take second pass for the rest of the types
            // union-lists accumulated above
            for (def in defs) {
                if (def !is GraphQLUnionType) {
                    val unions = unionMap[def.name] ?: NO_TYPE_NAMES
                    // assert: def is GraphQLObjectType || unions == NO_TYPE_NAMESS
                    val d =
                        when (def) {
                            is GraphQLScalarType -> Scalar(result, def, valueConverter)
                            is GraphQLEnumType -> Enum(schema, result, def, valueConverter)
                            is GraphQLInputObjectType -> Input(schema, result, def, valueConverter)
                            is GraphQLObjectType -> Object(schema, result, def, unions, valueConverter)
                            is GraphQLInterfaceType ->
                                Interface(schema, result, def, allObjectTypes(def.name, interfaceMap), valueConverter)

                            else -> throw RuntimeException("Unexpected GraphQL type: $def")
                        }
                    result[def.name] = d
                }
            }

            val directives =
                schema.directives
                    .associate { it.name to Directive(result, it, valueConverter) }

            return GJSchema(result, directives, schema)
        }

        private val NO_TYPE_NAMES = listOf<String>()
        private val NO_INTERFACES = listOf<Interface>()
        private val NO_UNIONS = listOf<Union>()

        private fun allObjectTypes(
            interfaceName: String,
            directs: Map<String, List<GraphQLNamedType>>
        ): List<String> {
            val result = mutableListOf<String>()

            fun allObjectTypes(toAdd: GraphQLNamedType) {
                if (toAdd.name == interfaceName) {
                    throw IllegalArgumentException("Cyclical inheritance.")
                }
                if (toAdd is GraphQLObjectType) {
                    result.add(toAdd.name)
                } else {
                    directs[toAdd.name]?.let { namedTypes ->
                        namedTypes.forEach { allObjectTypes(it) }
                    }
                }
            }

            directs[interfaceName]?.let { namedTypes ->
                namedTypes.forEach { allObjectTypes(it) }
            }
            return result
        }
    }

    // Well-designed GraphQL schemas are both immutable and highly
    // cyclical.  But the Kotlin(/Java) object-construction process
    // isn't friendly to immutable, cyclical data structures.  We
    // address this gap by passing a "type map" around during object
    // construction, which we use to lazily resolve references to
    // cyclical references.
    //
    // We've established the following protocol to ensure that our
    // lazy resolution is correct.  We differentiate between
    // "top-level" objects -- which are specifically [TypeDef]s in
    // our design -- from objects nested under those top-level
    // objects (for example, [EnumValue]s and [Field]s).  When it
    // comes to the construction of top-level objects, this is
    // done using `by lazy`, using [typeMap] to resolve references.
    // However, when it comes to the construction of nested objects,
    // since those constructors are being called when [typeMap] has
    // been populated, we can use [typeMap] without further laziness.

    sealed interface Def : ViaductSchema.Def {
        val def: GraphQLNamedSchemaElement

        override fun hasAppliedDirective(name: String) =
            when (val container = def) {
                is GraphQLDirectiveContainer -> container.hasAppliedDirective(name)
                else -> false
            }
    }

    sealed interface TypeDef :
        ViaductSchema.TypeDef,
        Def {
        override fun asTypeExpr(): TypeExpr

        override val possibleObjectTypes: Set<Object>
    }

    abstract class Arg :
        HasDefaultValue(),
        ViaductSchema.Arg

    interface HasArgs :
        Def,
        ViaductSchema.HasArgs {
        override val args: List<Arg>
    }

    class DirectiveArg internal constructor(
        override val containingDef: Directive,
        override val def: GraphQLArgument,
        override val type: TypeExpr,
        protected override val _defaultValue: Any?,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
    ) : Arg(),
        ViaductSchema.DirectiveArg {
        override val name: String = def.name

        override fun toString() = describe()
    }

    class Directive internal constructor(
        private val typeMap: TypeMap,
        override val def: GraphQLDirective,
        valueConverter: ValueConverter
    ) : ViaductSchema.Directive,
        HasArgs {
        override val name: String = def.name
        override val isRepeatable: Boolean = def.isRepeatable
        override val args =
            def.arguments.map {
                val argType = toTypeExpr(typeMap, it.type)
                val defaultValue = it.defaultValue(typeMap, valueConverter)
                DirectiveArg(this, it, argType, defaultValue, emptyList())
            }

        override val allowedLocations =
            def
                .validLocations()
                .map {
                ViaductSchema.Directive.Location.valueOf(it.name)
            }.toSet()

        override val sourceLocation =
            def.definition?.sourceLocation?.sourceName?.let {
                ViaductSchema.SourceLocation(it)
            }

        override val appliedDirectives: List<ViaductSchema.AppliedDirective> = emptyList()

        override fun toString() = describe()
    }

    class Scalar internal constructor(
        private val typeMap: TypeMap,
        override val def: GraphQLScalarType,
        valueConverter: ValueConverter
    ) : ViaductSchema.Scalar,
        TypeDef {
        override val name: String = def.name

        override val sourceLocation =
            def.definition?.sourceLocation?.sourceName?.let {
                ViaductSchema.SourceLocation(it)
            }

        override val appliedDirectives by lazy { typeMap.collectDirectives(def, valueConverter) }

        override fun asTypeExpr() = TypeExpr(typeMap, this.name)

        override val possibleObjectTypes = emptySet<Object>()

        override fun toString() = describe()
    }

    class EnumValue internal constructor(
        override val containingDef: Enum,
        override val def: GraphQLEnumValueDefinition,
        override val containingExtension: ViaductSchema.Extension<Enum, EnumValue>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
    ) : ViaductSchema.EnumValue,
        Def {
        override val name: String = def.name

        override fun toString() = describe()
    }

    class Enum internal constructor(
        schema: GraphQLSchema,
        private val typeMap: TypeMap,
        override val def: GraphQLEnumType,
        valueConverter: ValueConverter
    ) : ViaductSchema.Enum,
        TypeDef {
        override val name: String = def.name
        override val values by lazy { extensions.flatMap { it.members } }

        override val extensions by lazy {
            (listOf(def.definition) + def.extensionDefinitions).map { gjLangTypeDef ->
                ViaductSchema.Extension.of(
                    def = this@Enum,
                    memberFactory = { containingExtension ->
                        if (gjLangTypeDef == null) {
                            def.values.map {
                                val ad = typeMap.collectDirectives(it, valueConverter)
                                createEnumValue(it.name, containingExtension, ad)
                            }
                        } else {
                            gjLangTypeDef.enumValueDefinitions.map { evd ->
                                val ad = evd.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                                createEnumValue(evd.name, containingExtension, ad)
                            }
                        }
                    },
                    isBase = gjLangTypeDef == def.definition,
                    appliedDirectives =
                        if (gjLangTypeDef != null) {
                            gjLangTypeDef.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                        } else {
                            typeMap.collectDirectives(def, valueConverter)
                        },
                    sourceLocation =
                        gjLangTypeDef?.sourceLocation?.sourceName?.let {
                            ViaductSchema.SourceLocation(it)
                        }
                )
            }
        }

        private fun createEnumValue(
            valueName: String,
            containingExtension: ViaductSchema.Extension<Enum, EnumValue>,
            appliedDirectives: List<ViaductSchema.AppliedDirective>
        ) = def.values.find { it.name == valueName }?.let {
            EnumValue(containingExtension.def, it, containingExtension, appliedDirectives)
        } ?: throw IllegalStateException("Enum value $valueName not found in ${def.name}")

        override fun value(name: String): EnumValue? = values.find { name == it.name }

        override val appliedDirectives by lazy { typeMap.collectDirectives(def, valueConverter) }

        override fun asTypeExpr() = TypeExpr(typeMap, this.name)

        override val possibleObjectTypes = emptySet<Object>()

        override fun toString() = describe()
    }

    class Union internal constructor(
        schema: GraphQLSchema,
        private val typeMap: TypeMap,
        override val def: GraphQLUnionType,
        valueConverter: ValueConverter
    ) : ViaductSchema.Union,
        TypeDef {
        override val name: String = def.name
        override val possibleObjectTypes by lazy { extensions.flatMap { it.members }.toSet() }
        override val appliedDirectives by lazy { typeMap.collectDirectives(def, valueConverter) }

        override fun asTypeExpr() = TypeExpr(typeMap, this.name)

        override fun toString() = describe()

        override val extensions by lazy {
            (listOf(def.definition) + def.extensionDefinitions).map { gjLangTypeDef ->
                ViaductSchema.Extension.of(
                    def = this@Union,
                    memberFactory = { _ ->
                        if (gjLangTypeDef == null) {
                            def.types.map { typeMap[it.name] as Object }
                        } else {
                            gjLangTypeDef.memberTypes
                                .filter { (it as TypeName).name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                                .map {
                                    val name = (it as TypeName).name
                                    typeMap[name] as Object
                                }
                        }
                    },
                    isBase = gjLangTypeDef == def.definition,
                    appliedDirectives =
                        if (gjLangTypeDef != null) {
                            gjLangTypeDef.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                        } else {
                            typeMap.collectDirectives(def, valueConverter)
                        },
                    sourceLocation =
                        gjLangTypeDef?.sourceLocation?.sourceName?.let {
                            ViaductSchema.SourceLocation(it)
                        }
                )
            }
        }
    }

    abstract class HasDefaultValue :
        ViaductSchema.HasDefaultValue,
        Def {
        abstract override val containingDef: Def

        abstract override val type: TypeExpr

        protected abstract val _defaultValue: Any?
        override val hasDefault: Boolean get() = _defaultValue != NO_DEFAULT

        /** Returns the default value; throws NoSuchElementException if there is none. */
        override val defaultValue: Any? get() =
            _defaultValue.also {
                if (it == NO_DEFAULT) throw NoSuchElementException("No default value")
            }

        companion object {
            internal val NO_DEFAULT = Any()
        }
    }

    class FieldArg internal constructor(
        override val containingDef: OutputField,
        override val def: GraphQLArgument,
        override val type: TypeExpr,
        protected override val _defaultValue: Any?,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
    ) : Arg(),
        ViaductSchema.FieldArg {
        override val name: String = def.name

        override fun toString() = describe()
    }

    sealed class Field :
        HasDefaultValue(),
        ViaductSchema.Field,
        HasArgs {
        abstract override val containingDef: Record
        abstract override val containingExtension: ViaductSchema.Extension<Record, Field>
        abstract override val type: TypeExpr
        abstract override val args: List<FieldArg>

        override fun toString() = describe()
    }

    class OutputField internal constructor(
        typeMap: TypeMap,
        override val def: GraphQLFieldDefinition,
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        valueConverter: ValueConverter
    ) : Field() {
        override val name: String = def.name
        override val type = toTypeExpr(typeMap, def.type)
        override val isOverride = ViaductSchema.isOverride(this)
        override val containingDef get() = containingExtension.def
        protected override val _defaultValue = HasDefaultValue.NO_DEFAULT

        override val args =
            def.arguments.map {
                val argType = toTypeExpr(typeMap, it.type)
                val defaultValue = it.defaultValue(typeMap, valueConverter)
                FieldArg(this, it, argType, defaultValue, typeMap.collectDirectives(it, valueConverter))
            }
    }

    class InputField internal constructor(
        typeMap: TypeMap,
        override val def: GraphQLInputObjectField,
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        override val _defaultValue: Any?
    ) : Field() {
        override val name: String = def.name
        override val type = toTypeExpr(typeMap, def.type)
        override val isOverride = ViaductSchema.isOverride(this)
        override val containingDef get() = containingExtension.def as Input
        override val args = emptyList<FieldArg>()
    }

    sealed interface Record :
        ViaductSchema.Record,
        TypeDef {
        override val extensions: List<ViaductSchema.Extension<Record, Field>>
        override val fields: List<Field>

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field = ViaductSchema.field(this, path)

        override val supers: List<Interface>
        override val unions: List<Union>
    }

    class Interface internal constructor(
        schema: GraphQLSchema,
        private val typeMap: TypeMap,
        override val def: GraphQLInterfaceType,
        possibleObjectStrings: Iterable<String>,
        valueConverter: ValueConverter
    ) : ViaductSchema.Interface,
        Record {
        override val name: String = def.name

        override fun field(name: String) = super<Record>.field(name)

        override val supers by lazy { def.interfaces.map { typeMap[it.name] as Interface } }
        override val possibleObjectTypes by lazy { possibleObjectStrings.map { typeMap[it] as Object }.toSet() }
        override val unions = NO_UNIONS
        override val fields by lazy { extensions.flatMap { it.members } }
        override val appliedDirectives by lazy { typeMap.collectDirectives(def, valueConverter) }

        override fun asTypeExpr() = TypeExpr(typeMap, this.name)

        override fun toString() = describe()

        private fun createOutputField(
            fieldName: String,
            containingExtension: ViaductSchema.Extension<Record, Field>,
            appliedDirectives: List<ViaductSchema.AppliedDirective>,
            valueConverter: ValueConverter
        ) = def.fields.find { it.name == fieldName }?.let {
            OutputField(typeMap, it, containingExtension, appliedDirectives, valueConverter)
        } ?: throw IllegalStateException("Field $fieldName not found in ${def.name}")

        override val extensions by lazy {
            (listOf(def.definition) + def.extensionDefinitions).map { gjLangTypeDef ->
                ViaductSchema.ExtensionWithSupers.of(
                    def = this@Interface,
                    memberFactory = { containingExtension ->
                        if (gjLangTypeDef == null) {
                            def.fields
                                .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                                .map {
                                    val ad = typeMap.collectDirectives(it, valueConverter)
                                    createOutputField(it.name, containingExtension, ad, valueConverter)
                                }
                        } else {
                            gjLangTypeDef.fieldDefinitions
                                .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                                .map {
                                    val ad = it.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                                    createOutputField(it.name, containingExtension, ad, valueConverter)
                                }
                        }
                    },
                    isBase = gjLangTypeDef == def.definition,
                    appliedDirectives =
                        if (gjLangTypeDef != null) {
                            gjLangTypeDef.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                        } else {
                            typeMap.collectDirectives(def, valueConverter)
                        },
                    supers =
                        when {
                            gjLangTypeDef == null -> this.supers
                            else -> gjLangTypeDef.implements.map { typeMap[(it as TypeName).name] as Interface }
                        },
                    sourceLocation =
                        gjLangTypeDef?.sourceLocation?.sourceName?.let {
                            ViaductSchema.SourceLocation(it)
                        }
                )
            }
        }
    }

    class Object internal constructor(
        schema: GraphQLSchema,
        private val typeMap: TypeMap,
        override val def: GraphQLObjectType,
        unionNames: Iterable<String>,
        valueConverter: ValueConverter
    ) : ViaductSchema.Object,
        Record {
        override val name: String = def.name

        override fun field(name: String) = super<Record>.field(name)

        override val supers by lazy { def.interfaces.map { typeMap[it.name] as Interface } }
        override val unions by lazy { unionNames.map { typeMap[it] as Union } }
        override val fields by lazy { extensions.flatMap { it.members } }
        val extensionDefinitions get() = def.extensionDefinitions
        override val appliedDirectives by lazy { typeMap.collectDirectives(def, valueConverter) }

        override fun asTypeExpr() = TypeExpr(typeMap, this.name)

        override val possibleObjectTypes = setOf(this)

        override fun toString() = describe()

        private fun createOutputField(
            fieldName: String,
            containingExtension: ViaductSchema.Extension<Record, Field>,
            appliedDirectives: List<ViaductSchema.AppliedDirective>,
            valueConverter: ValueConverter
        ) = def.fields.find { it.name == fieldName }?.let {
            OutputField(typeMap, it, containingExtension, appliedDirectives, valueConverter)
        } ?: throw IllegalStateException("Field $fieldName not found in ${def.name}")

        override val extensions by lazy {
            (listOf(def.definition) + def.extensionDefinitions)
                .map { gjLangTypeDef ->
                    ViaductSchema.ExtensionWithSupers.of(
                        def = this@Object,
                        memberFactory = { containingExtension ->
                            if (gjLangTypeDef == null) {
                                def.fields
                                    .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                                    .map {
                                        val ad = typeMap.collectDirectives(it, valueConverter)
                                        createOutputField(it.name, containingExtension, ad, valueConverter)
                                    }
                            } else {
                                gjLangTypeDef.fieldDefinitions
                                    .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                                    .map {
                                        val ad = it.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                                        createOutputField(it.name, containingExtension, ad, valueConverter)
                                    }
                            }
                        },
                        isBase = gjLangTypeDef == def.definition,
                        appliedDirectives =
                            if (gjLangTypeDef != null) {
                                gjLangTypeDef.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                            } else {
                                typeMap.collectDirectives(def, valueConverter)
                            },
                        supers =
                            when {
                                gjLangTypeDef == null -> this.supers
                                else -> gjLangTypeDef.implements.map { typeMap[(it as TypeName).name] as Interface }
                            },
                        sourceLocation =
                            gjLangTypeDef?.sourceLocation?.sourceName?.let {
                                ViaductSchema.SourceLocation(it)
                            }
                    )
                }
        }
    }

    class Input internal constructor(
        schema: GraphQLSchema,
        private val typeMap: TypeMap,
        override val def: GraphQLInputObjectType,
        valueConverter: ValueConverter
    ) : ViaductSchema.Input,
        Record {
        override val name: String = def.name
        override val supers = NO_INTERFACES
        override val unions = NO_UNIONS
        override val fields by lazy { extensions.flatMap { it.members } }
        override val appliedDirectives by lazy { typeMap.collectDirectives(def, valueConverter) }

        override fun asTypeExpr() = TypeExpr(typeMap, this.name)

        override val possibleObjectTypes = setOf<Object>()

        override fun toString() = describe()

        private fun createInputField(
            fieldName: String,
            containingExtension: ViaductSchema.Extension<Record, Field>,
            appliedDirectives: List<ViaductSchema.AppliedDirective>,
            valueConverter: ValueConverter
        ) = def.fields.find { it.name == fieldName }?.let {
            val defaultValue =
                when {
                    !it.hasSetDefaultValue() -> HasDefaultValue.NO_DEFAULT
                    else -> valueConverter.convert(toTypeExpr(typeMap, it.type), it.inputFieldDefaultValue)
                }
            InputField(typeMap, it, containingExtension, appliedDirectives, defaultValue)
        } ?: throw IllegalStateException("Field $fieldName not found in ${def.name}")

        override val extensions by lazy {
            (listOf(def.definition) + def.extensionDefinitions).map { gjLangTypeDef ->
                ViaductSchema.Extension.of(
                    def = this@Input,
                    memberFactory = { containingExtension ->
                        if (gjLangTypeDef == null) {
                            def.fields.map {
                                val ad = typeMap.collectDirectives(it, valueConverter)
                                createInputField(it.name, containingExtension, ad, valueConverter)
                            }
                        } else {
                            gjLangTypeDef.inputValueDefinitions.map {
                                val ad = it.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                                createInputField(it.name, containingExtension, ad, valueConverter)
                            }
                        }
                    },
                    isBase = gjLangTypeDef == def.definition,
                    appliedDirectives =
                        if (gjLangTypeDef != null) {
                            gjLangTypeDef.directives.toAppliedDirectives(schema, typeMap, valueConverter)
                        } else {
                            typeMap.collectDirectives(def, valueConverter)
                        },
                    sourceLocation =
                        gjLangTypeDef?.sourceLocation?.sourceName?.let {
                            ViaductSchema.SourceLocation(it)
                        }
                )
            }
        }
    }

    class TypeExpr internal constructor(
        private val typeMap: TypeMap,
        private val baseTypeDefName: String,
        override val baseTypeNullable: Boolean = true, // GraphQL default is types are nullable
        override val listNullable: BitVector = NO_WRAPPERS
    ) : ViaductSchema.TypeExpr() {
        override val baseTypeDef get() = typeMap[baseTypeDefName]!!

        override fun unwrapLists() = TypeExpr(typeMap, baseTypeDefName, baseTypeNullable)

        override fun unwrapList(): TypeExpr? =
            if (listNullable.size == 0) {
                null
            } else {
                TypeExpr(typeMap, baseTypeDefName, baseTypeNullable, listNullable.lsr())
            }
    }
}

private fun baseTypeDefName(t: GraphQLType) = GraphQLTypeUtil.unwrapAll(t).name

private fun toTypeExpr(
    typeMap: TypeMap,
    gtype: GraphQLType
): GJSchema.TypeExpr {
    var baseTypeNullable = true
    var listNullable = ViaductSchema.TypeExpr.NO_WRAPPERS

    var t = gtype
    if (GraphQLTypeUtil.isWrapped(t)) {
        val wrapperBuilder = BitVector.Builder()
        do {
            if (GraphQLTypeUtil.isList(t)) {
                wrapperBuilder.add(1L, 1)
                t = GraphQLTypeUtil.unwrapOne(t)
            } else if (GraphQLTypeUtil.isNonNull(t)) {
                t = GraphQLTypeUtil.unwrapOne(t)
                if (GraphQLTypeUtil.isList(t)) {
                    wrapperBuilder.add(0L, 1)
                    t = GraphQLTypeUtil.unwrapOne(t)
                } else if (GraphQLTypeUtil.isWrapped(t)) {
                    throw IllegalStateException("Unexpected GraphQL wrapping $gtype.")
                } else {
                    baseTypeNullable = false
                }
            } else {
                throw IllegalStateException("Unexpected GraphQL wrapper $gtype.")
            }
        } while (GraphQLTypeUtil.isWrapped(t))
        listNullable = wrapperBuilder.build()
    }

    return GJSchema.TypeExpr(typeMap, baseTypeDefName(gtype), baseTypeNullable, listNullable)
}

private fun TypeMap.toTypeExpr(type: Type<*>): GJSchema.TypeExpr =
    type.toTypeExpr { baseTypeDefName, baseTypeNullable, listNullable ->
        GJSchema.TypeExpr(this, baseTypeDefName, baseTypeNullable, listNullable)
    }

private fun TypeMap.toAppliedDirective(
    schema: GraphQLSchema,
    dir: Directive,
    valueConverter: ValueConverter
): ViaductSchema.AppliedDirective {
    val def =
        schema.getDirective(dir.name)?.definition
            ?: throw java.lang.IllegalStateException("Directive @${dir.name} not found in schema.")
    return dir.toAppliedDirective(def, valueConverter) { toTypeExpr(it) }
}

private fun TypeMap.collectDirectives(
    def: GraphQLDirectiveContainer,
    valueConverter: ValueConverter
): List<ViaductSchema.AppliedDirective> =
    def.appliedDirectives.map { directive ->
        ViaductSchema.AppliedDirective.of(
            name = directive.name,
            arguments =
                directive.arguments.fold(mutableMapOf()) { m, arg ->
                    m[arg.name] = valueConverter.convert(toTypeExpr(this, arg.type), arg.argumentValue)
                    m
                }
        )
    }

private fun Iterable<Directive>.toAppliedDirectives(
    schema: GraphQLSchema,
    typeMap: TypeMap,
    valueConverter: ValueConverter
) = this.map { typeMap.toAppliedDirective(schema, it, valueConverter) }

private fun GraphQLArgument.defaultValue(
    typeMap: TypeMap,
    valueConverter: ValueConverter
) = if (!hasSetDefaultValue()) {
    GJSchema.HasDefaultValue.NO_DEFAULT
} else {
    valueConverter.convert(toTypeExpr(typeMap, type), argumentDefaultValue)
}
