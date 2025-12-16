package detekt

import common.GradleConstants
import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Flags Gradle dependency declarations using raw String coordinates
 * (e.g., implementation("group:artifact:version") or group/name/version named params),
 * enforcing the use of Version Catalog (libs.*), project(), platform(libs.*), etc.
 */
class NoStringDependenciesInGradleRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoStringDependenciesInGradle",
        severity = Severity.CodeSmell,
        description = "Dependencies must use version catalog (libs.*), project(), or platform(libs.*). " +
            "Raw String coordinates and group/name/version named params are forbidden.",
        debt = Debt.TEN_MINS
    )

    private var isGradleScript = false

    override fun visitKtFile(file: KtFile) {
        isGradleScript = file.name.endsWith(".gradle.kts")
        if (!isGradleScript) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        if (!isGradleScript) return

        val callee = expression.calleeExpression?.text ?: return
        if (!isDependencyConfigCall(callee)) {
            // Not a dependency configuration call, nothing to do.
            super.visitCallExpression(expression)
            return
        }

        // Sanity check: optionally ensure we’re inside a `dependencies {}` scope, to reduce false positives.
        // If you want to be stricter, comment this out.
        val insideDependenciesBlock = expression.parents.any { isDependenciesBlock(it) }
        if (!insideDependenciesBlock) {
            super.visitCallExpression(expression)
            return
        }

        // 1) If first argument is a string literal like "g:a:v", flag it unless explicitly allowed.
        val firstArg = expression.valueArguments.firstOrNull()
        val argExpr = firstArg?.getArgumentExpression()

        if (argExpr != null) {
            // Allowed forms: libs.*, platform(libs.*), enforcedPlatform(libs.*), project(":"), testFixtures(project(":")), libs.bundles.*
            if (isAllowedExpression(argExpr)) {
                super.visitCallExpression(expression)
                return
            }

            // Literal "group:artifact:version" without interpolation → forbidden
            if (argExpr is KtStringTemplateExpression && !argExpr.hasInterpolation()) {
                reportForbidden(expression, callee, argExpr.text)
                super.visitCallExpression(expression)
                return
            }
        }

        // 2) Also forbid named params style: implementation(group = "...", name = "...", version = "...")
        val hasNamedStringParams = expression.valueArguments.any { va ->
            val name = va.getArgumentName()?.asName?.asString()
            val isNamed = name in setOf("group", "name", "version")
            val v = va.getArgumentExpression()
            val isString = v is KtStringTemplateExpression && !v.hasInterpolation()
            isNamed && isString
        }

        if (hasNamedStringParams) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Use version catalog (libs.*) or project()/platform(libs.*). " +
                        "Do not use group/name/version string parameters."
                )
            )
        }

        super.visitCallExpression(expression)
    }

    private fun isDependencyConfigCall(name: String): Boolean {
        if (name in GradleConstants.KNOWN_CONFIGURATIONS) return true
        return GradleConstants.CONFIGURATION_SUFFIXES.any { name.endsWith(it) }
    }

    private fun reportForbidden(
        expression: KtCallExpression,
        callee: String,
        literal: String
    ) {
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Dependency coordinates must not be raw string literals. " +
                    "Use $callee(libs.some.alias) or project(\":module\") or platform(libs.some.bom). Found: $literal"
            )
        )
    }

    /**
     * True if the expression is one of the allowed forms:
     *   - libs.something / libs.bundles.something
     *   - project(":module")
     *   - platform(libs.something) / enforcedPlatform(libs.something)
     *   - testFixtures(project(":module"))
     */
    private fun isAllowedExpression(expr: KtExpression): Boolean {
        val text = expr.text.trim()

        // plain catalog alias or bundle
        if (text.matches(Regex("""^libs(\.bundles)?\.[A-Za-z0-9_.]+$"""))) return true

        // project(":...")
        if (text.startsWith("project(")) return true

        // platform(libs...) or enforcedPlatform(libs...)
        if (text.matches(Regex("""^(enforcedPlatform|platform)\s*\(\s*libs(\.bundles)?\.[A-Za-z0-9_.]+\s*\)$"""))) return true

        // testFixtures(project(":..."))
        if (text.matches(Regex("""^testFixtures\s*\(\s*project\s*\(.*\)\s*\)$"""))) return true

        return false
    }

    /**
     * Try to detect a dependencies { ... } block. We check if the PSI element is
     * a call 'dependencies' or a lambda passed to it.
     */
    private fun isDependenciesBlock(el: PsiElement): Boolean {
        // Example shapes:
        // dependencies { ... }
        // dependencies(…) { ... }
        return when (el) {
            is KtCallExpression -> el.calleeExpression?.text == "dependencies"
            is KtLambdaExpression -> (el.parent?.parent as? KtCallExpression)
                ?.calleeExpression?.text == "dependencies"

            else -> false
        }
    }
}
