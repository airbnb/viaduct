package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
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
        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("StableApi").contains("InternalApi")
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
        assertThat(findings).hasSize(1)
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
        assertThat(findings).hasSize(1)
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
        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("StableApi").contains("InternalApi").contains("ExperimentalApi")
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
        assertThat(findings).hasSize(1)
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
        assertThat(findings).hasSize(2)
    }

    // --- no violations ---

    @Test
    fun `single StableApi is fine`() {
        assertThat(rule.lint("@StableApi class Foo".trimIndent())).isEmpty()
    }

    @Test
    fun `single InternalApi is fine`() {
        assertThat(rule.lint("@InternalApi class Foo".trimIndent())).isEmpty()
    }

    @Test
    fun `single ExperimentalApi is fine`() {
        assertThat(rule.lint("@ExperimentalApi fun bar() {}".trimIndent())).isEmpty()
    }

    @Test
    fun `no stability annotation is fine`() {
        assertThat(rule.lint("class Foo".trimIndent())).isEmpty()
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
        assertThat(findings).isEmpty()
    }
}
