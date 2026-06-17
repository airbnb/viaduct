package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ApiAnnotationConflictRuleTest {
    private val rule = ApiAnnotationConflictRule(Config.empty)

    // --- violations ---

    @Test
    fun `StableApi function inside InternalApi class`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo {
                @StableApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
        findings.first().message shouldContain "bar"
        findings.first().message shouldContain "Foo"
    }

    @Test
    fun `StableApi property inside InternalApi class`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo {
                @StableApi
                val x: Int = 0
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `StableApi nested class inside InternalApi class`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo {
                @StableApi
                class Bar
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `StableApi secondary constructor inside InternalApi class`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo(val x: Int) {
                @StableApi
                constructor() : this(0)
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `multiple violations in single class`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo {
                @StableApi
                fun bar() {}

                @StableApi
                val x: Int = 0
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(2)
    }

    @Test
    fun `StableApi function inside ExperimentalApi class`() {
        val findings = rule.lint(
            """
            @ExperimentalApi
            class Foo {
                @StableApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `StableApi function inside VisibleForTest class`() {
        val findings = rule.lint(
            """
            @VisibleForTest
            class Foo {
                @StableApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `StableApi member inside InternalApi nested class within StableApi outer class`() {
        val findings = rule.lint(
            """
            @StableApi
            class Outer {
                @InternalApi
                class Inner {
                    @StableApi
                    var x: Int = 0
                }
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `FQCN annotations are handled`() {
        val findings = rule.lint(
            """
            @viaduct.apiannotations.InternalApi
            class Foo {
                @viaduct.apiannotations.StableApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    // --- no violations ---

    @Test
    fun `InternalApi method inside StableApi class is fine`() {
        val findings = rule.lint(
            """
            @StableApi
            class Foo {
                @InternalApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldBeEmpty()
    }

    @Test
    fun `unannotated method inside InternalApi class is fine`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo {
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldBeEmpty()
    }

    @Test
    fun `InternalApi method inside InternalApi class is fine`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo {
                @InternalApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldBeEmpty()
    }

    @Test
    fun `ExperimentalApi method inside InternalApi class is fine`() {
        val findings = rule.lint(
            """
            @InternalApi
            class Foo {
                @ExperimentalApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldBeEmpty()
    }

    @Test
    fun `StableApi method in unannotated class is fine`() {
        val findings = rule.lint(
            """
            class Foo {
                @StableApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldBeEmpty()
    }

    @Test
    fun `StableApi method inside StableApi class is fine`() {
        val findings = rule.lint(
            """
            @StableApi
            class Foo {
                @StableApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldBeEmpty()
    }
}
