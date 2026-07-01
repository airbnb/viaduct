package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

private const val CONNECTION_DIRECTIVE = "connection"
private const val EDGE_DIRECTIVE = "edge"

private val ViaductSchema.TypeDef.hasConnectionDirective: Boolean
    get() = this is ViaductSchema.Object && hasAppliedDirective(CONNECTION_DIRECTIVE)

private val ViaductSchema.TypeDef.hasEdgeDirective: Boolean
    get() = this is ViaductSchema.Object && hasAppliedDirective(EDGE_DIRECTIVE)

/**
 * Validates that types with the `@connection` directive have the required `edges` and `pageInfo` fields.
 *
 * Rules enforced:
 * 1. A `@connection` type must have an `edges` field.
 * 2. The `edges` field's base type must have the `@edge` directive.
 * 3. A `@connection` type must have a `pageInfo` field.
 * 4. The `pageInfo` field must be non-null.
 */
class ConnectionTypeStructureRule : ValidationRule(
    id = "ConnectionTypeStructure",
    description = "@connection types must have a valid 'edges' field and a non-null 'pageInfo' field"
) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        if (!obj.hasConnectionDirective) return
        val typeName = obj.name

        val edgesField = obj.field("edges")
        if (edgesField == null) {
            ctx.reportError(
                code = ValidationErrorCodes.CONNECTION_MISSING_EDGES_FIELD,
                message = "@connection type '$typeName' must have an 'edges' field.",
                location = SchemaLocation.ofType(typeName).withSourceLocation(obj.sourceLocation)
            )
        } else {
            val edgesBaseType = edgesField.type.baseTypeDef
            if (!edgesBaseType.hasEdgeDirective) {
                ctx.reportError(
                    code = ValidationErrorCodes.CONNECTION_EDGES_TYPE_NOT_EDGE,
                    message = "@connection type '$typeName' has an 'edges' field, but its base type " +
                        "'${edgesBaseType.name}' does not have the @edge directive.",
                    location = SchemaLocation.ofField(typeName, "edges").withSourceLocation(edgesField.sourceLocation)
                )
            }
        }

        val pageInfoField = obj.field("pageInfo")
        if (pageInfoField == null) {
            ctx.reportError(
                code = ValidationErrorCodes.CONNECTION_MISSING_PAGE_INFO_FIELD,
                message = "@connection type '$typeName' must have a 'pageInfo' field.",
                location = SchemaLocation.ofType(typeName).withSourceLocation(obj.sourceLocation)
            )
        } else if (pageInfoField.type.isNullable) {
            ctx.reportError(
                code = ValidationErrorCodes.CONNECTION_PAGE_INFO_MUST_BE_NON_NULL,
                message = "@connection type '$typeName' must have a non-null 'pageInfo' field " +
                    "(e.g., pageInfo: PageInfo!), but it is declared as nullable.",
                location = SchemaLocation.ofField(typeName, "pageInfo").withSourceLocation(pageInfoField.sourceLocation)
            )
        }
    }
}

/**
 * Validates that types with the `@edge` directive have a `node` field.
 *
 * Rule enforced:
 * 1. A `@edge` type must have a `node` field.
 */
class ConnectionEdgeStructureRule : ValidationRule(
    id = "ConnectionEdgeStructure",
    description = "@edge types must have a 'node' field"
) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        if (!obj.hasEdgeDirective) return
        val typeName = obj.name

        if (obj.field("node") == null) {
            ctx.reportError(
                code = ValidationErrorCodes.EDGE_MISSING_NODE_FIELD,
                message = "@edge type '$typeName' must have a 'node' field.",
                location = SchemaLocation.ofType(typeName).withSourceLocation(obj.sourceLocation)
            )
        }
    }
}

/**
 * Validates that the PageInfo type referenced by `@connection` types conforms to the Relay Connection spec.
 *
 * For each `@connection` type's `pageInfo` field type, enforces:
 * - `hasNextPage: Boolean!` — must be non-null Boolean; indicates if more items exist after this page
 * - `hasPreviousPage: Boolean!` — must be non-null Boolean; indicates if more items exist before this page
 * - `startCursor` — must be **nullable**; must be null when the connection has no edges
 * - `endCursor` — must be **nullable**; must be null when the connection has no edges
 *
 * An empty connection (edges: []) means there is no first or last edge, so startCursor and endCursor
 * must be null — the spec does not allow sentinel values like "" or non-nullable cursor fields.
 * Relay clients assume pageInfo is always present and that cursors are nullable, not magical strings.
 */
