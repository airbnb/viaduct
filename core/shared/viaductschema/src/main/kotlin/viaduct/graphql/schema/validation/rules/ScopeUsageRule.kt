package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Build-time SDL validation that every `@scope(to: [...])` directive in the schema references
 * only scope names declared in the gradle `viaductApplication.declaredSchemaScopes(...)` universe
 * (passed as [validScopes]), and that participating types and root types carry `@scope` whenever
 * scopes are declared at all.
 *
 * Implements rules A.1, A.2, A.3, A.4, A.5, A.6, A.7, A.9 from the schema-scoping spec. Rule A.8
 * (directive-retained type uses literal `["*"]`) is enforced separately by `ValidateScopesVisitor`
 * during materialization and is intentionally not covered here.
 *
 * The materialization-phase counterpart `ScopeDirectiveParser`
 * (`core/shared/graphql/.../scopes/utils/`) re-enforces A.1–A.7 inline during scoped-schema
 * retrieval. Both layers must coexist:
 * - This rule is the authoritative build-time enforcer and runs whenever a scope universe is
 *   configured, even when no `declaredScopedSchemas` entry is a proper subset of the universe
 *   (the spec-gap case where the runtime parser would otherwise see only `["*"]` requests).
 * - `ScopeDirectiveParser` is defense-in-depth for the runtime path and remains responsible for
 *   per-request scope projection.
 *
 * The rule is registered by `DefaultSchemaValidator.create(validScopes)` only when [validScopes]
 * is non-empty; the empty-set construction path is exercised by tests but is otherwise unused.
 *
 * ## Parse pipeline
 *
 * A single pass — [parseScopes] — walks the type's extensions and emits all diagnostics tied to
 * directive structure (A.1, A.2, A.3, malformed `to:` arg). The result is memoized per-type on
 * [ValidationContext] so per-field/per-arg traversals consume it without re-walking. The derived
 * `union` set is the only view that downstream `checkReferenced` needs.
 */
