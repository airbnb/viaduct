package detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.parents

private val DISALLOWED_PREFIXES = listOf("mk", "make")
private const val PREFERRED_PREFIX = "create"

/**
 * Ensures that factory methods in stable API use the "create" prefix naming convention.
 *
 * This rule detects functions that start with "mk" or "make" and warns that
 * the Viaduct style guide requires using the "create" prefix for factory methods instead.
 *
 * The rule only applies to functions that are annotated with @StableApi or are
 * contained within a class/object annotated with @StableApi.
 *
 * **Examples of violations:**
 * ```kotlin
 * @StableApi
 * fun mkFoo(): Foo = Foo()
 *
 * @StableApi
 * object FooFactory {
 *     fun makeBar(): Bar = Bar()  // Also a violation since container is @StableApi
 * }
 * ```
 *
 * **Correct usage:**
 * ```kotlin
 * @StableApi
 * fun createFoo(): Foo = Foo()
 *
 * @StableApi
 * object FooFactory {
 *     fun createBar(): Bar = Bar()
 * }
 * ```
 *
 * @see `viaduct.apiannotations.StableApi`
 */
class FactoryMethodNamingRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "FactoryMethodNaming",
        severity = Severity.Style,
        description = "Factory methods should use the 'create' prefix instead of 'mk' or 'make'.",
        debt = Debt.FIVE_MINS
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        val functionName = function.name ?: return

        // Check if function name starts with a disallowed prefix
        val violatingPrefix = DISALLOWED_PREFIXES.find { prefix ->
            functionName.startsWith(prefix) && functionName.length > prefix.length &&
                functionName[prefix.length].isUpperCase()
        } ?: return

        // Only check functions covered by @StableApi
        if (!function.isCoveredByStableApi()) return

        val suggestedName = PREFERRED_PREFIX + functionName.removePrefix(violatingPrefix)

        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(function),
                message = "Factory method '$functionName' should use the 'create' prefix. " +
                    "Consider renaming to '$suggestedName'."
            )
        )
    }

    /**
     * A function is covered by @StableApi if it or any of its enclosing classes/objects
     * is annotated with @StableApi.
     */
    private fun KtNamedFunction.isCoveredByStableApi(): Boolean {
        if (this.containingKtFile.hasStableApiAnnotation()) return true

        if (this.hasStableApiAnnotation()) return true

        val owners = this.parents.filterIsInstance<KtClassOrObject>()
        return owners.any { it.hasStableApiAnnotation() }
    }

    /**
     * Returns true if the given declaration has a @StableApi annotation.
     */
    private fun KtModifierListOwner.hasStableApiAnnotation(): Boolean {
        val entries = this.annotationEntries
        if (entries.isEmpty()) return false
        return entries.any { entry ->
            val entryName = entry.typeReference?.text ?: return@any false

            val name = if (entryName.contains('.')) {
                entryName.substringAfterLast('.')
            } else {
                entryName
            }

            name == "StableApi"
        }
    }

    /**
     * Checks if the file has a @StableApi annotation at the top level.
     */
    private fun KtFile.hasStableApiAnnotation(): Boolean {
        val entries = this.annotationEntries
        if (entries.isEmpty()) return false
        return entries.any { entry ->
            val entryName = entry.typeReference?.text ?: return@any false

            val name = if (entryName.contains('.')) {
                entryName.substringAfterLast('.')
            } else {
                entryName
            }

            name == "StableApi"
        }
    }
}
