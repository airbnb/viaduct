package viaduct.tenant.codegen.graphql.schema

import viaduct.graphql.schema.ViaductSchema

fun ViaductSchema.Def.isInScope(scope: String): Boolean {
    return when (this) {
        is ViaductSchema.Enum,
        is ViaductSchema.Input,
        is ViaductSchema.Interface,
        is ViaductSchema.Object,
        is ViaductSchema.Union -> {
            this.appliedDirectives.includesScope(scope)
        }
        is ViaductSchema.Field -> {
            this.containingExtension.appliedDirectives.includesScope(scope) && this.type.baseTypeDef.isInScope(scope)
        }
        is ViaductSchema.EnumValue -> {
            this.containingExtension.appliedDirectives.includesScope(scope)
        }
        else -> true
    }
}

private fun Iterable<ViaductSchema.AppliedDirective<*>>.includesScope(scope: String): Boolean {
    // If a definition is in a non-private scope, it's automatically also in the private version of that scope, e.g.
    // scope(to: ["listing-block"]) is the same as scope(to: ["listing-block", "listing-block:private"]).
    // So if the given scope is :private, expand the check in case the :private scope wasn't explicitly set.
    val scopes = if (scope.endsWith(":private")) {
        setOf(scope, scope.removeSuffix(":private"))
    } else {
        setOf(scope)
    }
    this.filter { it.name == "scope" }.forEach { directive ->
        (directive.arguments["to"] as ViaductSchema.ListLiteral).forEach {
            val value = (it as ViaductSchema.StringLiteral).value
            if (value in scopes || value == "*") return true
        }
    }
    return false
}

/** If the extension has a scope directive, return the scopes
 *  listed in that directive.  Otherwise, return null.  Throws
 *  an exception if the directive is not well-formed.
 */
val ViaductSchema.Extension<*, *>.scopes: List<String>? get() {
    val dirs = this.appliedDirectives.filter { it.name == "scope" }
    if (dirs.size == 0) return null

    // scope is a repeatable directive
    return dirs.flatMap { dir ->
        (dir.arguments["to"] as ViaductSchema.ListLiteral).map { (it as ViaductSchema.StringLiteral).value }
    }
}
