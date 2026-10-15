package viaduct.tenant.codegen.cli

import graphql.analysis.QueryTraversalOptions
import graphql.analysis.QueryVisitorFieldEnvironment
import graphql.analysis.QueryVisitorStub
import graphql.language.Directive
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.validation.QueryComplexityLimits
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import viaduct.engine.api.parse.DocumentParser
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.graphql.utils.SelectionsParserUtils
import viaduct.graphql.utils.ViaductQueryTraverser
import viaduct.graphql.utils.collectVariableReferencesFollowingFragments
import viaduct.graphql.utils.isParentField
import viaduct.tenant.codegen.ksp.ResolverParams

/**
 * Validates a resolver's required selection sets against the tenant compilation schema. This
 * validator receives the per-tenant build-time schema produced by
 * `CompilationSchemaInfo.schema_sdl` in Bazel builds and the source-preserving application central
 * schema in OSS Gradle builds. It does not consume the runtime base schema.
 *
 * The runtime base schema filters out tenant-local fields, so it would reject valid same-tenant
 * tenant-local RSS selections. Bazel's tenant compilation schema is the contract for what that
 * tenant's generated resolver code can select. OSS Gradle does not build a filtered per-tenant
 * schema, so its central schema establishes GraphQL validity and tenant-local ownership but cannot
 * detect fields outside a narrower tenant compilation surface.
 *
 * Runs at assembly scope (successor to the per-leaf KSP `ValidateResolverFragments`), where the
 * schema is loaded once per tenant and cross-leaf `@GraphQLFragment` spreads are already resolved,
 * so it is the authoritative GraphQL schema-validation pass for required selection sets.
 *
 * Tenant-local field ownership comes from the source-location-preserving [ViaductSchema] paired
 * with the GraphQL Java schema, because generated SDL does not reliably carry ownership metadata.
 *
 * Callers supply [normalizedSelections] (entry selections, shorthand wrapped) for the type-shape
 * check and [expandedSelections] (with cross-leaf fragments appended) for the schema check.
 */
