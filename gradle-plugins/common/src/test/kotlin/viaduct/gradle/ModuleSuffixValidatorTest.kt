package viaduct.gradle

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ModuleSuffixValidatorTest {
    @Test
    fun `distinct non-prefixing suffixes are valid`() {
        val errors = ModuleSuffixValidator.validate(
            listOf(
                ModuleSuffixEntry(":app:alpha", "alpha"),
                ModuleSuffixEntry(":app:beta", "beta"),
                ModuleSuffixEntry(":app:gamma", "gamma.sub"),
            )
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `single module may use an empty suffix`() {
        val errors = ModuleSuffixValidator.validate(
            listOf(ModuleSuffixEntry(":app:only", ""))
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `duplicate suffixes are rejected with stable error code`() {
        val errors = ModuleSuffixValidator.validate(
            listOf(
                ModuleSuffixEntry(":app:alpha", "catalog"),
                ModuleSuffixEntry(":app:beta", "catalog"),
            )
        )

        errors.shouldHaveSize(1)
        errors.single().code shouldBe ModuleSuffixValidator.DUPLICATE_SUFFIX
        errors.single().message shouldContain ":app:alpha"
        errors.single().message shouldContain ":app:beta"
        errors.single().message shouldContain "catalog"
    }

    @Test
    fun `dot-segment prefix collisions are rejected with stable error code`() {
        val errors = ModuleSuffixValidator.validate(
            listOf(
                ModuleSuffixEntry(":app:outer", "catalog"),
                ModuleSuffixEntry(":app:inner", "catalog.pricing"),
            )
        )

        errors.shouldHaveSize(1)
        errors.single().code shouldBe ModuleSuffixValidator.PREFIX_COLLISION
        errors.single().message shouldContain ":app:outer"
        errors.single().message shouldContain ":app:inner"
        errors.single().message shouldContain "catalog.pricing"
    }

    @Test
    fun `shared string prefix without dot-segment boundary is valid`() {
        val errors = ModuleSuffixValidator.validate(
            listOf(
                ModuleSuffixEntry(":app:cat", "cat"),
                ModuleSuffixEntry(":app:catalog", "catalog"),
            )
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `empty suffix with sibling modules gets a specific error`() {
        val errors = ModuleSuffixValidator.validate(
            listOf(
                ModuleSuffixEntry(":app:root", ""),
                ModuleSuffixEntry(":app:payments", "payments"),
            )
        )

        errors.shouldHaveSize(1)
        errors.single().code shouldBe ModuleSuffixValidator.EMPTY_SUFFIX_WITH_SIBLINGS
        errors.single().message shouldContain "exactly one module"
        errors.single().message shouldContain ":app:root"
    }
}
