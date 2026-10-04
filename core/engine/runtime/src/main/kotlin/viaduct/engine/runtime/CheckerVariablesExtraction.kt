package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.Validated
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.api.spi.VariableFromFunctionDefinitions
import viaduct.graphql.utils.collectVariableReferences

/** Converts legacy checker variable-resolver graphs into the forms supported by runtime2. */
internal fun extractCheckerVariableDefinitions(
    requiredSelectionSets: Map<String, RequiredSelectionSet?>,
    objectTypeName: String? = null,
    queryTypeName: String? = null,
    checkerType: CheckerExecutor.CheckerType? = null,
): Map<String, ResolverVariableDefinitions> =
    requiredSelectionSets.mapValues { (inputName, requiredSelectionSet) ->
        requiredSelectionSet?.let { required ->
            CheckerVariableDefinitionsBuilder(
                inputName = inputName,
                objectTypeName = objectTypeName ?: required.selections.typeName,
                queryTypeName = queryTypeName ?: DEFAULT_QUERY_TYPE_NAME,
                checkerType = checkerType,
            ).build(required)
        } ?: ResolverVariableDefinitions.EMPTY
    }

private const val DEFAULT_QUERY_TYPE_NAME = "Query"

private class CheckerVariableDefinitionsBuilder(
    private val inputName: String,
    private val objectTypeName: String,
    private val queryTypeName: String,
    private val checkerType: CheckerExecutor.CheckerType?,
) {
    private sealed interface Definition {
        data class FromArgument(
            val path: String,
        ) : Definition

        data class FromObjectField(
            val path: String,
        ) : Definition

        data class FromQueryField(
            val path: String,
        ) : Definition

        data object FromFunction : Definition
    }

    private class Provider(
        val resolver: VariablesResolver,
        var selectedNames: Set<String>,
    )

    private val definitions = linkedMapOf<String, Definition>()
    private val providers = mutableListOf<Provider>()

    fun build(requiredSelectionSet: RequiredSelectionSet): ResolverVariableDefinitions {
        add(requiredSelectionSet)
        return ResolverVariableDefinitions(
            fromArguments =
                VariableFromArgumentDefinitions(
                    definitions.mapNotNull { (name, definition) ->
                        (definition as? Definition.FromArgument)?.let { name to it.path }
                    }.toMap(),
                ),
            fromObjectFields =
                VariableFromFieldDefinitions(
                    definitions.mapNotNull { (name, definition) ->
                        (definition as? Definition.FromObjectField)?.let { name to it.path }
                    }.toMap(),
                ),
            fromQueryFields =
                VariableFromFieldDefinitions(
                    definitions.mapNotNull { (name, definition) ->
                        (definition as? Definition.FromQueryField)?.let { name to it.path }
                    }.toMap(),
                ),
            fromFunction =
                providers.takeIf { it.isNotEmpty() }?.let { providers ->
                    CheckerVariablesProviderDefinitions(
                        inputName = inputName,
                        providers = providers.map { it.resolver to it.selectedNames },
                    )
                },
        )
    }

    private fun add(requiredSelectionSet: RequiredSelectionSet) {
        requireSupportedRoot(requiredSelectionSet)
        val referencedNames = requiredSelectionSet.selections.selections.collectVariableReferences()
        requiredSelectionSet.variablesResolvers
            .filter { resolver -> resolver.variableNames.any(referencedNames::contains) }
            .forEach { resolver ->
                addVariable(resolver, resolver.variableNames.intersect(referencedNames))
            }
    }

    private fun addVariable(
        resolver: VariablesResolver,
        selectedNames: Set<String>,
    ) {
        when (val canonical = resolver.unwrapValidation()) {
            is FromArgument -> addFromArgument(canonical)
            is FromFieldVariablesResolver -> addFromField(canonical)
            else -> addFromFunction(resolver, canonical, selectedNames)
        }
    }

    private fun addFromArgument(resolver: FromArgument) {
        require(checkerType != CheckerExecutor.CheckerType.TYPE) {
            "Type checker input $inputName cannot define variable ${resolver.name} from an argument"
        }
        declare(resolver.name, Definition.FromArgument(resolver.path.joinToString(".")))
    }

    private fun addFromField(resolver: FromFieldVariablesResolver) {
        val requiredSelectionSet = resolver.requiredSelectionSet
        add(requiredSelectionSet)
        val path = resolver.path.joinToString(".")
        val definition =
            when (requiredSelectionSet.selections.typeName) {
                objectTypeName -> Definition.FromObjectField(path)
                queryTypeName -> Definition.FromQueryField(path)
                else ->
                    throw IllegalArgumentException(
                        "Checker input $inputName variable ${resolver.name} is rooted at " +
                            "${requiredSelectionSet.selections.typeName}; expected $objectTypeName or $queryTypeName",
                    )
            }
        declare(resolver.name, definition)
    }

    private fun addFromFunction(
        resolver: VariablesResolver,
        canonical: VariablesResolver,
        selectedNames: Set<String>,
    ) {
        require(canonical.requiredSelectionSet == null) {
            "Runtime2 checker input $inputName does not support an opaque variables resolver " +
                "with its own required selection set: ${canonical::class.qualifiedName}"
        }
        require(canonical.variableNames.isNotEmpty()) {
            "Checker variables provider $inputName must declare a variable"
        }
        selectedNames.forEach { name -> declare(name, Definition.FromFunction) }
        val existing = providers.singleOrNull { it.resolver === resolver }
        if (existing != null) {
            existing.selectedNames = existing.selectedNames + selectedNames
            return
        }
        val overlapping = providers.flatMap { it.selectedNames }.toSet().intersect(selectedNames)
        require(overlapping.isEmpty()) {
            "Checker input $inputName has multiple variables providers for $overlapping"
        }
        providers += Provider(resolver, selectedNames)
    }

    private fun declare(
        name: String,
        definition: Definition,
    ) {
        val existing = definitions[name]
        require(existing == null || existing == definition) {
            "Checker input $inputName defines variable $name inconsistently"
        }
        definitions[name] = definition
    }

    private fun requireSupportedRoot(requiredSelectionSet: RequiredSelectionSet) {
        val root = requiredSelectionSet.selections.typeName
        require(root == objectTypeName || root == queryTypeName) {
            "Checker input $inputName is rooted at $root; expected $objectTypeName or $queryTypeName"
        }
    }
}

private class CheckerVariablesProviderDefinitions(
    private val inputName: String,
    private val providers: List<Pair<VariablesResolver, Set<String>>>,
) : VariableFromFunctionDefinitions {
    override val variableNames: Set<String> = providers.flatMapTo(linkedSetOf()) { it.second }

    override suspend fun provideVariables(
        objectData: EngineObjectData.Sync,
        arguments: Map<String, Any?>,
        context: EngineExecutionContext,
    ): Map<String, Any?> =
        buildMap {
            providers.forEach { (provider, selectedNames) ->
                val values =
                    provider.resolve(
                        VariablesResolver.ResolveCtx(objectData = objectData, arguments = arguments),
                        context,
                    )
                check(values.keys == provider.variableNames) {
                    "Checker variables provider $inputName returned invalid variables: " +
                        "expected ${provider.variableNames}, got ${values.keys}"
                }
                selectedNames.forEach { name -> put(name, values.getValue(name)) }
            }
        }
}

private fun VariablesResolver.unwrapValidation(): VariablesResolver =
    when (this) {
        is Validated -> delegate.unwrapValidation()
        else -> this
    }
