package viaduct.graphql.schema

/**
 * Provides reverse (inbound) navigation over a [ViaductSchema].
 *
 * [ViaductSchema] provides efficient forward navigation: from a type
 * to its fields, from a field to its type, from a record to its
 * supers, and so on.  This class complements that with reverse
 * navigation: given a target definition, which other definitions
 * reference it?
 *
 * ### Syntactic vs. semantic
 *
 * [ViaductSchema] already provides limited reverse navigation through
 * [ViaductSchema.Object.unions] and
 * [ViaductSchema.TypeDef.possibleObjectTypes].  Those properties are
 * *semantic*: [ViaductSchema.TypeDef.possibleObjectTypes] on an
 * [ViaductSchema.Interface] returns only [ViaductSchema.Object] types
 * (the concrete types that could appear at runtime).  By contrast,
 * [ViaductReverseSchema] is *syntactic*: [inboundTypeDefs] on an
 * [ViaductSchema.Interface] returns every [ViaductSchema.OutputRecord]
 * that lists it in its `supers` clause — both
 * [ViaductSchema.Object] and [ViaductSchema.Interface] types —
 * because both syntactically reference the target through an
 * `implements` declaration.  Where the syntactic and semantic views
 * agree (e.g., union membership), both APIs produce the same result.
 *
 * ### Direct-reference catalog
 *
 * A definition S directly references a target T when S contains a
 * structural pointer to T in the schema graph:
 *
 * | Source (S)            | Target (T)  | Forward path                   |
 * |-----------------------|-------------|--------------------------------|
 * | [ViaductSchema.Field] | [ViaductSchema.TypeDef] | `field.type.baseTypeDef` |
 * | [ViaductSchema.FieldArg] | [ViaductSchema.TypeDef] | `arg.type.baseTypeDef` |
 * | [ViaductSchema.DirectiveArg] | [ViaductSchema.TypeDef] | `arg.type.baseTypeDef` |
 * | [ViaductSchema.Object], [ViaductSchema.Interface] | [ViaductSchema.Interface] | `outputRecord.supers` |
 * | [ViaductSchema.Union] | [ViaductSchema.Object] | `union.possibleObjectTypes` |
 * | [ViaductSchema.Def]   | [ViaductSchema.Directive] | `def.appliedDirectives[].directive` |
 *
 * "Direct reference" does not chase containment.  If field `Foo.bar`
 * has type `Baz`, then `Foo.bar` (the [ViaductSchema.Field]) directly
 * references `Baz`, but the containing type `Foo` does not.
 *
 * ### Method organization
 *
 * [inboundDefs] is the core method: it returns all definitions that
 * directly reference a target, across every edge type above.
 *
 * The remaining `inbound*` methods are more strongly typed
 * convenience filters that narrow the result by the type of the
 * referencing definition (e.g., [inboundFields] returns only
 * [ViaductSchema.Field]s).  All `inbound*` methods accept
 * [ViaductSchema.Def] as the target parameter so they can filter
 * across all edge types uniformly, including directive application.
 *
 * [referencingTopLevelDefs] collapses each inbound definition to
 * its containing [ViaductSchema.TopLevelDef], producing a view of
 * the schema as a graph of top-level definitions connected by
 * "uses" edges.  See its own documentation for the containment
 * chain details.
 *
 * All methods return an empty collection — rather than throwing —
 * when applied to a target for which the query is structurally
 * meaningless.  For example, [inboundFields] on a
 * [ViaductSchema.Field] target returns an empty collection because
 * fields are never the base type of another field's type expression.
 *
 * ### Schema affinity
 *
 * A [ViaductReverseSchema] is bound to a specific [ViaductSchema]
 * instance (exposed via [schema]).  All `inbound*` and
 * `referencing*` methods require that the [target] belong to
 * that schema.  Passing a [ViaductSchema.Def] from a different
 * schema is a programming error and will throw
 * [IllegalArgumentException].  This follows from
 * [ViaductSchema]'s closed-world invariant: object identity is
 * equality within a schema, so a definition from another schema
 * can never match any indexed reference.
 */
