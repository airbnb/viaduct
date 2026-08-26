package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

/**
 * Selects schema elements copied by [ViaductSchemaBuilder.filteredCopy].
 *
 * Filtering is hierarchical. When a source is rejected, its contained
 * elements are not visited. All elements are accepted by default.
 *
 * Filtering is a builder-editing step, so the returned builder is not guaranteed to be
 * buildable until callers add any replacement definitions or other updates they need.
 *
 * Rejecting a configured operation root removes that root from the copied builder. Callers can
 * add a replacement definition with the same name if they want to retain the root.
 * Rejecting a directive definition argument also leaves that argument in copied applications,
 * so callers must replace the directive or its applications before building.
 *
 * Applied-directive arguments are copied together with their directive. To
 * modify arguments, reject the applied directive and add its replacement to
 * the returned builder.
 */
interface ViaductSchemaBuilderFilter {
    fun filterTopLevelDef(source: ViaductSchema.TopLevelDef): Boolean = true

    /**
     * Filters a non-base type extension. Base extensions are governed by
     * [filterTopLevelDef], but their contents are passed to the applicable
     * filters below.
     */
    fun filterExtension(source: ViaductSchema.Extension<*, *>): Boolean = true

    fun filterField(source: ViaductSchema.Field): Boolean = true

    fun filterArg(source: ViaductSchema.Arg): Boolean = true

    fun filterEnumValue(source: ViaductSchema.EnumValue): Boolean = true

    fun filterSupertype(
        source: ViaductSchema.ExtensionWithSupers<*, *>,
        supertype: ViaductSchema.Interface,
    ): Boolean = true

    fun filterMember(
        source: ViaductSchema.Extension<ViaductSchema.Union, ViaductSchema.Object>,
        member: ViaductSchema.Object,
    ): Boolean = true

    fun filterDirectiveLocation(
        source: ViaductSchema.Directive,
        location: ViaductSchema.Directive.Location,
    ): Boolean = true

    /**
     * Filters an applied directive owned by a field, argument, or enum value.
     * Type-definition directives are owned by extensions and are passed to
     * [filterExtensionAppliedDirective].
     */
    fun filterAppliedDirective(
        source: ViaductSchema.Def,
        appliedDirective: ViaductSchema.AppliedDirective<*>,
    ): Boolean = true

    /**
     * Filters an applied directive owned by a base or non-base type
     * extension.
     */
    fun filterExtensionAppliedDirective(
        source: ViaductSchema.Extension<*, *>,
        appliedDirective: ViaductSchema.AppliedDirective<*>,
    ): Boolean = true
}