class ScopeUsageRule(
    private val validScopes: Set<String>
) : ValidationRule(
        id = "ScopeUsage",
        description = "@scope(to: [...]) must reference declared scope names; participating and root types must carry @scope"
    ) {
    private val allowedNames: Set<String> = validScopes + WILDCARD

    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) = checkTypeDef(ctx, obj)

    override fun visitInterface(
        ctx: ValidationContext,
        iface: ViaductSchema.Interface
    ) = checkTypeDef(ctx, iface)

    override fun visitUnion(
        ctx: ValidationContext,
        union: ViaductSchema.Union
    ) = checkTypeDef(ctx, union)

    override fun visitInput(
        ctx: ValidationContext,
        input: ViaductSchema.Input
    ) = checkTypeDef(ctx, input)

    override fun visitEnum(
        ctx: ValidationContext,
        enum: ViaductSchema.Enum
    ) = checkTypeDef(ctx, enum)

    private fun checkTypeDef(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef
    ) {
        if (shouldSkipByName(def.name)) return
        val parsed = parsedScopes(ctx, def)
        checkPresenceOnTypeDef(ctx, def, parsed)
        checkExtensionsSubsetOfBase(ctx, def, parsed)
    }

    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        val container = field.containingDef
        if (shouldSkipByName(container.name)) return
        val label = "${container.name}.${field.name}"
        val fromScopes = parsedScopes(ctx, container).union
        checkReferenced(ctx, field, field.type.baseTypeDef, label, fromScopes)
    }

    override fun visitFieldArg(
        ctx: ValidationContext,
        arg: ViaductSchema.FieldArg
    ) {
        val field = arg.containingDef
        val container = field.containingDef
        if (shouldSkipByName(container.name)) return
        val label = "${container.name}.${field.name}($${arg.name})"
        val fromScopes = parsedScopes(ctx, container).union
        checkReferenced(ctx, field, arg.type.baseTypeDef, label, fromScopes)
    }

    private fun checkReferenced(
        ctx: ValidationContext,
        field: ViaductSchema.Field,
        referenced: ViaductSchema.TypeDef,
        label: String,
        fromScopes: Set<String>?
    ) {
        if (referenced.kind !in PARTICIPATING_KINDS) return
        if (shouldSkipByName(referenced.name)) return

        val container = field.containingDef
        val refScopes = parsedScopes(ctx, referenced).union
        if (refScopes == null) {
            ctx.reportError(
                code = ValidationErrorCodes.SCOPE_MISSING_ON_REFERENCED_TYPE,
                message = "Field '$label' references type '${referenced.name}' which has no @scope. " +
                    "Add @scope to '${referenced.name}' or remove the reference.",
                location = SchemaLocation.ofField(container.name, field.name)
                    .withSourceLocation(field.sourceLocation)
            )
            return
        }
        if (fromScopes == null) return
        if (!sharesScope(fromScopes, refScopes)) {
            ctx.reportError(
                code = ValidationErrorCodes.SCOPE_REFERENCE_NO_SHARED_SCOPE,
                message = "Field '$label' references type '${referenced.name}' but their @scope sets share no scope. " +
                    "'${container.name}' has ${fromScopes.sorted()}; '${referenced.name}' has ${refScopes.sorted()}.",
                location = SchemaLocation.ofField(container.name, field.name)
                    .withSourceLocation(field.sourceLocation)
            )
        }
    }

    private fun parsedScopes(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef
    ): ParsedScopes = ctx.computeIfAbsent(this, def) { parseScopes(ctx, it) }

    private fun parseScopes(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef
    ): ParsedScopes {
        val perExtension = mutableListOf<ParsedExtension>()
        for (ext in def.extensions) {
            val scopeApps = ext.appliedDirectives.filter { it.name == SCOPE_DIRECTIVE_NAME }
            if (scopeApps.isEmpty()) {
                perExtension.add(ParsedExtension(ext, hasScopeDirective = false, rawScopes = emptySet()))
                continue
            }
            if (scopeApps.size > 1) {
                ctx.reportError(
                    code = ValidationErrorCodes.SCOPE_DIRECTIVE_REPEATED,
                    message = "@scope is applied ${scopeApps.size} times on a single definition of '${def.name}'. " +
                        "The @scope directive should not be repeated on one definition; combine the names into a single @scope(to: [...]) instead.",
                    location = SchemaLocation.ofType(def.name).withSourceLocation(ext.sourceLocation)
                )
            }
            val raw = mutableSetOf<String>()
            for (app in scopeApps) {
                // `to:` is declared `[String!]!`. The `GJSchemaRaw` decoder enforces required
                // directive arguments upstream: `toAppliedDirective` (GJSchemaRaw.kt:304) throws
                // `IllegalStateException("No default value for non-nullable argument to")` before
                // any `ViaductSchema` reaches this rule. So `app.arguments[TO_ARG_NAME]` cannot be
                // null in practice; the `!!` documents that contract. If a future decoder relaxes
                // the upstream check, prefer fixing the decoder over re-introducing a duplicate
                // rule-layer diagnostic — see the Round-3 worklog note on Finding #2.
                val toArg = app.arguments[TO_ARG_NAME]!!
                if (toArg !is ViaductSchema.ListLiteral) {
                    ctx.reportError(
                        code = ValidationErrorCodes.SCOPE_TO_ARG_NOT_LIST,
                        message = "@scope(to: ...) on '${def.name}' must be a list literal of strings; got '$toArg'.",
                        location = SchemaLocation.ofType(def.name).withSourceLocation(ext.sourceLocation)
                    )
                    continue
                }
                val names = mutableListOf<String>()
                for (element in toArg) {
                    val s = (element as? ViaductSchema.StringLiteral)?.value
                    if (s == null) {
                        ctx.reportError(
                            code = ValidationErrorCodes.SCOPE_NAME_NOT_VALID_STRING,
                            message = "@scope(to: [...]) on '${def.name}' contains a non-string element '$element'. " +
                                "Each scope name must be a string literal (e.g. \"public\").",
                            location = SchemaLocation.ofType(def.name).withSourceLocation(ext.sourceLocation)
                        )
                    } else {
                        names.add(s)
                    }
                }

                reportInvalidNames(ctx, def, ext, names)
                reportDuplicates(ctx, def, ext, names)
                reportStarMixed(ctx, def, ext, names)

                raw.addAll(names)
            }
            perExtension.add(ParsedExtension(ext, hasScopeDirective = true, rawScopes = raw))
        }
        return ParsedScopes(perExtension, validScopes)
    }

    private fun reportInvalidNames(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef,
        ext: ViaductSchema.Extension<*, *>,
        names: List<String>
    ) {
        for (name in names.toSet()) {
            if (name !in allowedNames) {
                ctx.reportError(
                    code = ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED,
                    message = "@scope(to: [...]) on '${def.name}' references undeclared scope '$name'. " +
                        "Declared scopes: ${validScopes.sorted()}.",
                    location = SchemaLocation.ofType(def.name).withSourceLocation(ext.sourceLocation)
                )
            }
        }
    }

    private fun reportDuplicates(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef,
        ext: ViaductSchema.Extension<*, *>,
        names: List<String>
    ) {
        val seen = mutableSetOf<String>()
        val dup = mutableSetOf<String>()
        for (n in names) {
            if (!seen.add(n)) dup.add(n)
        }
        for (d in dup) {
            ctx.reportError(
                code = ValidationErrorCodes.SCOPE_NAME_DUPLICATE_IN_DIRECTIVE,
                message = "@scope(to: [...]) on '${def.name}' lists scope '$d' more than once.",
                location = SchemaLocation.ofType(def.name).withSourceLocation(ext.sourceLocation)
            )
        }
    }

    private fun reportStarMixed(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef,
        ext: ViaductSchema.Extension<*, *>,
        names: List<String>
    ) {
        val hasStar = names.contains(WILDCARD)
        val concrete = names.filter { it != WILDCARD }
        if (hasStar && concrete.isNotEmpty()) {
            ctx.reportError(
                code = ValidationErrorCodes.SCOPE_STAR_MIXED_WITH_CONCRETE,
                message = "@scope(to: [...]) on '${def.name}' mixes '*' with concrete scopes $concrete. " +
                    "Use either ['*'] alone or a list of concrete scopes.",
                location = SchemaLocation.ofType(def.name).withSourceLocation(ext.sourceLocation)
            )
        }
    }

    private fun checkPresenceOnTypeDef(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef,
        parsed: ParsedScopes
    ) {
        // A.4 / A.9 require the BASE definition to carry @scope. Legacy
        // `ScopeDirectiveParser.getScopesFromDirective` rejected when the base AST node lacked the
        // directive, even if an extension carried it. Suppressing A.4/A.9 just because some
        // extension carries @scope opens a hole where `type Widget { id: ID! }` plus
        // `extend type Widget @scope(...) { ... }` silently passes — the base type itself becomes
        // unscoped at materialization time.
        if (parsed.baseHasScope) return
        val isRoot = isRootType(ctx, def)
        val baseExt = def.extensions.firstOrNull { it.isBase }
        val location = SchemaLocation.ofType(def.name)
            .withSourceLocation(baseExt?.sourceLocation)
        if (isRoot) {
            ctx.reportError(
                code = ValidationErrorCodes.SCOPE_MISSING_ON_ROOT_TYPE,
                message = "Root type '${def.name}' must carry @scope when schema scopes are declared. " +
                    "Add @scope(to: [...]) to '${def.name}'.",
                location = location
            )
        } else {
            ctx.reportError(
                code = ValidationErrorCodes.SCOPE_MISSING_ON_PARTICIPATING_TYPE,
                message = "Type '${def.name}' must carry @scope when schema scopes are declared. " +
                    "Add @scope(to: [...]) to '${def.name}'.",
                location = location
            )
        }
    }

    private fun checkExtensionsSubsetOfBase(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef,
        parsed: ParsedScopes
    ) {
        val base = parsed.perExtension.firstOrNull { it.ext.isBase } ?: return
        if (!base.hasScopeDirective) return
        // Subset semantics operate on the RAW names from the SDL, not the validScopes-filtered set.
        // Otherwise (a) an extension that claims a concrete narrowing on top of a base whose names
        // are all undeclared escapes A.7 entirely (filtered base is empty -> early return), and
        // (b) the framework-injection carve-out below would fire on `["*", "BOGUS"]` whose filtered
        // set collapses to `{"*"}`, leaving every undeclared concrete name a free pass.
        val baseScopes = base.rawScopes
        if (baseScopes.isEmpty()) return
        val baseAllowsAll = WILDCARD in baseScopes
        for (other in parsed.perExtension) {
            if (other.ext.isBase || !other.hasScopeDirective) continue
            // An extension whose scope set is exactly `["*"]` is treated as "do not broaden the
            // base" rather than as a literal "all scopes" claim. This matches how the framework
            // injects extensions (e.g., the default Query.node/nodes extension carries `["*"]`)
            // and keeps A.7 focused on real user mistakes: adding concrete scopes the base does
            // not declare. The literal-`["*"]` behavior change for directive-retained types is
            // tracked separately as A.8 and is intentionally not enforced here.
            //
            // Match against the RAW scope set so `["*", "BOGUS"]` does NOT silently qualify after
            // filtering — that would let a user smuggle an undeclared concrete name past A.7.
            if (other.rawScopes == STAR_ONLY) continue
            val excess = other.rawScopes.filter { it != WILDCARD && it !in baseScopes }
            if (excess.isNotEmpty() && !baseAllowsAll) {
                ctx.reportError(
                    code = ValidationErrorCodes.SCOPE_EXTENSION_EXCEEDS_BASE,
                    message = "extend '${def.name}' adds scopes ${excess.sorted()} not declared on its base type. " +
                        "Base scopes are ${baseScopes.sorted()}.",
                    location = SchemaLocation.ofType(def.name).withSourceLocation(other.ext.sourceLocation)
                )
            }
        }
    }

    private fun sharesScope(
        a: Set<String>,
        b: Set<String>
    ): Boolean {
        if (WILDCARD in a || WILDCARD in b) return true
        return a.any { it in b }
    }

    private fun isRootType(
        ctx: ValidationContext,
        def: ViaductSchema.TypeDef
    ): Boolean {
        val s = ctx.schema
        return def.name == s.queryTypeDef?.name ||
            def.name == s.mutationTypeDef?.name ||
            def.name == s.subscriptionTypeDef?.name
    }

    private fun shouldSkipByName(name: String): Boolean = name.startsWith("__") || name == ViaductSchema.VIADUCT_IGNORE_SYMBOL

    private data class ParsedExtension(
        val ext: ViaductSchema.Extension<*, *>,
        val hasScopeDirective: Boolean,
        // Names exactly as written in the SDL (after dedup into a Set). Subset / carve-out logic
        // operates on this so that undeclared-name and star-smuggling defects cannot evade A.7.
        val rawScopes: Set<String>
    )

    /**
     * Per-type scope view. The single source of truth for downstream `checkReferenced` is
     * [union] — the validScopes-filtered union across extensions, or null when no extension
     * carries `@scope` (signalling "type has no @scope at all"). The filter drops undeclared
     * names so [sharesScope] cannot return true on a pair of types that both carry the same typo.
     */
    private class ParsedScopes(
        val perExtension: List<ParsedExtension>,
        validScopes: Set<String>
    ) {
        val baseHasScope: Boolean = perExtension.firstOrNull { it.ext.isBase }?.hasScopeDirective == true
        val union: Set<String>? = run {
            var anyDirective = false
            val all = mutableSetOf<String>()
            for (pe in perExtension) {
                if (!pe.hasScopeDirective) continue
                anyDirective = true
                for (s in pe.rawScopes) {
                    if (s == WILDCARD || s in validScopes) all.add(s)
                }
            }
            if (anyDirective) all else null
        }
    }

    companion object {
        private const val SCOPE_DIRECTIVE_NAME = "scope"
        private const val TO_ARG_NAME = "to"
        private const val WILDCARD = "*"
        private val STAR_ONLY = setOf(WILDCARD)
        private val PARTICIPATING_KINDS = setOf(
            ViaductSchema.TypeDefKind.OBJECT,
            ViaductSchema.TypeDefKind.INTERFACE,
            ViaductSchema.TypeDefKind.UNION,
            ViaductSchema.TypeDefKind.INPUT,
            ViaductSchema.TypeDefKind.ENUM
        )
    }
}
