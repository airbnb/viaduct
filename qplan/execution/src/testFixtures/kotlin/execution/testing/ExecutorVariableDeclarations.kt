package execution.testing

import graphql.language.AstPrinter
import model.Arguments
import model.Fragment
import model.engineObjectDataOf
import model.registry.ResolverTarget
import model.registry.VariablesProviderFunction
import model.testing.VariableDeclaration
import model.testing.ViaductAndGJSchema
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.fromQueryField
import model.usedVariables
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.graphql.schema.ViaductSchema

/** Compiles the executor SPI with the same typed templates and paths used by TestWorld. */
internal class ExecutorVariableDeclarations(
    val declarations: Map<Arguments.Variable, VariableDeclaration>,
    val providerNames: Set<String>,
    val provider: VariablesProviderFunction?,
)

internal fun FieldResolverExecutor.compileVariableDeclarations(
    schema: ViaductAndGJSchema,
    field: ViaductSchema.ObjectField,
    objectFragment: Fragment,
    queryFragment: Fragment?,
    context: EngineExecutionContext,
): ExecutorVariableDeclarations {
    val coordinate = "${field.containingDef.name}.${field.name}"
    val templates = listOfNotNull(objectFragment, queryFragment)
        .flatMap { it.subselections.usedVariables() }.toSet()
        .groupBy { it.variableName }
    require(
        templates.values.all { variables ->
            variables.size == 1 &&
                variables.single().isTemplate &&
                variables.single().target == ResolverTarget.FieldValueResolverTarget(field)
        }
    ) { "Required selections for $coordinate contain ambiguous or foreign variable templates" }

    val functionProvider = variablesFromFunctionProvider
    val names = listOf(
        argumentVariables.variableNames,
        objectFieldVariables.variableNames,
        queryFieldVariables.variableNames,
        functionProvider?.variableNames.orEmpty(),
    ).flatten()
    require(names.size == names.toSet().size) { "Duplicate variable declarations for $coordinate" }
    require(templates.keys.containsAll(names)) { "Unused variable declarations for $coordinate: ${names - templates.keys}" }
    require(names.containsAll(templates.keys)) {
        "Missing explicit variable declarations for $coordinate: ${templates.keys - names.toSet()}"
    }
    val declarations = linkedMapOf<Arguments.Variable, VariableDeclaration>()

    fun declare(
        name: String,
        compile: () -> VariableDeclaration
    ) {
        declarations[templates.getValue(name).single()] = try {
            compile()
        } catch (failure: IllegalArgumentException) {
            if (failure.message.orEmpty().contains("lossy type condition")) {
                throw InvalidVariableException(
                    field.containingDef.name to field.name,
                    name,
                    failure.message ?: "Invalid variable source",
                )
            }
            throw failure
        }
    }
    argumentVariables.variables.forEach { (name, path) ->
        declare(name) { schema.loweredSchema.fromArgument(field, path.split('.')) }
    }
    objectFieldVariables.variables.forEach { (name, path) ->
        declare(name) {
            schema.fromObjectField(
                requireNotNull(objectSelectionSet) { "Object variable $name requires an object RSS" }.fragmentSource(),
                path.split('.'),
                variableField = field,
            )
        }
    }
    queryFieldVariables.variables.forEach { (name, path) ->
        declare(name) {
            schema.fromQueryField(
                requireNotNull(querySelectionSet) { "Query variable $name requires a Query RSS" }.fragmentSource(),
                path.split('.'),
                variableField = field,
            )
        }
    }

    val providerNames = functionProvider?.variableNames.orEmpty()
    val provider: VariablesProviderFunction? = functionProvider?.let { declaredProvider ->
        { arguments ->
            val values = declaredProvider.provideVariables(
                engineObjectDataOf(field.containingDef),
                arguments.fieldValues,
                context,
            )
            check(values.keys == providerNames) {
                "Variables provider for $coordinate must return exactly its declared names"
            }
            values
        }
    }
    return ExecutorVariableDeclarations(declarations, providerNames, provider)
}

private fun RequiredSelectionSet.fragmentSource(): String = "fragment _ on ${selections.typeName} ${AstPrinter.printAst(selections.selections)}"
