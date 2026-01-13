package viaduct.graphql.schema

import viaduct.invariants.InvariantChecker

private typealias TypeMap = Map<String, FilteredSchema.TypeDef<out ViaductSchema.TypeDef>>

/** See KDoc for [ViaductSchema] for a background.
 *
 *  The `xyzTypeNameFromBaseSchema` parameters here work a bit differently from
 *  their analogs in `GJSchemaRaw`.  In `GJSchemaRaw`, they are used to set
 *  the root types, and thus if they name a non-existent type, the right behavior
 *  is to fail.  Here, the base schema is assumed to have (or not have) a root type
 *  defs, and the `xyzTypeNameFromBaseSchema` is intended to pass the name of those
 *  types in.  Now, it might be the case that those types get filtered out, so
 *  it's _not_ an error if they name non-existent types.  The code can't actually
 *  know, however, if `xyzTypeNameFromBaseSchema` is actually from the base schema,
 *  so it _does_ check to ensure that it names an object type.
 *
 *  (There's a bigger issue here that FilteredSchema does not take an actual schema
 *  as a constructor argument. The reason is that ViaductSchema itself is not
 *  parameterized by a `TypeDef` param, which we'd need to make the typing of
 *  FilteredSchema to work. Not sure if we want to fix this.)
 */
