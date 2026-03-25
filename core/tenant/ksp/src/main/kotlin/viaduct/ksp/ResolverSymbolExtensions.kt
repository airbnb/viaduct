package viaduct.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import viaduct.graphql.utils.SelectionsParserUtils

/**
 * Extracts the typeName from the @ResolverFor annotation on the resolver's base class.
 */
fun KSClassDeclaration.extractTypeNameFromBaseClass(): String {
    val superTypes = this.superTypes.toList()
    val baseClassDeclaration = checkNotNull(superTypes.firstOrNull()?.resolve()?.declaration as? KSClassDeclaration) {
        "Resolver class ${this.qualifiedName?.asString()} has no base class"
    }

    val resolverForAnnotation = checkNotNull(
        baseClassDeclaration.annotations.firstOrNull { it.shortName.asString() == "ResolverFor" }
    ) {
        "Resolver class ${this.qualifiedName?.asString()} has base class ${baseClassDeclaration.qualifiedName?.asString()} without a @ResolverFor annotation"
    }

    val typeNameArg = checkNotNull(
        resolverForAnnotation.arguments.firstOrNull { it.name?.asString() == "typeName" }
    ) { "@ResolverFor annotation for ${baseClassDeclaration.qualifiedName?.asString()} is missing the typeName argument" }

    return typeNameArg.value.toString()
}

/**
 * Converts shorthand fragment syntax to full fragment syntax.
 *
 * @throws IllegalArgumentException if directives are used in shorthand syntax
 */
fun convertToFullFragment(
    fragmentString: String,
    typeName: String
): String {
    if (!SelectionsParserUtils.isShorthandForm(fragmentString)) {
        return fragmentString.trim()
    }

    require(!fragmentString.contains(Regex("@\\w+"))) {
        "Directives are not allowed in shorthand fragments: $fragmentString"
    }

    return SelectionsParserUtils.wrapShorthandAsFragment(fragmentString, typeName)
}
