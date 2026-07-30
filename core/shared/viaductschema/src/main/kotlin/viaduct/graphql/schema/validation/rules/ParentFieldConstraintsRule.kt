package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule
import viaduct.graphql.utils.DefaultSchemaFactory.DefaultDirective

/**
 * Validates correct usage of fields marked with @parent.
 *
 * Parent fields are resolved by the engine from execution ancestry. They must be declared directly
 * on object types because field directives are not inherited from interfaces, and they cannot have
 * explicit resolver semantics, argument/list shapes that imply normal data fetching, or ambiguous
 * schema parentage.
 */
class ParentFieldConstraintsRule(
    internal val conflictingFieldDirectives: Set<String> = setOf(DefaultDirective.RESOLVER.directiveName),
    private val resolverDirectiveName: String = DefaultDirective.RESOLVER.directiveName,
) : ValidationRule(
        id = "ParentFieldConstraints",
        description = "@$DIRECTIVE_NAME fields must be declared on object types and be no-arg, non-list composite fields without resolver directives, selective parent producers, or ambiguous parent producers"
    ) {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        if (!field.hasAppliedDirective(DIRECTIVE_NAME)) return

        val parentType = field.containingDef
        val parentTypeName = parentType.name
        val fieldName = field.name
        val fieldBaseType = field.type.baseTypeDef

        if (!parentType.isOutput) {
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_ON_NON_OUTPUT_TYPE,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME, but parent fields can only be declared on object types.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (parentType is ViaductSchema.Interface) {
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_ON_INTERFACE,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME, but parent fields cannot be declared on interfaces. " +
                    "Remove the field from the interface contract and declare it only on object types.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (field.isOverride) {
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_IMPLEMENTED_INTERFACE_FIELD,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME but implements an interface field. " +
                    "@$DIRECTIVE_NAME is not allowed on fields inherited from interfaces.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (!fieldBaseType.isComposite) {
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_TYPE_NOT_COMPOSITE,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME but returns '${fieldBaseType.name}'. " +
                    "@$DIRECTIVE_NAME fields must return an object, interface, or union type.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (field.hasArgs) {
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_HAS_ARGS,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME but has arguments. @$DIRECTIVE_NAME fields cannot take arguments.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (field.type.isList) {
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_IS_LIST,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME but returns a list. @$DIRECTIVE_NAME fields cannot return lists.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        val conflictingDirectives = field.appliedDirectives
            .map { it.name }
            .filter { it in conflictingFieldDirectives }
            .distinct()
        if (conflictingDirectives.isNotEmpty()) {
            val names = conflictingDirectives.joinToString(", ") { "@$it" }
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_HAS_CONFLICTING_RESOLVER,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME but also carries directive(s) that override field resolution: $names. " +
                    "Remove the explicit resolver directive — @$DIRECTIVE_NAME fields are resolved by the engine.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        val selectiveResolver = nearestSelectiveResolverForParentTarget(ctx, fieldBaseType)
        if (selectiveResolver != null) {
            ctx.reportError(
                code = ValidationErrorCodes.PARENT_FIELD_TARGET_HAS_SELECTIVE_RESOLVER,
                message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME and returns '${fieldBaseType.name}', " +
                    "but the nearest resolver that can produce that parent type is selective: $selectiveResolver. " +
                    "@$DIRECTIVE_NAME fields cannot target parent objects produced by selective resolvers.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (parentType is ViaductSchema.OutputRecord && fieldBaseType.isComposite) {
            val parentProducerFields = parentProducerFields(
                ctx = ctx,
                childType = parentType,
            )
            if (!hasUniqueCompatibleParentProducer(parentProducerFields, fieldBaseType)) {
                val producerFields = parentProducerFields.joinToString(", ") { it.coordinate() }.ifEmpty { "<none>" }
                ctx.reportError(
                    code = ValidationErrorCodes.PARENT_FIELD_CHILD_HAS_AMBIGUOUS_PARENT,
                    message = "Field $parentTypeName.$fieldName is marked @$DIRECTIVE_NAME and returns '${fieldBaseType.name}', " +
                        "but '$parentTypeName' does not have exactly one schema field that can produce it from that declared parent type. " +
                        "Producer field(s): $producerFields. " +
                        "@$DIRECTIVE_NAME fields require a unique non-@$DIRECTIVE_NAME producer field for '$parentTypeName', " +
                        "and that producer field must come from '${fieldBaseType.name}' or one of its possible object types.",
                    location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
                )
            }
        }
    }

    private fun parentProducerFields(
        ctx: ValidationContext,
        childType: ViaductSchema.OutputRecord,
    ): List<ViaductSchema.Field> {
        val childTypes = (listOf(childType) + childType.possibleObjectTypes).distinct()

        return childTypes
            .flatMap { ctx.reverseSchema.inboundFields(it) }
            .asSequence()
            .filterNot { it.hasAppliedDirective(DIRECTIVE_NAME) }
            .filter { it.containingDef is ViaductSchema.OutputRecord }
            .distinctBy { it.coordinate() }
            .sortedBy { it.coordinate() }
            .toList()
    }

    private fun hasUniqueCompatibleParentProducer(
        producerFields: List<ViaductSchema.Field>,
        parentTargetType: ViaductSchema.TypeDef,
    ): Boolean =
        producerFields.singleOrNull()
            ?.let { parentProducerTypeFitsTarget(it.containingDef as ViaductSchema.OutputRecord, parentTargetType.possibleObjectTypes) }
            ?: false

    private fun parentProducerTypeFitsTarget(
        producerType: ViaductSchema.OutputRecord,
        parentTargetObjectTypes: Set<ViaductSchema.Object>,
    ): Boolean = parentTargetObjectTypes.containsAll(producerType.possibleObjectTypes)

    private fun nearestSelectiveResolverForParentTarget(
        ctx: ValidationContext,
        targetType: ViaductSchema.TypeDef,
    ): String? {
        if (!targetType.isComposite) return null

        val targetTypes = (listOf(targetType) + targetType.possibleObjectTypes).distinct()
        return targetTypes
            .firstNotNullOfOrNull { type ->
                if (type.isSelectiveResolver()) {
                    type.name
                } else {
                    nearestSelectiveInboundFieldResolver(ctx, type, visitedTypes = setOf(type))
                }
            }
    }

    private fun nearestSelectiveInboundFieldResolver(
        ctx: ValidationContext,
        type: ViaductSchema.TypeDef,
        visitedTypes: Set<ViaductSchema.TypeDef>,
    ): String? =
        ctx.reverseSchema.inboundFields(type)
            .asSequence()
            .filterNot { it.hasAppliedDirective(DIRECTIVE_NAME) }
            .filter { it.containingDef is ViaductSchema.OutputRecord }
            .firstNotNullOfOrNull { inboundField ->
                if (inboundField.hasAppliedDirective(resolverDirectiveName)) {
                    inboundField.coordinate().takeIf { inboundField.isSelectiveResolver() }
                } else {
                    val containingType = inboundField.containingDef as ViaductSchema.TypeDef
                    if (containingType in visitedTypes) {
                        null
                    } else {
                        nearestSelectiveInboundFieldResolver(ctx, containingType, visitedTypes + containingType)
                    }
                }
            }

    private fun ViaductSchema.Def.isSelectiveResolver(): Boolean {
        val resolverDirective = appliedDirectives.firstOrNull { it.name == resolverDirectiveName } ?: return false
        return listOf("isSelective", "selective").any { argName ->
            (resolverDirective.arguments[argName] as? ViaductSchema.BooleanLiteral)?.value == true
        }
    }

    private fun ViaductSchema.Field.coordinate(): String = "${containingDef.name}.$name"

    companion object {
        const val DIRECTIVE_NAME = "parent"
    }
}
