package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class ScopeUsageRuleTest {
    private val preamble = """
        directive @scope(to: [String!]!) repeatable on OBJECT | INTERFACE | UNION | ENUM | INPUT_OBJECT | SCALAR
    """.trimIndent()

    private fun validate(
        sdl: String,
        validScopes: Set<String> = setOf("public", "internal")
    ) = SchemaValidator(listOf(listOf(ScopeUsageRule(validScopes))))
        .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `valid SDL with full coverage emits no errors`() {
        val errors = validate(
            """
            type Query @scope(to: ["public", "internal"]) {
                widget: Widget
            }
            type Widget @scope(to: ["public", "internal"]) {
                id: ID!
                name: String
            }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `wildcard star applied everywhere emits no errors`() {
        val errors = validate(
            """
            type Query @scope(to: ["*"]) {
                widget: Widget
            }
            type Widget @scope(to: ["*"]) {
                id: ID!
            }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `A_1 undeclared scope name on type is rejected with stable code`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { p: Int }
            type Widget @scope(to: ["mystery"]) { id: ID! }
            extend type Query @scope(to: ["public"]) { w: Widget }
            """.trimIndent()
        )
        val codes = errors.map { it.code }
        codes shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
        val undeclared = errors.first { it.code == ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED }
        undeclared.message shouldContain "mystery"
        undeclared.message shouldContain "Widget"
    }

    @Test
    fun `A_1 fires even when universe is the only thing configured (regression for spec gap #1)`() {
        val errors = validate(
            sdl = """
                type Query @scope(to: ["public"]) {
                    badField: BadType
                }
                type BadType @scope(to: ["typo"]) { id: ID! }
            """.trimIndent(),
            validScopes = setOf("public")
        )
        errors.map { it.code } shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
    }

    @Test
    fun `A_2 duplicate scope name within one directive is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public", "public"]) { id: ID }
            """.trimIndent()
        )
        val dup = errors.firstOrNull { it.code == ValidationErrorCodes.SCOPE_NAME_DUPLICATE_IN_DIRECTIVE }
        dup shouldBe errors.first { it.code == ValidationErrorCodes.SCOPE_NAME_DUPLICATE_IN_DIRECTIVE }
        dup!!.message shouldContain "public"
        dup.message shouldContain "Query"
    }

    @Test
    fun `A_3 star mixed with a concrete scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["*", "public"]) { id: ID }
            """.trimIndent()
        )
        val mixed = errors.first { it.code == ValidationErrorCodes.SCOPE_STAR_MIXED_WITH_CONCRETE }
        mixed.message shouldContain "Query"
    }

    @Test
    fun `A_4 participating Object without scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            type Widget { id: ID! }
            """.trimIndent()
        )
        val missing = errors.first { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_PARTICIPATING_TYPE }
        missing.message shouldContain "Widget"
    }

    @Test
    fun `A_4 participating Input without scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            input WidgetIn { id: ID! }
            """.trimIndent()
        )
        val codes = errors.filter { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_PARTICIPATING_TYPE }
            .map { it.message }
        codes.any { it.contains("WidgetIn") } shouldBe true
    }

    @Test
    fun `A_4 participating Enum without scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            enum Color { RED BLUE }
            """.trimIndent()
        )
        val codes = errors.filter { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_PARTICIPATING_TYPE }
            .map { it.message }
        codes.any { it.contains("Color") } shouldBe true
    }

    @Test
    fun `A_4 participating Interface without scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            interface Named { name: String }
            """.trimIndent()
        )
        val codes = errors.filter { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_PARTICIPATING_TYPE }
            .map { it.message }
        codes.any { it.contains("Named") } shouldBe true
    }

    @Test
    fun `A_4 participating Union without scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            type A @scope(to: ["public"]) { id: ID }
            type B @scope(to: ["public"]) { id: ID }
            union AB = A | B
            """.trimIndent()
        )
        val codes = errors.filter { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_PARTICIPATING_TYPE }
            .map { it.message }
        codes.any { it.contains("AB") } shouldBe true
    }

    @Test
    fun `A_5 referenced type missing scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { w: Widget }
            type Widget { id: ID! }
            """.trimIndent()
        )
        // A.4 fires for Widget, and A.5 fires because Query.w references a type with no @scope.
        errors.map { it.code } shouldContain ValidationErrorCodes.SCOPE_MISSING_ON_REFERENCED_TYPE
        val missing = errors.first { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_REFERENCED_TYPE }
        missing.message shouldContain "Widget"
        missing.message shouldContain "Query.w"
    }

    @Test
    fun `A_6 referencing and referenced share no scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { w: Widget }
            type Widget @scope(to: ["internal"]) { id: ID! }
            """.trimIndent()
        )
        val noShared = errors.first { it.code == ValidationErrorCodes.SCOPE_REFERENCE_NO_SHARED_SCOPE }
        noShared.message shouldContain "Query.w"
        noShared.message shouldContain "Widget"
    }

    @Test
    fun `A_6 wildcard on either side satisfies the share requirement`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { w: Widget }
            type Widget @scope(to: ["*"]) { id: ID! }
            """.trimIndent()
        )
        errors.filter { it.code == ValidationErrorCodes.SCOPE_REFERENCE_NO_SHARED_SCOPE }.shouldBeEmpty()
    }

    @Test
    fun `A_7 extension introduces scope beyond base is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            type Widget @scope(to: ["public"]) { id: ID! }
            extend type Widget @scope(to: ["internal"]) { secret: String }
            """.trimIndent()
        )
        val exceeds = errors.first { it.code == ValidationErrorCodes.SCOPE_EXTENSION_EXCEEDS_BASE }
        exceeds.message shouldContain "Widget"
        exceeds.message shouldContain "internal"
    }

    @Test
    fun `A_7 extension exactly equal to star is treated as inherit base (framework injection contract)`() {
        // The default schema factory injects `extend type Query @scope(to: ["*"])` so the framework
        // can attach Node.node/nodes resolvers regardless of how the user narrows Query's base
        // scope. A.7 must not fire on this pattern; that would convert every scoped application
        // build into a "framework error" halt inside ViaductSchemaValidator.
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            extend type Query @scope(to: ["*"]) { node: ID }
            """.trimIndent()
        )
        errors.filter { it.code == ValidationErrorCodes.SCOPE_EXTENSION_EXCEEDS_BASE }.shouldBeEmpty()
    }

    @Test
    fun `A_7 wildcard base allows any concrete extension scope`() {
        val errors = validate(
            """
            type Query @scope(to: ["*"]) { id: ID }
            type Widget @scope(to: ["*"]) { id: ID! }
            extend type Widget @scope(to: ["internal"]) { secret: String }
            """.trimIndent()
        )
        errors.filter { it.code == ValidationErrorCodes.SCOPE_EXTENSION_EXCEEDS_BASE }.shouldBeEmpty()
    }

    @Test
    fun `A_9 Query root without scope is rejected with a root-specific code`() {
        val errors = validate(
            """
            type Query { id: ID }
            """.trimIndent()
        )
        val rootCode = errors.first { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_ROOT_TYPE }
        rootCode.message shouldContain "Query"
    }

    @Test
    fun `A_9 Mutation root without scope is rejected`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            type Mutation { noop: Boolean }
            """.trimIndent()
        )
        val codes = errors.filter { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_ROOT_TYPE }
            .map { it.message }
        codes.any { it.contains("Mutation") } shouldBe true
    }

    @Test
    fun `proper subset scoped schemas validate cleanly (regression for spec gap #3)`() {
        // Universe = {public, internal, admin}; one set is a proper subset, one equals universe.
        // The rule should still cleanly validate participating types whose scope sets are subsets.
        val errors = validate(
            sdl = """
                type Query @scope(to: ["public", "internal", "admin"]) { w: Widget }
                type Widget @scope(to: ["public"]) { id: ID! }
            """.trimIndent(),
            validScopes = setOf("public", "internal", "admin")
        )
        errors.filter { it.code == ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED }.shouldBeEmpty()
        errors.filter { it.code == ValidationErrorCodes.SCOPE_REFERENCE_NO_SHARED_SCOPE }.shouldBeEmpty()
    }

    @Test
    fun `every scoped schema equals universe still surfaces A_1 typos (regression for spec gap #2)`() {
        // Universe = {public}; scoped schemas all equal the universe in the user's gradle config.
        // A typo in the SDL is still caught here because validation is universe-driven, not
        // diff-driven against scoped-schema sets.
        val errors = validate(
            sdl = """
                type Query @scope(to: ["public"]) { w: Widget }
                type Widget @scope(to: ["publik"]) { id: ID! }
            """.trimIndent(),
            validScopes = setOf("public")
        )
        errors.map { it.code } shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
    }

    @Test
    fun `empty valid scope universe is treated as no-op (rule registration responsibility)`() {
        // When the universe is empty, the wiring layer is expected not to register this rule;
        // but if registered defensively, every SDL @scope reference becomes A.1. This test pins
        // that behavior — the rule itself is not silent when given an empty universe.
        val errors = SchemaValidator(listOf(listOf(ScopeUsageRule(emptySet()))))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\ntype Query @scope(to: [\"public\"]) { id: ID }"))
        errors.map { it.code } shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
    }

    @Test
    fun `multiple errors on one type are reported independently`() {
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            type Widget @scope(to: ["mystery", "*", "mystery"]) { id: ID! }
            """.trimIndent()
        )
        val codes = errors.filter { it.message.contains("Widget") }.map { it.code }.toSet()
        codes shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
        codes shouldContain ValidationErrorCodes.SCOPE_NAME_DUPLICATE_IN_DIRECTIVE
        codes shouldContain ValidationErrorCodes.SCOPE_STAR_MIXED_WITH_CONCRETE
    }

    @Test
    fun `Subscription root without scope is rejected when configured`() {
        // Subscription only counts as a root if the schema declares it.
        val errors = validate(
            """
            schema {
                query: Query
                subscription: Sub
            }
            type Query @scope(to: ["public"]) { id: ID }
            type Sub { ticks: Int }
            """.trimIndent()
        )
        val codes = errors.filter { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_ROOT_TYPE }
            .map { it.message }
        codes.any { it.contains("Sub") } shouldBe true
    }

    @Test
    fun `field reference into a non-participating Scalar is ignored by A_5 and A_6`() {
        // String/ID/etc. live as scalars; they cannot carry @scope and references to them are
        // intentionally outside A.5/A.6.
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { name: String, id: ID! }
            """.trimIndent()
        )
        errors.filter { it.code == ValidationErrorCodes.SCOPE_MISSING_ON_REFERENCED_TYPE }.shouldBeEmpty()
        errors.filter { it.code == ValidationErrorCodes.SCOPE_REFERENCE_NO_SHARED_SCOPE }.shouldBeEmpty()
    }

    @Test
    fun `repeated @scope on a single definition is reported as SCOPE_DIRECTIVE_REPEATED (mirrors legacy)`() {
        // The legacy `ScopeDirectiveParser.getScopesFromDirective` threw
        // "The scopes directive should not be repeated. Found multiple instances on node." when
        // a single definition carried multiple `@scope` applications. The new rule mirrors that
        // behavior under a dedicated error code (`SCOPE_DIRECTIVE_REPEATED`). The legacy threw
        // a generic `SchemaScopeValidationError` with only a message string; the new rule keeps the
        // semantics but adds a stable code so build output and reviewers can pattern-match on it.
        // Continues to combine the scope names across repeated applications so other A.x diagnostics
        // still fire alongside this one (no silent suppression).
        val errors = validate(
            """
            type Query @scope(to: ["public"]) @scope(to: ["internal"]) {
                widget: Widget
            }
            type Widget @scope(to: ["public", "internal"]) { id: ID! }
            """.trimIndent()
        )
        val codes = errors.map { it.code }
        codes shouldContain ValidationErrorCodes.SCOPE_DIRECTIVE_REPEATED
        val repeated = errors.first { it.code == ValidationErrorCodes.SCOPE_DIRECTIVE_REPEATED }
        repeated.message shouldContain "Query"
        repeated.message shouldContain "should not be repeated"
    }

    @Test
    fun `VIADUCT_IGNORE stub type is skipped by ScopeUsageRule`() {
        // The canonical placeholder type `ViaductSchema.VIADUCT_IGNORE_SYMBOL` ("VIADUCT_IGNORE")
        // is emitted by `GJSchemaRaw`, `ToRegistry`, `GraphQLSchemaDecoder`, and
        // `TypeDefinitionRegistryDecoder` to keep empty enclosing structures valid; it is not a
        // real participating type. The rule must skip it by name so A.4 does not fire on the
        // stub. Materialization paths strip the symbol upstream
        // (`TypeDefinitionRegistryDecoder.kt:128/153/178`), so this case is unreachable in
        // legacy validation — pinning it here guards against the local skip-name reference
        // drifting from the canonical constant.
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { id: ID }
            type VIADUCT_IGNORE { dummy: String }
            """.trimIndent()
        )
        errors.filter { it.message.contains("VIADUCT_IGNORE") }.shouldBeEmpty()
    }

    @Test
    fun `A_5 and A_6 fire on field argument types`() {
        // Field-argument input types participate in the schema graph just like the field's
        // return type: if an argument type has no `@scope` (A.5) or shares no scope with the
        // containing type (A.6), the same diagnostics apply. The rule must walk `field.args`
        // alongside `field.type.baseTypeDef`, and the diagnostic label must identify which
        // argument is the offender so the build output is actionable.

        // A.5 — argument type has no @scope.
        val a5 = validate(
            """
            type Query @scope(to: ["public"]) { search(input: SearchInput): String }
            input SearchInput { term: String }
            """.trimIndent()
        )
        val a5Msg = a5.first {
            it.code == ValidationErrorCodes.SCOPE_MISSING_ON_REFERENCED_TYPE &&
                it.message.contains("SearchInput")
        }
        a5Msg.message shouldContain "Query.search"
        a5Msg.message shouldContain "input"

        // A.6 — argument type carries scope but shares no scope with container.
        val a6 = validate(
            """
            type Query @scope(to: ["public"]) { search(input: SearchInput): String }
            input SearchInput @scope(to: ["internal"]) { term: String }
            """.trimIndent()
        )
        val a6Msg = a6.first {
            it.code == ValidationErrorCodes.SCOPE_REFERENCE_NO_SHARED_SCOPE &&
                it.message.contains("SearchInput")
        }
        a6Msg.message shouldContain "Query.search"
    }

    @Test
    fun `sharesScope filters undeclared names so a typo on both sides does not yield false positive`() {
        // When both a referencing type and its referenced type carry the same undeclared scope
        // name (e.g., both `["BOGUS"]` against universe `{public}`), the shares-scope check
        // must NOT count the typo as a shared scope — otherwise A.6 is silent and the only
        // diagnostic the build emits is A.1. Names outside the declared universe (and outside
        // the `*` wildcard) carry no meaning for shares-scope purposes; they must be filtered
        // before comparison. This complements A.1 — both fire when the user typo'd on both
        // sides, rather than A.1 alone masking A.6.
        val errors = validate(
            sdl = """
                type Query @scope(to: ["public", "BOGUS"]) { w: Widget }
                type Widget @scope(to: ["BOGUS"]) { id: ID! }
            """.trimIndent(),
            validScopes = setOf("public")
        )
        val codes = errors.map { it.code }
        codes shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
        codes shouldContain ValidationErrorCodes.SCOPE_REFERENCE_NO_SHARED_SCOPE
    }

    @Test
    fun `malformed @scope to-arg that is not a list is reported as SCOPE_TO_ARG_NOT_LIST`() {
        // The `@scope` directive is declared as `to: [String!]!`, so syntactically valid SDL
        // that passes a non-list (e.g., `@scope(to: "public")`) is reachable today only via
        // `GJSchemaRaw` (parsed-but-not-semantically-validated). When such an application
        // arrives at this rule, silently dropping it would leave the user's intended single-
        // name scope undiagnosed and the type effectively unscoped — a misleading downstream
        // A.4 / A.7 cascade. The rule must report a dedicated `SCOPE_TO_ARG_NOT_LIST`
        // diagnostic at the offending source location so the defect class is identifiable.
        val errors = validate(
            """
            type Query @scope(to: "public") { id: ID }
            """.trimIndent()
        )
        val codes = errors.map { it.code }
        codes shouldContain ValidationErrorCodes.SCOPE_TO_ARG_NOT_LIST
        val nonList = errors.first { it.code == ValidationErrorCodes.SCOPE_TO_ARG_NOT_LIST }
        nonList.message shouldContain "Query"
    }

    @Test
    fun `A_7 still fires when base scope names are all undeclared`() {
        // A.7 (extension scope set must be a subset of the base's) must operate on the raw
        // scope names declared in the SDL, not the validScopes-filtered set. Otherwise a base
        // type whose entire scope list is undeclared (e.g., `["BOGUS"]` against universe
        // `{public, internal}`) collapses to an empty filtered set and the subset check
        // silently bails, letting an extension claim concrete scopes the base does not
        // authorize. Both A.1 (for the undeclared name) and A.7 (for the over-broadening
        // extension) must fire so the user sees both defects in one build.
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { w: Widget }
            type Widget @scope(to: ["BOGUS"]) { id: ID! }
            extend type Widget @scope(to: ["internal"]) { secret: String }
            """.trimIndent()
        )
        val codes = errors.map { it.code }
        codes shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
        codes shouldContain ValidationErrorCodes.SCOPE_EXTENSION_EXCEEDS_BASE
        val exceeds = errors.first { it.code == ValidationErrorCodes.SCOPE_EXTENSION_EXCEEDS_BASE }
        exceeds.message shouldContain "Widget"
        exceeds.message shouldContain "internal"
    }

    @Test
    fun `A_7 wildcard plus undeclared on extension does not qualify for framework-injection carve-out`() {
        // The framework-injection carve-out (an extension whose scope set is exactly `["*"]`
        // is treated as "inherit base, do not broaden") must match against the raw declared
        // names, not the validScopes-filtered set. Otherwise an extension that writes
        // `["*", "BOGUS"]` collapses to `{"*"}` after filtering and silently qualifies for
        // the carve-out — sneaking the undeclared name past A.7 entirely. Only the literal
        // `["*"]` shape may qualify. A.3 (star mixed with concrete) and A.1 (undeclared name)
        // fire as well; the contract under test is that A.7 ALSO fires rather than being
        // silenced by a misapplied carve-out.
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { w: Widget }
            type Widget @scope(to: ["public"]) { id: ID! }
            extend type Widget @scope(to: ["*", "BOGUS"]) { secret: String }
            """.trimIndent()
        )
        val codes = errors.map { it.code }
        codes shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_DECLARED
        codes shouldContain ValidationErrorCodes.SCOPE_EXTENSION_EXCEEDS_BASE
        codes shouldContain ValidationErrorCodes.SCOPE_STAR_MIXED_WITH_CONCRETE
    }

    @Test
    fun `A_4 fires on base without scope even when an extension carries scope (mirrors legacy)`() {
        // A.4 (participating type must carry `@scope`) is a property of the BASE AST node,
        // not the union of base + extensions. A user who writes
        //   type Widget { id: ID! }
        //   extend type Widget @scope(to: ["public"]) { ... }
        // leaves the base Widget unscoped — at materialization time the base type itself is
        // not scope-projectable. Legacy `_metadataForScopeAllowedElementHelper` and
        // `ScopeDirectiveParser.getScopesFromDirective` required the base to carry the
        // directive directly; this rule mirrors that semantic by checking presence on the
        // base extension specifically rather than on any-extension.
        val errors = validate(
            """
            type Query @scope(to: ["public"]) { w: Widget }
            type Widget { id: ID! }
            extend type Widget @scope(to: ["public"]) { name: String }
            """.trimIndent()
        )
        val a4 = errors.filter {
            it.code == ValidationErrorCodes.SCOPE_MISSING_ON_PARTICIPATING_TYPE &&
                it.message.contains("Widget")
        }
        a4.shouldHaveSize(1)
    }

    @Test
    fun `directive-structure diagnostics carry extension sourceLocation for IDE jumpability`() {
        // Regression guard for the Round-3 refactor: diagnostics emitted from inside the
        // per-extension parse loop (SCOPE_TO_ARG_NOT_LIST, SCOPE_NAME_NOT_VALID_STRING,
        // SCOPE_NAME_NOT_DECLARED, SCOPE_NAME_DUPLICATE_IN_DIRECTIVE, SCOPE_STAR_MIXED_WITH_CONCRETE,
        // SCOPE_DIRECTIVE_REPEATED) all share the same `.withSourceLocation(ext.sourceLocation)`
        // attachment. Dropping that call on any one of them silently breaks IDE click-through.
        // Inline SDL via the String overload doesn't propagate a sourceName (and `ViaductSchema.
        // SourceLocation` only carries `sourceName`, no line/column), so this test loads the SDL
        // from a backing resource file so the extension's `sourceLocation` is populated and the
        // attachment is observable. Pinning a representative — SCOPE_TO_ARG_NOT_LIST — catches
        // a refactor that drops the attachment from the shared call site.
        val schemaUrl = javaClass.getResource("/validation/application/scope_malformed.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(schemaUrl))
        val errors = SchemaValidator(listOf(listOf(ScopeUsageRule(setOf("public", "internal")))))
            .validate(schema)
        val notList = errors.first { it.code == ValidationErrorCodes.SCOPE_TO_ARG_NOT_LIST }
        notList.location.sourceLocation.shouldNotBeNull().sourceName shouldContain "scope_malformed.graphql"
    }

    @Test
    fun `non-string element in @scope to-list is reported as SCOPE_NAME_NOT_VALID_STRING`() {
        // The legacy `ScopeDirectiveParser.getScopesFromDirective` threw "'$it' is not a StringValue"
        // when a list element wasn't a StringValue. The new rule reports each non-string element via
        // a dedicated code (rather than silently dropping it or piggybacking on A.1's "undeclared")
        // so the diagnostic reflects the actual defect class: literal-is-not-a-string vs.
        // name-not-in-the-universe. Reachable today only by bypassing graphql-java's signature check
        // (the directive is declared `to: [String!]!`), but pinning it removes a latent silent-drop.
        // The directive arg is `[String!]!`; `GJSchemaRaw` is parsed-but-not-semantically-validated,
        // so an enum literal in the list survives parsing for the rule to inspect.
        val errors = validate(
            """
            type Query @scope(to: ["public", BAD_ENUM]) { id: ID! }
            """.trimIndent()
        )
        val codes = errors.map { it.code }
        codes shouldContain ValidationErrorCodes.SCOPE_NAME_NOT_VALID_STRING
        val nonString = errors.first { it.code == ValidationErrorCodes.SCOPE_NAME_NOT_VALID_STRING }
        nonString.message shouldContain "Query"
        nonString.message shouldContain "non-string"
    }
}
