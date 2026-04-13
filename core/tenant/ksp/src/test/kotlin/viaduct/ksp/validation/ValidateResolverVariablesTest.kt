package viaduct.ksp.validation

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ValidateResolverVariablesTest {
    companion object {
        private fun makeSpec(
            variables: List<ResolverVariableSpec> = emptyList(),
            fragments: List<ResolverFragmentSpec> = emptyList(),
            className: String = "TestResolver",
            packageName: String = "com.example",
            variablesProviderVarNames: Set<String> = emptySet(),
        ) = ResolverAnnotationSpec(
            fragments = fragments,
            variables = variables,
            metadata = Metadata(
                packageName = packageName,
                className = className,
                sourceFileName = "Test.kt",
                typeName = "TestType",
                fragmentType = ResolverFragmentType.OBJECT,
            ),
            variablesProviderVarNames = variablesProviderVarNames,
        )

        private fun makeVariable(
            name: String = "testVar",
            fromObjectField: String? = null,
            fromQueryField: String? = null,
            fromArgument: String? = null,
        ) = ResolverVariableSpec(
            name = name,
            fromObjectField = fromObjectField,
            fromQueryField = fromQueryField,
            fromArgument = fromArgument,
        )

        private fun makeFragment(
            fragment: String,
            fragmentType: ResolverFragmentType = ResolverFragmentType.OBJECT,
            typeName: String = "TestType",
        ) = ResolverFragmentSpec(
            fragment = fragment,
            metadata = Metadata(
                packageName = "com.example",
                className = "TestResolver",
                sourceFileName = "Test.kt",
                typeName = typeName,
                fragmentType = fragmentType,
            ),
        )
    }

    @Test
    fun `variables without fragment fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromObjectField = "id")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("neither objectValueFragment nor queryValueFragment") })
    }

    @Test
    fun `variables with objectValueFragment passes`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromObjectField = "id")),
            fragments = listOf(makeFragment("fragment Main on TestType { id(x: \$testVar) }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `variable with no source set fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("found 0 set") })
    }

    @Test
    fun `variable with multiple sources fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromObjectField = "id", fromQueryField = "user.id")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("found 2 set") })
    }

    @Test
    fun `empty fromObjectField path fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromObjectField = "")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("blank fromObjectField") })
    }

    @Test
    fun `empty fromQueryField path fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromQueryField = "")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("blank fromQueryField") })
    }

    @Test
    fun `empty fromArgument path fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromArgument = "")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("blank fromArgument") })
    }

    @Test
    fun `duplicate variable names across annotations fails`() {
        val spec = makeSpec(
            variables = listOf(
                makeVariable(name = "viewerId", fromObjectField = "id"),
                makeVariable(name = "viewerId", fromArgument = "viewerId"),
            ),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("Duplicate variable names") && it.contains("viewerId") })
    }

    @Test
    fun `duplicate variable name between annotation and provider fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "viewerId", fromArgument = "viewerId")),
            fragments = listOf(makeFragment("fragment Main on TestType { name(x: \$viewerId) }")),
            variablesProviderVarNames = setOf("viewerId"),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("Duplicate variable names") && it.contains("viewerId") })
    }

    @Test
    fun `fromObjectField without objectValueFragment fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromObjectField = "id")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("uses fromObjectField but no objectValueFragment") })
    }

    @Test
    fun `fromQueryField without queryValueFragment fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(fromQueryField = "user.id")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("uses fromQueryField but no queryValueFragment") })
    }

    @Test
    fun `multiple errors reported together`() {
        val spec = makeSpec(
            variables = listOf(
                makeVariable(name = "var1"),
                makeVariable(name = "var1", fromObjectField = "", fromQueryField = "path"),
            ),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.size > 1, "Expected multiple errors but got ${errors.size}: $errors")
    }

    @Test
    fun `no variables produces no errors`() {
        val spec = makeSpec(variables = emptyList())
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `unused variable fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "viewerId", fromObjectField = "id")),
            fragments = listOf(makeFragment("fragment Main on TestType { id name }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("viewerId") && it.contains("not referenced") })
    }

    @Test
    fun `used variable passes`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "viewerId", fromObjectField = "id")),
            fragments = listOf(makeFragment("fragment Main on TestType { id name(viewerId: \$viewerId) }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `variable used in nested field passes`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "userId", fromObjectField = "user")),
            fragments = listOf(makeFragment("fragment Main on TestType { user { name(id: \$userId) } }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `variable used in inline fragment passes`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "viewerId", fromArgument = "viewerId")),
            fragments = listOf(makeFragment("fragment Main on TestType { ... on TestType { name(viewerId: \$viewerId) } }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `variable path not found in selections fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "user.name")),
            fragments = listOf(makeFragment("fragment Main on TestType { id }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("path 'user.name' not found in object fragment selections") })
    }

    @Test
    fun `variable path found in selections passes`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "user.name")),
            fragments = listOf(makeFragment("fragment Main on TestType { user { name(x: \$myVar) } }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `nested path resolves correctly`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "a.b.c")),
            fragments = listOf(makeFragment("fragment Main on TestType { a { b { c(x: \$myVar) } } }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `partial path fails`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "a.b.c")),
            fragments = listOf(makeFragment("fragment Main on TestType { a { b(x: \$myVar) } }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("path 'a.b.c' not found in object fragment selections") })
    }

    @Test
    fun `single-segment path works`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "id")),
            fragments = listOf(makeFragment("fragment Main on TestType { id(x: \$myVar) name }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `aliased field path resolves using alias name`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "myAlias.sub")),
            fragments = listOf(makeFragment("fragment Main on TestType { myAlias: original { sub(x: \$myVar) } }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `path through inline fragment resolves`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "name")),
            fragments = listOf(makeFragment("fragment Main on TestType { ... on TestType { name(x: \$myVar) } }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `path through named fragment spread resolves`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromObjectField = "name")),
            fragments = listOf(makeFragment("fragment Main on TestType { ...Details } fragment Details on TestType { name(x: \$myVar) }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `fromQueryField path resolved against query fragment`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "myVar", fromQueryField = "viewer.name")),
            fragments = listOf(makeFragment("fragment Main on Query { viewer { name(x: \$myVar) } }", fragmentType = ResolverFragmentType.QUERY, typeName = "Query")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `used variablesProvider var passes`() {
        val spec = makeSpec(
            fragments = listOf(makeFragment("fragment Main on TestType { name(x: \$providerVar) }")),
            variablesProviderVarNames = setOf("providerVar"),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `unused variablesProvider var fails`() {
        val spec = makeSpec(
            fragments = listOf(makeFragment("fragment Main on TestType { name }")),
            variablesProviderVarNames = setOf("unusedVar"),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("unusedVar") && it.contains("not referenced") })
    }

    @Test
    fun `multiple unused variables reported`() {
        val spec = makeSpec(
            variables = listOf(
                makeVariable(name = "varA", fromObjectField = "a"),
                makeVariable(name = "varB", fromObjectField = "b"),
            ),
            fragments = listOf(makeFragment("fragment Main on TestType { id name }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("varA") && it.contains("varB") && it.contains("not referenced") })
    }

    @Test
    fun `unbound variable reference fails`() {
        val spec = makeSpec(
            fragments = listOf(makeFragment("fragment Main on TestType { name(viewerId: \$viewerId) }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("\$viewerId") && it.contains("undeclared") })
    }

    @Test
    fun `variable from @Variables provider satisfies reference`() {
        val spec = makeSpec(
            fragments = listOf(makeFragment("fragment Main on TestType { name(viewerId: \$viewerId) }")),
            variablesProviderVarNames = setOf("viewerId"),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.none { it.contains("undeclared") })
    }

    @Test
    fun `mixed sources cover all references`() {
        val spec = makeSpec(
            variables = listOf(makeVariable(name = "varA", fromArgument = "argA")),
            fragments = listOf(makeFragment("fragment Main on TestType { name(a: \$varA, b: \$varB) }")),
            variablesProviderVarNames = setOf("varB"),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.none { it.contains("undeclared") })
    }

    @Test
    fun `multiple unbound variables reported`() {
        val spec = makeSpec(
            fragments = listOf(makeFragment("fragment Main on TestType { name(a: \$a, b: \$b) }")),
        )
        val errors = ValidateResolverVariables(listOf(spec)).validate()
        assertTrue(errors.any { it.contains("\$a") && it.contains("\$b") && it.contains("undeclared") })
    }
}
