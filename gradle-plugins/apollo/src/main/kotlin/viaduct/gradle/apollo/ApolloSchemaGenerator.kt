package viaduct.gradle.apollo

import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.DirectivesContainer
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.StringValue
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import viaduct.graphql.schema.graphqljava.readTypesFromFiles

object ApolloSchemaGenerator {
    fun generate(
        centralSchemaDirectory: File,
        keyDirectiveName: String = "key"
    ): String {
        val files = centralSchemaDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "graphqls" }
            .sortedBy { it.relativeTo(centralSchemaDirectory).invariantSeparatorsPath }
            .toList()
        return generate(readTypesFromFiles(files, centralSchemaDirectory), keyDirectiveName)
    }

    private fun generate(
        registry: TypeDefinitionRegistry,
        keyDirectiveName: String
    ): String {
        validateKeyDefinition(registry, keyDirectiveName)
        val operations = registry.schemaDefinition().map { it.operationTypeDefinitions }.orElse(emptyList()) +
            registry.schemaExtensionDefinitions.flatMap { it.operationTypeDefinitions }
        val queryName = operations.singleOrNull { it.name == "query" }?.typeName?.name ?: "Query"
        val query = registry.types()[queryName] as? ObjectTypeDefinition
            ?: error("Apollo schema generation requires a query root object '$queryName'.")
        val rootScopes = scopes(query)
        val declarations = registry.types().values.filterIsInstance<DirectivesContainer<*>>() +
            registry.objectTypeExtensions().values.flatten() + registry.interfaceTypeExtensions().values.flatten()
        declarations.forEach { declaration -> declaration.getDirectives(keyDirectiveName).forEach(::validateKey) }
        val entities = registry.types().values.filterIsInstance<ObjectTypeDefinition>().filter { type ->
            (type.getDirectives(keyDirectiveName) + registry.objectTypeExtensions()[type.name].orEmpty().flatMap { it.getDirectives(keyDirectiveName) })
                .any { key -> (key.getArgument("resolvable")?.value as? BooleanValue)?.isValue != false }
        }.sortedBy { it.name }
        return buildString {
            append("scalar _Any\n")
            append("type _Service${scopeDirective(rootScopes)} { sdl: String! @resolver }\n")
            append("extend type $queryName${scopeDirective(rootScopes)} { _service: _Service! @resolver }\n")
            if (entities.isNotEmpty()) {
                append("union _Entity${scopeDirective(rootScopes?.let { listOf("*") })} = ${entities.joinToString(" | ") { it.name }}\n")
                append("extend type $queryName${scopeDirective(rootScopes)} { _entities(representations: [_Any!]!): [_Entity]! @resolver }\n")
            }
        }
    }

    private fun validateKeyDefinition(
        registry: TypeDefinitionRegistry,
        keyDirectiveName: String
    ) {
        val definition = requireNotNull(registry.getDirectiveDefinition(keyDirectiveName).orElse(null)) {
            "Apollo schema generation requires a declaration for selected key directive '@$keyDirectiveName'. Configure keyDirectiveName to match the declaration."
        }
        val resolvable = definition.inputValueDefinitions.singleOrNull { it.name == "resolvable" }
        require(
            (resolvable?.type as? TypeName)?.name == "Boolean" &&
                (resolvable.defaultValue as? BooleanValue)?.isValue == true
        ) {
            "Apollo schema generation requires Apollo @key(resolvable: Boolean = true); other federation dialects are unsupported."
        }
        val fieldsType = (definition.inputValueDefinitions.singleOrNull { it.name == "fields" }?.type as? NonNullType)?.type as? TypeName
        require(fieldsType != null && (fieldsType.name == "String" || registry.scalars().containsKey(fieldsType.name))) {
            "Apollo schema generation requires @key(fields:) to be a non-null scalar."
        }
    }

    private fun validateKey(key: Directive) {
        val source = key.sourceLocation
        val location = "${source?.sourceName}:${source?.line}:${source?.column}"
        require((key.getArgument("fields")?.value as? StringValue)?.value?.isNotBlank() == true) {
            "Apollo @key(fields:) must be a non-empty string at $location."
        }
        val resolvable = key.getArgument("resolvable")?.value
        require(resolvable == null || resolvable is BooleanValue) {
            "Apollo @key(resolvable:) must be a boolean at $location."
        }
    }

    private fun scopes(type: DirectivesContainer<*>): List<String>? =
        type.getDirectives("scope").takeIf { it.isNotEmpty() }?.flatMap { directive ->
            val values = directive.getArgument("to")?.value as? ArrayValue
                ?: error("Apollo schema generation requires @scope(to:) to be an array of strings.")
            values.values.map { (it as? StringValue)?.value ?: error("Apollo schema generation requires @scope(to:) to contain strings.") }
        }?.distinct()?.sorted()

    private fun scopeDirective(scopes: List<String>?): String =
        if (scopes == null) {
            ""
        } else {
            scopes.joinToString(", ", " @scope(to: [", "])") {
                AstPrinter.printAst(StringValue.newStringValue(it).build())
            }
        }
}
