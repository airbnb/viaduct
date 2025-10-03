package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser

class RequiredSelectionsAreAcyclicTest {
    @Test
    fun `valid -- no required selections`() {
        assertValid(
            "type Subject { x: Int }",
            MockRequiredSelectionSetRegistry.empty
        )
    }

    @Test
    fun `valid -- dependency on sibling`() {
        assertValid(
            "type Subject { x: Int y: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .build()
        )
    }

    @Test
    fun `valid -- dependency on nested sibling`() {
        assertValid(
            """
                type Obj { x: Int }
                type Subject { x: Int obj: Obj }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "obj { x }")
                .build()
        )
    }

    @Test
    fun `valid -- interleaved sibling`() {
        assertValid(
            """
                type Obj { x: Int y: Int }
                type Subject { x: Int y: Int obj: Obj }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "obj { x }")
                .fieldResolverEntry("Subject" to "y", "obj { y }")
                .build()
        )
    }

    @Test
    fun `valid -- required selection sets on sub selections`() {
        assertValid(
            """
                type Obj { x: Int y: Int }
                type Subject { x: Int y: Int z: Int obj: Obj }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y z")
                .fieldResolverEntry("Subject" to "y", "obj { y }")
                .build()
        )
    }

    @Test
    fun `valid -- required selection sets on sub selections with nested fields`() {
        assertValid(
            """
                type Obj { a: Int }
                type Subject { x: Int y: Obj z: Int h: Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y { a } z")
                .fieldResolverEntry("Subject" to "y", "h")
                .build()
        )
    }

    @Test
    fun `valid -- same required selection sets for same coordinate`() {
        assertValid(
            "type Subject { x: Int y: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "x", "y")
                .build()
        )
    }

    @Test
    fun `valid -- same required selection sets for same coordinate 1`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "y", "z")
                .build()
        )
    }

    @Test
    fun `valid -- different required selection sets for same coordinate`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "x", "y z")
                .build()
        )
    }

    @Test
    fun `valid -- different required selection sets for same coordinate 1`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "x", "z")
                .build()
        )
    }

    @Test
    fun `valid -- same required selection sets for multiple same coordinate`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "y", "z")
                .fieldResolverEntry("Subject" to "y", "z")
                .build()
        )
    }

    @Test
    fun `valid -- concrete system coordinate`() {
        assertValid(
            "type Subject { x: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "__typename")
                .build()
        )
    }

    @Test
    fun `valid -- selections on recursive object`() {
        assertValid(
            "type Subject { subj: Subject x: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "subj { subj { subj { __typename } } }")
                .build()
        )
    }

    @Test
    fun `valid -- required selections on co-recursive objects`() {
        assertValid(
            """
                type Other { subj: Subject }
                type Subject { other: Other, x: Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "other { subj { other { subj { __typename } } } }")
                .build()
        )
    }

    @Test
    fun `valid -- required selections on co-recursive objects 1`() {
        assertValid(
            """
                type Obj { subj: Subject }
                type Subject { obj: Obj, x: Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "obj { subj { obj { subj { __typename } } } }")
                .build()
        )
    }

    @Test
    fun `valid -- required selections on co-recursive objects 2`() {
        assertValid(
            """
                type Obj { subj:Subject }
                type Subject { obj:Obj, x:Int, y:Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "obj { subj { y } }")
                .fieldResolverEntry("Subject" to "y", "obj { subj { __typename } }")
                .build()
        )
    }

    @Test
    fun `valid -- abstract system coordinate`() {
        assertValid(
            """
                type Subject { x: Int u: Union }
                union Union = Subject
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "u { __typename }")
                .build()
        )
    }

    @Test
    fun `valid -- field checker rss references self`() {
        assertValid(
            "type Subject { x: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "x")
                .build()
        )
    }

    @Test
    fun `valid -- field checker references self with resolver`() {
        assertValid(
            "type Subject { x: Int y: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "x")
                .fieldResolverEntry("Subject" to "x", "y")
                .build()
        )
    }

    @Test
    fun `valid -- field checker with multiple required selections`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "y z")
                .build()
        )
    }

    @Test
    fun `valid -- multiple field checkers for same field`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "y")
                .fieldCheckerEntry("Subject" to "x", "z")
                .build()
        )
    }

    @Test
    fun `valid -- field checker with resolver on different fields`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "z", "y")
                .build()
        )
    }

    @Test
    fun `valid -- mixed field checker and resolver dependencies`() {
        assertValid(
            "type Subject { x: Int y: Int z: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "y", "z")
                .build()
        )
    }

    @Test
    fun `valid -- type checker basic`() {
        assertValid(
            """
                type Subject { x: Int foo: Foo }
                type Foo { y : String }
            """,
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("Subject", "x foo { y }")
                .build()
        )
    }

    @Test
    fun `valid -- type checker references field of own type without resolver`() {
        assertValid(
            "type Subject { x: Int s: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("Subject", "s { x }")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via self`() {
        assertInvalid(
            "type Subject { x: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "x")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via sibling fields`() {
        assertInvalid(
            "type Subject { x: Int, y: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "y", "x")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via nested object`() {
        assertInvalid(
            "type Subject { x: Int, y: Int, subject: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "subject { y }")
                .fieldResolverEntry("Subject" to "y", "subject { x }")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via nested object 1`() {
        assertInvalid(
            "type Subject { x: Int, y: Int, subject: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "y", "subject { x }")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via nested object 2`() {
        assertInvalid(
            "type Subject { x: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "x { __typename }")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via nested object for each coordinate`() {
        assertInvalid(
            "type Subject { x: Int, y: Int, subject: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "subject { x }")
                .fieldResolverEntry("Subject" to "y", "subject { y }")
                .build()
        )
    }

    @Test
    fun `invalid -- same coordinate with cycle itself`() {
        assertInvalid(
            "type Subject { x: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "x")
                .fieldResolverEntry("Subject" to "x", "x")
                .build()
        )
    }

    @Test
    fun `invalid -- same coordinate recursion with cycle from nested object`() {
        assertInvalid(
            "type Subject { x: Int, y: Int, subject: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "x", "subject { y }")
                .fieldResolverEntry("Subject" to "y", "subject { x }")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via interface`() {
        assertInvalid(
            """
                type Subject implements Interface { x: Int, iface: Interface }
                interface Interface { x: Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "iface { x }")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via interface 2`() {
        assertInvalid(
            """
                type Subject implements Interface { x: Int, iface: Interface }
                interface Interface { x: Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "iface { ... on Subject { x } }")
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via union`() {
        assertInvalid(
            """
                type Subject { x: Int, union: Union }
                union Union = Subject
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "union { ... on Subject { x } }")
                .build()
        )
    }

    @Test
    fun `invalid -- cycle through query selections`() {
        assertInvalid(
            sdl = "extend type Query { i:Int, f:Foo } type Foo { c:Int }",
            registry = MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Query" to "i", "f { c }")
                .fieldResolverEntryForType("Query", "Foo" to "c", "i")
                .build()
        )
    }

    @Test
    fun `valid -- non-cycle through type checker, field resolver, field checker`() {
        assertValid(
            """
                extend type Query { i: Int f: Foo }
                type Foo { i: Int s: String }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("Foo", "s")
                .fieldResolverEntryForType("Query", "Foo" to "s", "i")
                .fieldCheckerEntry("Query" to "i", "f { i }")
                .build()
        )
    }

    @Test
    fun `invalid -- type checker, query selections, object selections`() {
        assertInvalid(
            """
                extend type Query { i: Int f: Foo }
                type Foo { i: Int s: String }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("Foo", "s")
                .fieldResolverEntryForType("Query", "Foo" to "s", "i")
                .fieldResolverEntry("Query" to "i", "f { i }")
                .build()
        )
    }

    @Test
    fun `invalid -- type checker, query selections, field checker, object selections`() {
        assertInvalid(
            """
                extend type Query { i: Int f: Foo b: Boolean }
                type Foo { i: Int s: String }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("Foo", "s")
                .fieldResolverEntryForType("Query", "Foo" to "s", "i")
                .fieldCheckerEntry("Query" to "i", "b")
                .fieldResolverEntry("Query" to "b", "f { i }")
                .build()
        )
    }

    @Test
    fun `invalid -- cycle after first element does not cause infinite loop`() {
        assertInvalid(
            """
                type Subject { x: Int, y: Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "y", "y")
                .build()
        )
    }

    @Test
    fun `invalid -- type checker references resolver with own type in RSS`() {
        assertInvalid(
            "type Subject { x: Int s: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("Subject", "x")
                .fieldResolverEntry("Subject" to "x", "s { x }")
                .build()
        )
    }

    @Test
    fun `valid -- FromQueryFieldVariable without cycle`() {
        // The resolver for Subject.field depends on Query.data via FromQueryFieldVariable
        // Query.data has no dependencies, so no cycle exists
        assertDoesNotThrow {
            validateAll(
                "extend type Query { data:Int } type Subject { field(x:Int):Int, otherField(x:Int):Int }",
                MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry(
                        "Subject" to "field",
                        "otherField(x:\$varx)",
                        VariablesResolver.fromSelectionSetVariables(
                            ParsedSelections.empty("Subject"),
                            SelectionsParser.parse("Query", "data"),
                            listOf(
                                FromQueryFieldVariable("varx", "data")
                            )
                        )
                    )
                    .build()
            )
        }
    }

    @Test
    fun `valid -- FromObjectFieldVariable without cycle`() {
        // The resolver for Subject.field depends on Subject.data via FromObjectFieldVariable
        // Subject.data has no dependencies, so no cycle exists
        assertDoesNotThrow {
            validateAll(
                "type Subject { field(x:Int):Int, otherField(x:Int):Int, data:Int }",
                MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry(
                        "Subject" to "field",
                        "otherField(x:\$varx)",
                        VariablesResolver.fromSelectionSetVariables(
                            SelectionsParser.parse("Subject", "data"),
                            ParsedSelections.empty("Query"),
                            listOf(
                                FromObjectFieldVariable("varx", "data")
                            )
                        )
                    )
                    .build()
            )
        }
    }

    @Test
    fun `invalid -- recursion via VariableResolver required selections`() {
        // The resolver for Subject.a depends on the value of Subject.c, which is fetched using a variable derived from Subject.b
        // The resolver for Subject.b depends on the value of Subject.c, which is fetched using a variable derived from Subject.a
        assertInvalid(
            "type Subject { a(x:Int):Int, b(x:Int):Int, c(x:Int):Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry(
                    "Subject" to "a",
                    "c(x:\$varx)",
                    VariablesResolver.fromSelectionSetVariables(
                        SelectionsParser.parse("Subject", "b(x:1)"),
                        ParsedSelections.empty("Query"),
                        listOf(
                            FromObjectFieldVariable("varx", "b")
                        )
                    )
                )
                .fieldResolverEntry(
                    "Subject" to "b",
                    "c(x:\$varx)",
                    VariablesResolver.fromSelectionSetVariables(
                        SelectionsParser.parse("Subject", "a(x:2)"),
                        ParsedSelections.empty("Query"),
                        listOf(
                            FromObjectFieldVariable("varx", "a")
                        )
                    )
                )
                .build()
        )
    }

    @Test
    fun `invalid -- recursion via FromQueryFieldVariable required selections`() {
        // The resolver for Subject.a depends on a variable derived from Query.b
        // The resolver for Query.b depends on a variable derived from Query.a
        // This creates a cycle in the query field dependencies
        assertInvalid(
            "extend type Query { a(x:Int):Int, b(x:Int):Int } type Subject { field(x:Int):Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry(
                    "Subject" to "field",
                    "field(x:\$varx)",
                    VariablesResolver.fromSelectionSetVariables(
                        ParsedSelections.empty("Subject"),
                        SelectionsParser.parse("Query", "b(x:1)"),
                        listOf(
                            FromQueryFieldVariable("varx", "b")
                        )
                    )
                )
                .fieldResolverEntryForType(
                    "Query",
                    "Query" to "b",
                    "a(x:\$vary)",
                    VariablesResolver.fromSelectionSetVariables(
                        ParsedSelections.empty("Query"),
                        SelectionsParser.parse("Query", "a(x:2)"),
                        listOf(
                            FromQueryFieldVariable("vary", "a")
                        )
                    )
                )
                .build()
        )
    }

    @Test
    fun `invalid -- mixed object and query field variable cycle`() {
        // Subject.a depends on Query.queryField via FromQueryFieldVariable
        // Query.queryField depends on Subject.b via object selection
        // Subject.b depends on Subject.a via object selection
        // This creates a cycle: Subject.a -> Query.queryField -> Subject.b -> Subject.a
        assertInvalid(
            "extend type Query { queryField(x:Int):Int } type Subject { a(x:Int):Int, b(x:Int):Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry(
                    "Subject" to "a",
                    "a(x:\$varx)",
                    VariablesResolver.fromSelectionSetVariables(
                        ParsedSelections.empty("Subject"),
                        SelectionsParser.parse("Query", "queryField(x:1)"),
                        listOf(
                            FromQueryFieldVariable("varx", "queryField")
                        )
                    )
                ).fieldResolverEntryForType(
                    "Query",
                    "Query" to "queryField",
                    "queryField(x:\$vary)",
                    VariablesResolver.fromSelectionSetVariables(
                        SelectionsParser.parse("Subject", "b(x:2)"),
                        ParsedSelections.empty("Query"),
                        listOf(
                            FromObjectFieldVariable("vary", "b")
                        )
                    )
                ).fieldResolverEntry(
                    "Subject" to "b",
                    "a(x:\$varz)",
                    VariablesResolver.fromSelectionSetVariables(
                        SelectionsParser.parse("Subject", "a(x:3)"),
                        ParsedSelections.empty("Query"),
                        listOf(
                            FromObjectFieldVariable("varz", "a")
                        )
                    )
                )
                .build()
        )
    }

    @Test
    fun `invalid -- field checker and resolver create cycle`() {
        assertInvalid(
            "type Subject { x: Int y: Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "y")
                .fieldResolverEntry("Subject" to "y", "x")
                .build()
        )
    }

    @Test
    fun `invalid -- field checker mixed with resolver cycle through nested object`() {
        assertInvalid(
            "type Subject { x: Int y: Int subject: Subject }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "x", "subject { y }")
                .fieldResolverEntry("Subject" to "y", "subject { x }")
                .build()
        )
    }

    @Test
    fun `invalid -- cycle with field checker, resolver, and variables`() {
        // Field checker on Subject.a requires Subject.b
        // Resolver for Subject.b requires Subject.c with variable from Subject.a
        // This creates a cycle: Subject.a -> Subject.b -> Subject.a (via variable)
        assertInvalid(
            "type Subject { a(x:Int):Int, b:Int, c(x:Int):Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("Subject" to "a", "b")
                .fieldResolverEntryForType(
                    selectionsType = "Subject",
                    coord = "Subject" to "b",
                    selectionsString = "c(x:\$varx)",
                    variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                        SelectionsParser.parse("Subject", "a(x:1)"),
                        null,
                        listOf(
                            FromObjectFieldVariable("varx", "a")
                        )
                    )
                )
                .build()
        )
    }

    @Test
    fun `error path -- required selection traversal`() {
        val err = assertThrows<RequiredSelectionsCycleException> {
            validateAll(
                "type Subject { x: Int, y: Int }",
                MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Subject" to "x", "y")
                    .fieldResolverEntry("Subject" to "y", "x")
                    .build()
            )
        }
        err.assertErrorPath(
            "Subject.x",
            "Subject.y",
            "Subject.x"
        )
    }

    @Test
    fun `error path -- selection set traversal`() {
        val err = assertThrows<RequiredSelectionsCycleException> {
            validateAll(
                """
                    type Foo { x: Int bar: Bar }
                    type Bar { x: Int foo: Foo }
                """.trimIndent(),
                MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Foo" to "x", "bar { x }")
                    .fieldResolverEntry("Bar" to "x", "foo { x }")
                    .build()
            )
        }
        err.assertErrorPath(
            "Foo.x",
            "Bar.x",
            "Foo.x"
        )
    }

    @Test
    fun `error message for malformed selection set includes text of that selection set`() {
        val badSelectionSet = "{ someUniqueText {} }"
        val err = assertThrows<IllegalArgumentException> {
            validateAll(
                "type Foo { x: Int }",
                MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Foo" to "x", badSelectionSet)
                    .build()
            )
        }
        if (!err.message!!.contains(badSelectionSet)) {
            fail<Unit>("Message \"${err.message}\" does not contain \"$badSelectionSet\"")
        }
    }

    /**
     * Given a schema, plus a collection of required selection-sets entries,
     * asserts that the cycle detector does _not_ detect a cycle.
     * See [validateAll] for description of RSS representation.
     */
    private fun assertValid(
        sdl: String,
        registry: MockRequiredSelectionSetRegistry
    ) {
        assertDoesNotThrow {
            validateAll(sdl, registry)
        }
    }

    /**
     * Similar to [assertValid], except that it asserts that the cycle
     * detector _does_ detect an error.
     */
    private fun assertInvalid(
        sdl: String,
        registry: MockRequiredSelectionSetRegistry
    ) {
        assertThrows<RequiredSelectionsCycleException> {
            validateAll(sdl, registry)
        }
    }

    private fun RequiredSelectionsCycleException.assertErrorPath(vararg expectedPath: String) {
        assertEquals(expectedPath.toList(), this.path.toList())
    }

    /**
     * Given a schema and a collection of required selection sets,
     * runs the cycle-detection validator against that collection.
     *
     * Throws the validator's exception for the first RSS in the list
     * that does not pass validation, returns normally if all pass.
     *
     * The RSS collection is represented as a list of <coord, rss> pairs
     * where "coord" is a field coordinate and "rss" is the text of a
     * selection set on the type-dimension of the field coordinate.
     */
    private fun validateAll(
        sdl: String,
        registry: MockRequiredSelectionSetRegistry,
    ) {
        val schema = MockSchema.mk(
            """
            extend type Query { empty: Int }
            $sdl
            """.trimIndent()
        )
        val validator = RequiredSelectionsAreAcyclic(schema)

        val coordToValidate = registry.entries
            .map {
                when (it) {
                    is MockRequiredSelectionSetRegistry.FieldEntry -> it.coord
                    is MockRequiredSelectionSetRegistry.TypeCheckerEntry -> it.typeName to null
                }
            }
            .toSet()
        coordToValidate.map { coord ->
            val ctx = RequiredSelectionsValidationCtx(
                coord.first,
                coord.second,
                registry
            )
            validator.validate(ctx)
        }
    }
}