class ConnectionPageInfoRule : ValidationRule(
    id = "ConnectionPageInfo",
    description = "PageInfo types referenced by @connection types must conform to the Relay Connection spec"
) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        if (!obj.hasConnectionDirective) return

        // If pageInfo field is absent, ConnectionTypeStructureRule already reported it.
        val pageInfoField = obj.field("pageInfo") ?: return
        val pageInfoType = pageInfoField.type.baseTypeDef as? ViaductSchema.Object ?: return

        val connectionTypeName = obj.name
        val pageInfoTypeName = pageInfoType.name

        validateNonNullBooleanField(ctx, pageInfoType, "hasNextPage", connectionTypeName, pageInfoTypeName)
        validateNonNullBooleanField(ctx, pageInfoType, "hasPreviousPage", connectionTypeName, pageInfoTypeName)
        validateNullableCursorField(ctx, pageInfoType, "startCursor", connectionTypeName, pageInfoTypeName)
        validateNullableCursorField(ctx, pageInfoType, "endCursor", connectionTypeName, pageInfoTypeName)
        validateNoExtraFields(ctx, pageInfoType, connectionTypeName, pageInfoTypeName)
        validateNoFieldArguments(ctx, pageInfoType, connectionTypeName, pageInfoTypeName)
    }

    private fun validateNonNullBooleanField(
        ctx: ValidationContext,
        pageInfoType: ViaductSchema.Object,
        fieldName: String,
        connectionTypeName: String,
        pageInfoTypeName: String,
    ) {
        val field = pageInfoType.field(fieldName)
        if (field == null) {
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD,
                message = "PageInfo type '$pageInfoTypeName' (referenced by @connection type '$connectionTypeName') " +
                    "must have a '$fieldName' field.",
                location = SchemaLocation.ofType(pageInfoTypeName)
            )
            return
        }
        if (field.type.isNullable || field.type.baseTypeDef.name != "Boolean") {
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_BOOLEAN_FIELD_MUST_BE_NON_NULL_BOOLEAN,
                message = "PageInfo type '$pageInfoTypeName'.$fieldName must be 'Boolean!' (non-null Boolean), " +
                    "as required by the Relay Connection spec.",
                location = SchemaLocation.ofField(pageInfoTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }
    }

    private fun validateNullableCursorField(
        ctx: ValidationContext,
        pageInfoType: ViaductSchema.Object,
        fieldName: String,
        connectionTypeName: String,
        pageInfoTypeName: String,
    ) {
        val field = pageInfoType.field(fieldName)
        if (field == null) {
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD,
                message = "PageInfo type '$pageInfoTypeName' (referenced by @connection type '$connectionTypeName') " +
                    "must have a '$fieldName' field.",
                location = SchemaLocation.ofType(pageInfoTypeName)
            )
            return
        }
        if (!field.type.isNullable) {
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_CURSOR_MUST_BE_NULLABLE,
                message = "PageInfo type '$pageInfoTypeName'.$fieldName must be nullable " +
                    "(e.g., String instead of String!). An empty connection has no edges, so " +
                    "startCursor and endCursor must be null per the Relay Connection spec.",
                location = SchemaLocation.ofField(pageInfoTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }
    }

    private fun validateNoExtraFields(
        ctx: ValidationContext,
        pageInfoType: ViaductSchema.Object,
        connectionTypeName: String,
        pageInfoTypeName: String,
    ) {
        val extraFields = pageInfoType.fields
            .map { it.name }
            .filter { it !in STANDARD_PAGE_INFO_FIELDS }
        if (extraFields.isNotEmpty()) {
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_EXTRA_FIELDS,
                message = "PageInfo type '$pageInfoTypeName' (referenced by @connection type '$connectionTypeName') " +
                    "must not have custom fields. " +
                    "Found extra fields: ${extraFields.joinToString(", ") { "'$it'" }}. " +
                    "Only the four standard Relay fields are allowed: " +
                    "${STANDARD_PAGE_INFO_FIELDS.joinToString(", ") { "'$it'" }}.",
                location = SchemaLocation.ofType(pageInfoTypeName)
            )
        }
    }

    private fun validateNoFieldArguments(
        ctx: ValidationContext,
        pageInfoType: ViaductSchema.Object,
        connectionTypeName: String,
        pageInfoTypeName: String,
    ) {
        pageInfoType.fields.filter { it.hasArgs }.forEach { field ->
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_FIELD_HAS_ARGS,
                message = "PageInfo type '$pageInfoTypeName'.'${field.name}' " +
                    "(referenced by @connection type '$connectionTypeName') " +
                    "must not have arguments. PageInfo fields must be plain scalar fields.",
                location = SchemaLocation.ofField(pageInfoTypeName, field.name).withSourceLocation(field.sourceLocation)
            )
        }
    }

    companion object {
        private val STANDARD_PAGE_INFO_FIELDS = setOf("hasNextPage", "hasPreviousPage", "startCursor", "endCursor")
    }
}

