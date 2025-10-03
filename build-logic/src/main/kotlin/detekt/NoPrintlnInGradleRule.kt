package detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * A custom Detekt rule that flags the use of `println(...)` in Gradle scripts.
 */
class NoPrintlnInGradleRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "NoPrintlnInGradle",
        severity = Severity.CodeSmell,
        description = "Avoid println(...) in Gradle scripts; use Gradle logger.",
        debt = Debt.FIVE_MINS
    )

    private var isGradleScript = false

    override fun visitKtFile(file: KtFile) {
        isGradleScript = file.name.endsWith(".gradle.kts")
        if (!isGradleScript) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        if (!isGradleScript) return
        if (expression.calleeExpression?.text == "println") {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Use logger.lifecycle/info/debug instead of println in Gradle scripts."
                )
            )
        }
        super.visitCallExpression(expression)
    }
}
