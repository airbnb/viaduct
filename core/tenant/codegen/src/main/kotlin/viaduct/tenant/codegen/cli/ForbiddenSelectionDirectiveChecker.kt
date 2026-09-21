package viaduct.tenant.codegen.cli

import graphql.analysis.QueryTraversalOptions
import graphql.analysis.QueryVisitorFieldEnvironment
import graphql.analysis.QueryVisitorStub
import graphql.language.FragmentDefinition
import graphql.language.Node
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLSchema
import viaduct.graphql.utils.ViaductQueryTraverser

/**
 * Finds selections of fields carrying a forbidden directive. Shared by the required-selection-set,
 * @GraphQLOperation, and @GraphQLFragment validators so every selection surface a tenant module
 * assembles is held to the same rule. Callers run it only after the selection has passed schema
 * validation, because the traversal assumes every selected field exists.
 */
internal class ForbiddenSelectionDirectiveChecker(
    private val schema: GraphQLSchema,
    private val directiveNames: Set<String>,
) {
    data class Violation(
        val fieldCoordinate: String,
        val directiveName: String,
    ) {
        fun message(): String = "selects $fieldCoordinate, which carries @$directiveName. Fields with this directive must not be selected."
    }

    val isEnabled: Boolean get() = directiveNames.isNotEmpty()

    /**
     * Walks [root] (an operation or fragment definition) whose selections start on
     * [rootParentType], following spreads through [fragmentsByName].
     */
    fun violations(
        root: Node<*>,
        rootParentType: GraphQLCompositeType,
        fragmentsByName: Map<String, FragmentDefinition>,
    ): Set<Violation> {
        if (!isEnabled) return emptySet()
        val violations = linkedSetOf<Violation>()
        ViaductQueryTraverser
            .newQueryTraverser()
            .schema(schema)
            .root(root)
            .rootParentType(rootParentType)
            .fragmentsByName(fragmentsByName)
            .options(QueryTraversalOptions.defaultOptions().coerceFieldArguments(false))
            .build()
            .visitPreOrder(
                object : QueryVisitorStub() {
                    override fun visitField(env: QueryVisitorFieldEnvironment) {
                        if (env.isTypeNameIntrospectionField) return
                        val fieldCoordinate = "${env.fieldsContainer.name}.${env.field.name}"
                        directiveNames
                            .filter(env.fieldDefinition::hasAppliedDirective)
                            .forEach { violations.add(Violation(fieldCoordinate, it)) }
                    }
                },
            )
        return violations
    }
}
