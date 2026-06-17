package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SingleApiAnnotationRuleTest {
    private val rule = SingleApiAnnotationRule(Config.empty)

    // --- violations ---

    @Test
    fun `class with StableApi and InternalApi`() {
        val findings = rule.lint(
            """
            @StableApi
            @InternalApi
            class Foo
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
        findings.first().message shouldContain "StableApi"
        findings.first().message shouldContain "InternalApi"
    }

    @Test
    fun `function with StableApi and ExperimentalApi`() {
        val findings = rule.lint(
            """
            @StableApi
            @ExperimentalApi
            fun bar() {}
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `property with InternalApi and VisibleForTest`() {
        val findings = rule.lint(
            """
            @InternalApi
            @VisibleForTest
            val x: Int = 0
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `three annotations on same declaration`() {
        val findings = rule.lint(
            """
            @StableApi
            @InternalApi
            @ExperimentalApi
            class Foo
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
        findings.first().message shouldContain "StableApi"
        findings.first().message shouldContain "InternalApi"
        findings.first().message shouldContain "ExperimentalApi"
    }

    @Test
    fun `FQCN annotations are handled`() {
        val findings = rule.lint(
            """
            @viaduct.apiannotations.StableApi
            @viaduct.apiannotations.InternalApi
            class Foo
            """.trimIndent()
        )
        findings.shouldHaveSize(1)
    }

    @Test
    fun `member inside class both with duplicate annotations`() {
        val findings = rule.lint(
            """
            @StableApi
            @InternalApi
            class Foo {
                @StableApi
                @ExperimentalApi
                fun bar() {}
            }
            """.trimIndent()
        )
        findings.shouldHaveSize(2)
    }

    // --- no violations ---

    @Test
    fun `single StableApi is fine`() {
        rule.lint("@StableApi class Foo".trimIndent()).shouldBeEmpty()
    }

    @Test
    fun `single InternalApi is fine`() {
        rule.lint("@InternalApi class Foo".trimIndent()).shouldBeEmpty()
    }

    @Test
    fun `single ExperimentalApi is fine`() {
        rule.lint("@ExperimentalApi fun bar() {}".trimIndent()).shouldBeEmpty()
    }

    @Test
    fun `no stability annotation is fine`() {
        rule.lint("class Foo".trimIndent()).shouldBeEmpty()
    }

    @Test
    fun `stability annotation mixed with non-stability annotation is fine`() {
        val findings = rule.lint(
            """
            @StableApi
            @Deprecated("use something else")
            class Foo
            """.trimIndent()
        )
        findings.shouldBeEmpty()
    }
}