@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class FilteredSchema<T : ViaductSchema.TypeDef>(
    filter: SchemaFilter,
    schemaEntries: Iterable<Map.Entry<String, T>>,
    directiveEntries: Iterable<Map.Entry<String, ViaductSchema.Directive>>,
    schemaInvariantOptions: SchemaInvariantOptions,
    queryTypeNameFromBaseSchema: String?,
    mutationTypeNameFromBaseSchema: String?,
    subscriptionTypeNameFromBaseSchema: String?
) : ViaductSchema {
    private val defs: MutableMap<String, TypeDef<out T>> = mutableMapOf()
    override val types: Map<String, TypeDef<out T>> = defs
    override val directives = directiveEntries.associate { (k, v) -> k to Directive(v, defs) }

    init {
        schemaEntries
            .filter { (_, value) -> filter.includeTypeDef(value) }
            .forEach { (k, v) ->
                val wrappedValue =
                    when (v) {
                        is ViaductSchema.Enum -> Enum(v, defs, filter)
                        is ViaductSchema.Input -> Input(v, defs, filter)
                        is ViaductSchema.Interface -> Interface(v, defs, filter)
                        is ViaductSchema.Object -> Object(v, defs, filter)
                        is ViaductSchema.Union -> Union(v, defs, filter)
                        is ViaductSchema.Scalar -> Scalar(v, defs)
                        else -> throw IllegalArgumentException("Unexpected type definition $v")
                    }
                defs[k] = wrappedValue
            }

        val violations = InvariantChecker()
        checkBridgeSchemaInvariants(this, violations, schemaInvariantOptions)
        violations.assertEmptyMultiline("FilteredSchema failed the following invariant checks:\n")
    }

    private fun rootDef(nameFromBaseSchema: String?): ViaductSchema.Object? {
        // As noted earlier, we shouldn't fail if the named type doesn't exist
        val result = nameFromBaseSchema?.let { types[it] }
        if (result != null && result !is ViaductSchema.Object) {
            throw IllegalArgumentException("$result is not an object type.")
        }
        return result as? ViaductSchema.Object
    }

    override val queryTypeDef = rootDef(queryTypeNameFromBaseSchema)
    override val mutationTypeDef = rootDef(mutationTypeNameFromBaseSchema)
    override val subscriptionTypeDef = rootDef(subscriptionTypeNameFromBaseSchema)

    override fun toString() = defs.toString()

    sealed interface Def<D : ViaductSchema.Def> : ViaductSchema.Def {
        val unfilteredDef: D

        override fun unwrapAll(): ViaductSchema.Def = this.unfilteredDef.unwrapAll()
    }

    sealed interface TypeDef<T : ViaductSchema.TypeDef> :
        Def<T>,
        ViaductSchema.TypeDef {
        override fun asTypeExpr(): TypeExpr<*>

        override val possibleObjectTypes: Set<Object<out ViaductSchema.Object>>
    }

    sealed interface Arg<D : ViaductSchema.Def, A : ViaductSchema.Arg> :
        HasDefaultValue<D, A>,
        ViaductSchema.Arg {
        override val unfilteredDef: A
        override val containingDef: Def<D>
    }

    interface HasArgs<D : ViaductSchema.Def> :
        Def<D>,
        ViaductSchema.HasArgs {
        override val args: List<Arg<D, out ViaductSchema.Arg>>
    }

    class DirectiveArg<D : ViaductSchema.Directive, A : ViaductSchema.DirectiveArg> internal constructor(
        override val unfilteredDef: A,
        override val containingDef: Directive<D>,
        private val defs: TypeMap
    ) : Arg<D, A>,
        ViaductSchema.DirectiveArg by unfilteredDef {
        override val type = TypeExpr(unfilteredDef.type, defs)

        override fun toString() = unfilteredDef.toString()
    }

    class Directive<D : ViaductSchema.Directive> internal constructor(
        override val unfilteredDef: D,
        private val defs: TypeMap
    ) : HasArgs<D>,
        ViaductSchema.Directive by unfilteredDef {
        override val args = unfilteredDef.args.map { DirectiveArg(it, this, defs) }
        override val isRepeatable: Boolean = unfilteredDef.isRepeatable

        override fun toString() = unfilteredDef.toString()
    }

    class Scalar<S : ViaductSchema.Scalar> internal constructor(
        override val unfilteredDef: S,
        private val defs: TypeMap
    ) : TypeDef<S>,
        ViaductSchema.Scalar by unfilteredDef {
        override fun asTypeExpr() = TypeExpr(unfilteredDef.asTypeExpr(), defs)

        override fun toString() = unfilteredDef.toString()

        override val possibleObjectTypes = emptySet<Object<out ViaductSchema.Object>>()
    }

    class EnumValue<E : ViaductSchema.Enum, V : ViaductSchema.EnumValue> internal constructor(
        override val unfilteredDef: V,
        override val containingDef: Enum<E>,
        override val containingExtension: ViaductSchema.Extension<Enum<E>, EnumValue<E, *>>
    ) : Def<V>,
        ViaductSchema.EnumValue by unfilteredDef {
        override fun toString() = unfilteredDef.toString()
    }

    class Enum<E : ViaductSchema.Enum> internal constructor(
        override val unfilteredDef: E,
        private val defs: TypeMap,
        filter: SchemaFilter
    ) : TypeDef<E>,
        ViaductSchema.Enum by unfilteredDef {
        override val extensions: List<ViaductSchema.Extension<Enum<E>, EnumValue<E, *>>> =
            unfilteredDef.extensions.map { unfilteredExt ->
                makeExtension(this, unfilteredDef, unfilteredExt) { ext ->
                    unfilteredExt.members.filter(filter::includeEnumValue).map { EnumValue(it, this, ext) }
                }
            }

        override val values = extensions.flatMap { it.members }

        override fun value(name: String): EnumValue<E, *>? = values.find { name == it.name }

        override fun asTypeExpr() = TypeExpr(unfilteredDef.asTypeExpr(), defs)

        override val possibleObjectTypes = emptySet<Object<out ViaductSchema.Object>>()

        override fun toString() = unfilteredDef.toString()
    }

    class Union<U : ViaductSchema.Union> internal constructor(
        override val unfilteredDef: U,
        private val defs: TypeMap,
        filter: SchemaFilter
    ) : TypeDef<U>,
        ViaductSchema.Union by unfilteredDef {
        override val extensions: List<ViaductSchema.Extension<Union<U>, Object<*>>> by lazy {
            unfilteredDef.extensions.map { unfilteredExt ->
                makeExtension(this, unfilteredDef, unfilteredExt) { _ ->
                    unfilteredExt.members.filter(filter::includeTypeDef).map { defs[it.name] as Object<*> }
                }
            }
        }

        override fun asTypeExpr() = TypeExpr(unfilteredDef.asTypeExpr(), defs)

        override val possibleObjectTypes by lazy { extensions.flatMap { it.members }.toSet() }

        override fun toString() = unfilteredDef.toString()
    }

    sealed interface HasDefaultValue<P : ViaductSchema.Def, H : ViaductSchema.HasDefaultValue> :
        Def<H>,
        ViaductSchema.HasDefaultValue {
        override val containingDef: Def<P>
        override val type: TypeExpr<*>
    }

    class FieldArg<R : ViaductSchema.Record, F : ViaductSchema.Field, A : ViaductSchema.FieldArg> internal constructor(
        override val unfilteredDef: A,
        override val containingDef: Field<R, F>,
        private val defs: TypeMap
    ) : Arg<F, A>,
        ViaductSchema.FieldArg by unfilteredDef {
        override val type = TypeExpr(unfilteredDef.type, defs)

        override fun toString() = unfilteredDef.toString()
    }

    class Field<R : ViaductSchema.Record, F : ViaductSchema.Field> internal constructor(
        override val unfilteredDef: F,
        override val containingDef: Record<R>,
        override val containingExtension: ViaductSchema.Extension<Record<R>, Field<R, *>>,
        defs: TypeMap
    ) : HasDefaultValue<R, F>,
        HasArgs<F>,
        ViaductSchema.Field by unfilteredDef {
        override val args = unfilteredDef.args.map { FieldArg(it, this, defs) }
        override val type = TypeExpr(unfilteredDef.type, defs)
        override val isOverride by lazy { ViaductSchema.isOverride(this) }

        override fun toString() = unfilteredDef.toString()
    }

    sealed interface Record<R : ViaductSchema.Record> :
        TypeDef<R>,
        ViaductSchema.Record {
        override val fields: List<Field<R, out ViaductSchema.Field>>

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field<R, out ViaductSchema.Field> = ViaductSchema.field(this, path)

        override val supers: List<Interface<*>>
        override val unions: List<Union<*>>
    }

    class Interface<I : ViaductSchema.Interface> internal constructor(
        override val unfilteredDef: I,
        private val defs: TypeMap,
        filter: SchemaFilter
    ) : Record<I>,
        ViaductSchema.Interface by unfilteredDef {
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Interface<I>, Field<I, *>>> by lazy {
            val superNames = supers.map { it.name }.toSet()
            unfilteredDef.extensions.map { unfilteredExt ->
                val newSupers =
                    unfilteredExt.supers
                        .filter { superNames.contains(it.name) }
                        .map { defs[it.name] as Interface<*> }
                makeExtension(this, unfilteredDef, unfilteredExt, newSupers) { ext ->
                    unfilteredExt.members
                        .filter {
                            filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef)
                        }.map { Field(it, this, ext, defs) }
                }
            }
        }

        override val fields by lazy { extensions.flatMap { it.members } }

        override fun field(name: String) = super<Record>.field(name)

        override fun field(path: Iterable<String>) = super<Record>.field(path)

        override val supers by lazy { unfilteredDef.filterSupers(filter, defs) }
        override val unions = emptyList<Union<*>>()

        override fun asTypeExpr() = TypeExpr(unfilteredDef.asTypeExpr(), defs)

        override val possibleObjectTypes by lazy {
            unfilteredDef.possibleObjectTypes
                .filter { filter.includePossibleSubType(it, unfilteredDef) }
                .map { defs[it.name] as Object<*> }
                .toSet()
        }

        override fun toString() = unfilteredDef.toString()
    }

    class Object<O : ViaductSchema.Object> internal constructor(
        override val unfilteredDef: O,
        private val defs: TypeMap,
        filter: SchemaFilter
    ) : Record<O>,
        ViaductSchema.Object by unfilteredDef {
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Object<O>, Field<O, *>>> by lazy {
            val superNames = supers.map { it.name }.toSet()
            unfilteredDef.extensions.map { unfilteredExt ->
                val newSupers =
                    unfilteredExt.supers
                        .filter { superNames.contains(it.name) }
                        .map { defs[it.name] as Interface<*> }
                makeExtension(this, unfilteredDef, unfilteredExt, newSupers) { ext ->
                    unfilteredExt.members
                        .filter {
                            filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef)
                        }.map { Field(it, this, ext, defs) }
                }
            }
        }

        override val fields by lazy { extensions.flatMap { it.members } }

        override fun field(name: String) = super<Record>.field(name)

        override fun field(path: Iterable<String>) = super<Record>.field(path)

        override val supers by lazy { unfilteredDef.filterSupers(filter, defs) }
        override val unions by lazy {
            unfilteredDef.unions
                .filter { filter.includeTypeDef(it) }
                .map { defs[it.name] as Union<*> }
        }

        override fun asTypeExpr() = TypeExpr(unfilteredDef.asTypeExpr(), defs)

        override val possibleObjectTypes = setOf(this)

        override fun toString() = unfilteredDef.toString()
    }

    class Input<I : ViaductSchema.Input> internal constructor(
        override val unfilteredDef: I,
        private val defs: TypeMap,
        filter: SchemaFilter
    ) : Record<I>,
        ViaductSchema.Input by unfilteredDef {
        override val supers = emptyList<Interface<*>>()
        override val unions = emptyList<Union<*>>()
        override val extensions: List<ViaductSchema.Extension<Input<I>, Field<I, *>>> by lazy {
            unfilteredDef.extensions.map { unfilteredExt ->
                makeExtension(this, unfilteredDef, unfilteredExt) { ext ->
                    unfilteredExt.members
                        .filter {
                            filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef)
                        }.map { Field(it, this, ext, defs) }
                }
            }
        }

        override val fields by lazy { extensions.flatMap { it.members } }

        override fun field(name: String) = super<Record>.field(name)

        override fun field(path: Iterable<String>) = super<Record>.field(path)

        override fun asTypeExpr() = TypeExpr(unfilteredDef.asTypeExpr(), defs)

        override val possibleObjectTypes = emptySet<Object<out ViaductSchema.Object>>()

        override fun toString() = unfilteredDef.toString()
    }

    class TypeExpr<T : ViaductSchema.TypeExpr> internal constructor(
        private val unfilteredTypeExpr: T,
        private val defs: TypeMap
    ) : ViaductSchema.TypeExpr() {
        override val baseTypeNullable = unfilteredTypeExpr.baseTypeNullable
        override val baseTypeDef: TypeDef<out ViaductSchema.TypeDef>
            get() {
                val baseTypeDefName = unfilteredTypeExpr.baseTypeDef.name
                return defs[baseTypeDefName]
                    ?: throw IllegalStateException("$baseTypeDefName not found")
            }
        override val listNullable = unfilteredTypeExpr.listNullable

        override fun unwrapLists() = TypeExpr(unfilteredTypeExpr.unwrapLists(), defs)

        override fun unwrapList() = unfilteredTypeExpr.unwrapList()?.let { TypeExpr(it, defs) }
    }

    companion object {
        private fun ViaductSchema.HasExtensionsWithSupers<*, *>.filterSupers(
            filter: SchemaFilter,
            defs: TypeMap
        ) = this.supers
            .filter { filter.includeSuper(this, it) && filter.includeTypeDef(it) }
            .map { defs[it.name] as Interface<*> }

        private fun <D : ViaductSchema.TypeDef, M : ViaductSchema.Def> makeExtension(
            def: D,
            unfilteredDef: ViaductSchema.HasExtensions<*, *>,
            unfilteredExt: ViaductSchema.Extension<*, *>,
            memberFactory: (ViaductSchema.Extension<D, M>) -> List<M>
        ) = ViaductSchema.Extension.of(
            def = def,
            memberFactory = memberFactory,
            isBase = unfilteredExt == unfilteredDef.extensions.first(),
            appliedDirectives = unfilteredExt.appliedDirectives,
            sourceLocation = unfilteredExt.sourceLocation
        )

        private fun <D : ViaductSchema.TypeDef, M : ViaductSchema.Def> makeExtension(
            def: D,
            unfilteredDef: ViaductSchema.HasExtensions<*, *>,
            unfilteredExt: ViaductSchema.Extension<*, *>,
            supers: List<Interface<*>>,
            memberFactory: (ViaductSchema.Extension<D, M>) -> List<M>
        ) = ViaductSchema.ExtensionWithSupers.of(
            def = def,
            memberFactory = memberFactory,
            isBase = unfilteredExt == unfilteredDef.extensions.first(),
            appliedDirectives = unfilteredExt.appliedDirectives,
            sourceLocation = unfilteredExt.sourceLocation,
            supers = supers
        )
    }
}

/** See KDoc for [ViaductSchema] for a background. */
interface SchemaFilter {
    fun includeTypeDef(typeDef: ViaductSchema.TypeDef): Boolean

    fun includeField(field: ViaductSchema.Field): Boolean

    fun includeEnumValue(enumValue: ViaductSchema.EnumValue): Boolean

    fun includeSuper(
        record: ViaductSchema.HasExtensionsWithSupers<*, *>,
        superInterface: ViaductSchema.Interface
    ): Boolean

    fun includePossibleSubType(
        possibleSubType: ViaductSchema.HasExtensionsWithSupers<*, *>,
        targetSuperType: ViaductSchema.Interface
    ): Boolean =
        when {
            possibleSubType.supers.contains(targetSuperType) -> includeSuper(possibleSubType, targetSuperType)
            else ->
                possibleSubType.supers.any {
                    includeSuper(possibleSubType, it) && includePossibleSubType(it, targetSuperType)
                }
        }
}