/**
 * Extends [ConnectionPageInfoRule] with additional structural checks:
 * - PageInfo must not implement any interfaces
 * - PageInfo must not be a member of any union
 *
 * These checks are intentionally separate because implementing interfaces is valid GraphQL and
 * existing Viaduct deployments may use patterns like `type PageInfo implements IPageInfo`.
 * Enable this rule when a stricter schema contract is desired (e.g., Gradle-based validation for OSS users).
 */
class StrictConnectionPageInfoRule : ValidationRule(
    id = "StrictConnectionPageInfo",
    description = "PageInfo types must not implement interfaces or be union members"
) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        if (!obj.hasConnectionDirective) return

        val pageInfoField = obj.field("pageInfo") ?: return
        val pageInfoType = pageInfoField.type.baseTypeDef as? ViaductSchema.Object ?: return

        val connectionTypeName = obj.name
        val pageInfoTypeName = pageInfoType.name

        if (pageInfoType.supers.isNotEmpty()) {
            val ifaceNames = pageInfoType.supers.joinToString(", ") { "'${it.name}'" }
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_IMPLEMENTS_INTERFACE,
                message = "PageInfo type '$pageInfoTypeName' " +
                    "(referenced by @connection type '$connectionTypeName') " +
                    "must not implement any interfaces, but implements: $ifaceNames.",
                location = SchemaLocation.ofType(pageInfoTypeName)
            )
        }

        if (pageInfoType.unions.isNotEmpty()) {
            val unionNames = pageInfoType.unions.joinToString(", ") { "'${it.name}'" }
            ctx.reportError(
                code = ValidationErrorCodes.PAGE_INFO_IN_UNION,
                message = "PageInfo type '$pageInfoTypeName' " +
                    "(referenced by @connection type '$connectionTypeName') " +
                    "must not be a member of any union, but is a member of: $unionNames.",
                location = SchemaLocation.ofType(pageInfoTypeName)
            )
        }
    }
}

/**
 * Validates that pagination arguments on connection-returning fields are nullable.
 *
 * Pagination argument fields (`first`, `after`, `last`, `before`) must be nullable in the schema
 * to satisfy the `ConnectionArguments` interface. Non-nullable types generate primitive JVM getters
 * (e.g., `int getFirst()`) that do not satisfy the boxed interface method (e.g., `Integer getFirst()`),
 * causing `AbstractMethodError` at runtime during codegen.
 */
class ConnectionArgumentsNullabilityRule : ValidationRule(
    id = "ConnectionArgumentsNullability",
    description = "Pagination arguments (first, after, last, before) on @connection-returning fields must be nullable"
) {
    override fun visitFieldArg(
        ctx: ValidationContext,
        arg: ViaductSchema.FieldArg
    ) {
        if (arg.name !in CONNECTION_ARG_NAMES) return

        val field = arg.containingDef
        if (!field.type.baseTypeDef.hasConnectionDirective) return

        if (!arg.type.isNullable) {
            val typeName = field.containingDef.name
            val fieldName = field.name
            ctx.reportError(
                code = ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE,
                message = "Connection argument '${arg.name}' on field '$typeName.$fieldName' must be nullable " +
                    "to satisfy the ConnectionArguments interface, but it is declared as non-null.",
                location = SchemaLocation(listOf(typeName, fieldName, arg.name))
                    .withSourceLocation(arg.sourceLocation)
            )
        }
    }

    companion object {
        private val CONNECTION_ARG_NAMES = setOf("first", "after", "last", "before")
    }
}