interface ViaductReverseSchema {
    /** The schema that this object has transposed. */
    val schema: ViaductSchema

    // =================================================================
    // Core inbound
    // =================================================================

    /**
     * All definitions that directly reference [target], across every
     * edge type in the direct-reference catalog.
     *
     * For a [ViaductSchema.TypeDef] target this includes
     * [ViaductSchema.Field]s, [ViaductSchema.FieldArg]s, and
     * [ViaductSchema.DirectiveArg]s whose `type.baseTypeDef` is
     * [target], plus [ViaductSchema.OutputRecord]s that implement it
     * (if [target] is an [ViaductSchema.Interface]) and
     * [ViaductSchema.Union]s that contain it (if [target] is an
     * [ViaductSchema.Object]).
     *
     * For a [ViaductSchema.Directive] target this includes every
     * [ViaductSchema.Def] that has applied the directive.
     *
     * @throws IllegalArgumentException if [target] does not belong
     *   to [schema]
     */
    fun inboundDefs(target: ViaductSchema.Def): Collection<ViaductSchema.Def>

    // =================================================================
    // Filtered inbound — by referencing-definition type
    // =================================================================

    /** @throws IllegalArgumentException if [target] does not belong to [schema] */
    fun inboundFields(target: ViaductSchema.Def): Collection<ViaductSchema.Field>

    /** @throws IllegalArgumentException if [target] does not belong to [schema] */
    fun inboundFieldArgs(target: ViaductSchema.Def): Collection<ViaductSchema.FieldArg>

    /** @throws IllegalArgumentException if [target] does not belong to [schema] */
    fun inboundDirectiveArgs(target: ViaductSchema.Def): Collection<ViaductSchema.DirectiveArg>

    /** @throws IllegalArgumentException if [target] does not belong to [schema] */
    fun inboundTypeDefs(target: ViaductSchema.Def): Collection<ViaductSchema.TypeDef>

    // =================================================================
    // Referencing — top-level-definition-level graph
    // =================================================================

    /**
     * [ViaductSchema.TopLevelDef]s that contain (directly or through
     * the containment chain) a definition that directly references
     * [target].
     *
     * This collapses the fine-grained reference graph to the
     * top-level-definition level — a view of the schema as a graph
     * where nodes are [ViaductSchema.TypeDef]s and
     * [ViaductSchema.Directive]s, and edges represent any structural
     * "uses" relationship (field type references, argument type
     * references, implements clauses, union membership, and
     * directive application).
     *
     * The containment chain for each inbound definition type:
     *
     * - [ViaductSchema.Field] → its `containingDef`
     *   (a [ViaductSchema.Record])
     * - [ViaductSchema.FieldArg] → its field's `containingDef`
     *   (a [ViaductSchema.Record])
     * - [ViaductSchema.DirectiveArg] → its `containingDef`
     *   (a [ViaductSchema.Directive])
     * - [ViaductSchema.OutputRecord] (via implements) → itself
     * - [ViaductSchema.Union] (via membership) → itself
     * - [ViaductSchema.EnumValue] (via directive application) →
     *   its `containingDef` (a [ViaductSchema.Enum])
     * - [ViaductSchema.TopLevelDef] (via directive application)
     *   → itself
     *
     * Results are deduplicated: if multiple fields of the same
     * record reference [target], that record appears only once.
     *
     * @throws IllegalArgumentException if [target] does not belong
     *   to [schema]
     */
    fun referencingTopLevelDefs(target: ViaductSchema.TopLevelDef): Collection<ViaductSchema.TopLevelDef>

    companion object {
        /**
         * Creates a standard implementation of
         * [ViaductReverseSchema] by walking [schema] once to build
         * the reverse index.
         *
         * Some [ViaductSchema] implementations may provide their
         * own optimized [ViaductReverseSchema] — for example, by
         * maintaining the reverse index incrementally during schema
         * construction.
         */
        fun from(schema: ViaductSchema): ViaductReverseSchema = DefaultViaductReverseSchema(schema)
    }
}