internal class RequiredSelectionSetValidator(
    private val tenantCompilationSchema: GraphQLSchema,
    private val currentTenantModule: String? = null,
    tenantCompilationViaductSchema: ViaductSchema? = null,
    forbiddenSelectionDirectives: Set<String> = emptySet(),
) {
    private val validator = Validator()
    private val forbiddenDirectiveChecker = ForbiddenSelectionDirectiveChecker(tenantCompilationSchema, forbiddenSelectionDirectives)
    private val fieldOwnershipIndex = if (currentTenantModule == null) {
        FieldOwnershipIndex.empty()
    } else {
        FieldOwnershipIndex.fromViaductSchema(
            requireNotNull(tenantCompilationViaductSchema) {
                "tenantCompilationViaductSchema is required when tenant-local ownership validation is enabled"
            },
            ::moduleNameFromSourceName,
        )
    }

    fun validate(
        normalizedSelections: String,
        expandedSelections: String,
        typeName: String,
        isQuery: Boolean,
        field: ResolverParams.Field,
        errors: MutableList<String>,
    ) {
        val normalizedDocument = try {
            DocumentParser.parse(normalizedSelections)
        } catch (e: Exception) {
            errors.add("Unable to extract the fragment type name for ${field.implFqn} (${field.typeName}.${field.fieldName}): ${e.message}")
            null
        }
        if (normalizedDocument != null) {
            validateFragmentType(normalizedDocument, typeName, isQuery, field, errors)
        }

        val expandedDocument = DocumentParser.parse(expandedSelections)
        if (validateAgainstSchema(expandedDocument, field, errors)) {
            validateSelectedFields(expandedDocument, typeName, field, errors)
        }
    }

    private fun validateFragmentType(
        normalizedDocument: Document,
        typeName: String,
        isQuery: Boolean,
        field: ResolverParams.Field,
        errors: MutableList<String>,
    ) {
        val fragmentTypeName = try {
            val fragments = normalizedDocument.getDefinitionsOfType(FragmentDefinition::class.java)
            SelectionsParserUtils.findEntryPointFragment(fragments).typeCondition.name
        } catch (e: Exception) {
            errors.add("Unable to extract the fragment type name for ${field.implFqn} (${field.typeName}.${field.fieldName}): ${e.message}")
            return
        }

        if (isQuery) {
            val queryTypeName = tenantCompilationSchema.queryType.name
            if (isMutationExecutionParentType(field.typeName)) {
                errors.add(
                    "Mutation resolver ${field.implFqn} (${field.typeName}.${field.fieldName}) must not set queryValueFragment. Use ctx.query inside resolve instead.",
                )
            } else if (fragmentTypeName != queryTypeName) {
                errors.add(
                    "queryValueFragment for ${field.implFqn} must be on the root query type ($queryTypeName), but found type $fragmentTypeName",
                )
            }
        } else {
            if (isMutationExecutionParentType(typeName)) {
                errors.add("Mutation resolver ${field.implFqn} should not set objectValueFragment.")
            } else if (fragmentTypeName != typeName) {
                errors.add(
                    "objectValueFragment for ${field.implFqn} must be on the parent type ($typeName), but found type $fragmentTypeName",
                )
            }
        }
    }

    /**
     * True if [typeName] is the mutation root or a `@namespaceType` object reachable from it — i.e. a
     * type whose fields execute as mutations, which must not declare required selection sets.
     */
    private fun isMutationExecutionParentType(typeName: String): Boolean {
        val mutationType = tenantCompilationSchema.mutationType ?: return false
        if (typeName == mutationType.name) return true

        val visited = mutableSetOf<String>()

        fun walkNamespaceFields(parent: GraphQLObjectType): Boolean {
            for (field in parent.fieldDefinitions) {
                val baseType = GraphQLTypeUtil.unwrapAll(field.type)
                if (
                    baseType is GraphQLObjectType &&
                    baseType.hasAppliedDirective(DefaultSchemaFactory.DefaultDirective.NAMESPACE_TYPE.directiveName) &&
                    visited.add(baseType.name)
                ) {
                    if (baseType.name == typeName || walkNamespaceFields(baseType)) {
                        return true
                    }
                }
            }
            return false
        }

        return walkNamespaceFields(mutationType)
    }

    private fun validateAgainstSchema(
        expandedDocument: Document,
        field: ResolverParams.Field,
        errors: MutableList<String>,
    ): Boolean {
        val schemaErrors = validator.validateDocument(tenantCompilationSchema, expandedDocument, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE)
            .filterNot { FILTERED_ERRORS.contains(it.validationErrorType as ValidationErrorType) }

        schemaErrors.forEach { error ->
            val baseMessage = "Fragment validation failed for ${field.implFqn} (${field.typeName}.${field.fieldName}): ${error.message}"
            errors.add(
                if (COMPILATION_SCHEMA_RELATED_ERRORS.contains(error.validationErrorType as ValidationErrorType)) {
                    "$baseMessage. This may be caused by a missing field or type in your tenant's compilation schema. " +
                        "See: https://developers.a.musta.ch/docs/default/component/viaduct/tenant-compilation-schemas/"
                } else {
                    baseMessage
                },
            )
        }
        return schemaErrors.isEmpty()
    }

    private fun validateSelectedFields(
        expandedDocument: Document,
        typeName: String,
        field: ResolverParams.Field,
        errors: MutableList<String>,
    ) {
        val rootParentType = tenantCompilationSchema.getType(typeName) as? GraphQLCompositeType ?: return
        val fragments = expandedDocument.getDefinitionsOfType(FragmentDefinition::class.java)
        val entryFragment = try {
            SelectionsParserUtils.findEntryPointFragment(fragments)
        } catch (_: Exception) {
            return
        }
        val fragmentsByName = fragments.associateBy { it.name }
        val tenantLocalViolations = linkedSetOf<TenantLocalFieldAccessViolation>()

        if (currentTenantModule != null) {
            ViaductQueryTraverser
                .newQueryTraverser()
                .schema(tenantCompilationSchema)
                .root(entryFragment)
                .rootParentType(rootParentType)
                .fragmentsByName(fragmentsByName)
                .options(QueryTraversalOptions.defaultOptions().coerceFieldArguments(false))
                .build()
                .visitPreOrder(
                    object : QueryVisitorStub() {
                        override fun visitField(env: QueryVisitorFieldEnvironment) {
                            env.findTenantLocalViolation()?.let(tenantLocalViolations::add)
                        }
                    },
                )
        }
        val parentFieldViolations = findParentFieldViolations(entryFragment, rootParentType, fragmentsByName)

        tenantLocalViolations.forEach { violation ->
            errors.add(
                "Required selection set for ${field.implFqn} (${field.typeName}.${field.fieldName}) " +
                    "references tenant-local field ${violation.fieldCoordinate} owned by ${violation.owner ?: "unknown tenant module"} " +
                    "from tenant module $currentTenantModule. Tenant-local fields may only be selected by resolvers in the owning tenant module.",
            )
        }
        parentFieldViolations.directivesByField.forEach { (fieldCoordinate, directiveNames) ->
            errors.add(
                "Required selection set for ${field.implFqn} (${field.typeName}.${field.fieldName}) " +
                    "applies ${directiveNames.joinToString(", ") { "@$it" }} to @parent field $fieldCoordinate. " +
                    "Directives may not be applied to a @parent field or to inline fragments or fragment spreads that directly contain it.",
            )
        }
        parentFieldViolations.variablesByField.forEach { (fieldCoordinate, variableNames) ->
            errors.add(
                "Required selection set for ${field.implFqn} (${field.typeName}.${field.fieldName}) " +
                    "uses ${variableNames.joinToString(", ") { "\$$it" }} beneath @parent field $fieldCoordinate. " +
                    "Required selection sets may not use variables beneath a @parent field.",
            )
        }
        forbiddenDirectiveChecker.violations(entryFragment, rootParentType, fragmentsByName).forEach { violation ->
            errors.add("Required selection set for ${field.implFqn} (${field.typeName}.${field.fieldName}) ${violation.message()}")
        }
    }

    private fun QueryVisitorFieldEnvironment.findTenantLocalViolation(): TenantLocalFieldAccessViolation? {
        val tenantModule = currentTenantModule ?: return null
        if (!fieldDefinition.isTenantLocalField()) return null
        val owner = fieldOwnershipIndex.ownerOf(fieldsContainer.name, field.name)
        return if (owner == tenantModule) null else TenantLocalFieldAccessViolation(fieldCoordinate(), owner)
    }

    // Expands every spread occurrence with its own ancestry: graphql-java's traverser visits a shared fragment only once.
    private fun findParentFieldViolations(
        entryFragment: FragmentDefinition,
        rootParentType: GraphQLCompositeType,
        fragmentsByName: Map<String, FragmentDefinition>,
    ): ParentFieldViolations {
        val violations = ParentFieldViolations()

        fun walk(
            selectionSet: SelectionSet?,
            parentType: GraphQLCompositeType,
            enclosingDirectives: List<Directive>,
            activeFragments: Set<String>,
            underParentField: Boolean,
        ) {
            selectionSet?.selections?.forEach { selection ->
                when (selection) {
                    is Field -> {
                        val fieldsContainer = parentType as? GraphQLFieldsContainer ?: return@forEach
                        val fieldDefinition = fieldsContainer.getFieldDefinition(selection.name) ?: return@forEach
                        val isParentField = fieldDefinition.isParentField()
                        if (isParentField) {
                            val fieldCoordinate = "${fieldsContainer.name}.${selection.name}"
                            val directiveNames = (enclosingDirectives + selection.directives).map { it.name }
                            if (directiveNames.isNotEmpty()) {
                                violations.directivesByField.getOrPut(fieldCoordinate) { linkedSetOf() }.addAll(directiveNames)
                            }
                            if (!underParentField) {
                                selection.selectionSet
                                    ?.collectVariableReferencesFollowingFragments(fragmentsByName)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { violations.variablesByField.getOrPut(fieldCoordinate) { linkedSetOf() }.addAll(it) }
                            }
                        }
                        val fieldType = GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as? GraphQLCompositeType ?: return@forEach
                        walk(selection.selectionSet, fieldType, emptyList(), activeFragments, underParentField || isParentField)
                    }
                    is InlineFragment -> walk(
                        selection.selectionSet,
                        selection.typeCondition?.let { tenantCompilationSchema.getType(it.name) as? GraphQLCompositeType } ?: parentType,
                        enclosingDirectives + selection.directives,
                        activeFragments,
                        underParentField,
                    )
                    is FragmentSpread -> {
                        if (selection.name in activeFragments) return@forEach
                        val fragment = fragmentsByName[selection.name] ?: return@forEach
                        val fragmentType = tenantCompilationSchema.getType(fragment.typeCondition.name) as? GraphQLCompositeType ?: return@forEach
                        walk(
                            fragment.selectionSet,
                            fragmentType,
                            enclosingDirectives + selection.directives + fragment.directives,
                            activeFragments + selection.name,
                            underParentField,
                        )
                    }
                }
            }
        }

        walk(entryFragment.selectionSet, rootParentType, entryFragment.directives, setOf(entryFragment.name), underParentField = false)
        return violations
    }

    companion object {
        private val TENANT_LOCAL_DIRECTIVE_NAME = DefaultSchemaFactory.DefaultDirective.TENANT_LOCAL.directiveName

        // A tenant may legitimately ship a named fragment no resolver spreads, so don't fail on
        // UnusedFragment. (UndefinedFragment is intentionally not filtered: cross-leaf spreads are
        // already resolved here, so an undefined fragment is a real error.)
        private val FILTERED_ERRORS = setOf(
            ValidationErrorType.UnusedFragment,
        )

        // Errors likely caused by a gap in the tenant's compilation schema; we append a docs hint.
        private val COMPILATION_SCHEMA_RELATED_ERRORS = setOf(
            ValidationErrorType.FieldUndefined,
            ValidationErrorType.UnknownType,
            ValidationErrorType.FragmentTypeConditionInvalid,
            ValidationErrorType.InlineFragmentTypeConditionInvalid,
        )

        private fun GraphQLFieldDefinition.isTenantLocalField(): Boolean = hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME)

        private fun QueryVisitorFieldEnvironment.fieldCoordinate(): String = "${fieldsContainer.name}.${field.name}"
    }

    private data class TenantLocalFieldAccessViolation(
        val fieldCoordinate: String,
        val owner: String?,
    )

    private class ParentFieldViolations {
        val directivesByField = linkedMapOf<String, MutableSet<String>>()
        val variablesByField = linkedMapOf<String, MutableSet<String>>()
    }

    private class FieldOwnershipIndex private constructor(
        private val ownersByFieldCoordinate: Map<Pair<String, String>, String?>,
    ) {
        fun ownerOf(
            typeName: String,
            fieldName: String,
        ): String? = ownersByFieldCoordinate[typeName to fieldName]

        companion object {
            fun empty(): FieldOwnershipIndex = FieldOwnershipIndex(emptyMap())

            fun fromViaductSchema(
                schema: ViaductSchema,
                moduleNameFromSourceName: (String?) -> String?,
            ): FieldOwnershipIndex {
                val ownersByFieldCoordinate = mutableMapOf<Pair<String, String>, String?>()
                schema.types.values
                    .filterIsInstance<ViaductSchema.Record>()
                    .flatMap { it.fields }
                    .forEach { field ->
                        ownersByFieldCoordinate[field.containingDef.name to field.name] =
                            moduleNameFromSourceName(field.sourceLocation?.sourceName)
                    }
                return FieldOwnershipIndex(ownersByFieldCoordinate)
            }
        }
    }
}

private val MODULE_NAME_FROM_SOURCE_REGEXES = listOf(
    Regex("(?:^|/)viaduct/centralSchema/partition/(.*?)/graphql(?:/|$)"),
    Regex("(?:^|/)viaduct/schemaPartition/(.*?)/graphql(?:/|$)"),
    Regex("(?:^|/)modules/(.*?)/schema(?:/|$)"),
)

internal fun moduleNameFromSourceName(sourceName: String?): String? {
    for (regex in MODULE_NAME_FROM_SOURCE_REGEXES) {
        val moduleName = moduleNameFromSourceName(sourceName, regex)
        if (moduleName != null) return moduleName
    }
    return null
}

internal fun moduleNameFromSourceName(
    sourceName: String?,
    moduleNameFromSourceRegex: Regex,
): String? {
    if (sourceName == null) return null
    val normalizedSourceName = sourceName.replace('\\', '/')
    val rawModuleName = moduleNameFromSourceRegex
        .find(normalizedSourceName)
        ?.groups
        ?.get(1)
        ?.value
        ?.takeIf(String::isNotBlank)
        ?: return null

    return rawModuleName.replace('.', '/')
}
