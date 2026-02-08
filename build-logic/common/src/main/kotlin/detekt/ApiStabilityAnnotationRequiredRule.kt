package detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierTypeOrDefault

private val TEST_SOURCE_SETS = setOf("test", "testFixtures", "jmh", "integrationTest")
private val STABILITY_ANNOTATION_SIMPLE_NAMES = setOf(
    "StableApi",
    "ExperimentalApi",
    "InternalApi",
    "TestingApi",
)

/**
 * Ensures that public API declarations are annotated with an API stability annotations.
 *
 * "Covered" means:
 * - The declaration is annotated directly, OR
 * - Any enclosing class/object is annotated.
 *
 * This rule by default on all packages is disabled, see `detekt-viaduct.yml` for default configuration.
 * The plugin `bcv-api.gradle.kts` enables this rule for source sets that produce public APIs applying the rules
 * in the file `detekt-viaduct-bcv.yml`.
 *
 * @see `bcv-api.gradle.kts` plugin
 * @see `viaduct.apiannotations` package for covered annotations.
 */
class ApiStabilityAnnotationRequiredRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "ApiStabilityAnnotationRequired",
        severity = Severity.Defect,
        description = "Public API declarations must declare an API stability annotation.",
        debt = Debt.TWENTY_MINS
    )

    override fun visitDeclaration(dcl: KtDeclaration) {
        // Ignore declarations in files without a valid path (e.g., generated files).
        if (dcl.isVirtualFile()) return

        if (!dcl.isApiRoot()) return

        if (dcl.containingKtFile.isTestSource()) return

        super.visitDeclaration(dcl)

        if (!dcl.isRelevantDeclarationKind()) return

        if (!dcl.isEffectivelyPublic()) return

        if (dcl.isOverride()) return

        if (dcl.isCoveredByStabilityAnnotation()) return

        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(dcl),
                message = "Public API '${dcl.renderName()}' must be annotated with one of the api stability annotations: ${STABILITY_ANNOTATION_SIMPLE_NAMES}.",
            )
        )
    }

    /**
     * Only main packages are relevant for this rule. Test source sets are ignored.
     */
    private fun KtFile.isTestSource(): Boolean {
        val path = virtualFile?.path ?: return false

        val segments = path.split('/', '\\')

        val srcIndex = segments.lastIndexOf("src")
        if (srcIndex == -1 || srcIndex + 1 >= segments.size) return false

        return segments[srcIndex + 1] in TEST_SOURCE_SETS
    }

    /**
     * Override declarations are not covered, since the overridden field should be covered.
     */
    private fun KtDeclaration.isOverride() = this is KtNamedDeclaration && this.hasModifier(KtTokens.OVERRIDE_KEYWORD)

    /**
     * Generated files do not need to be checked.
     */
    private fun KtDeclaration.isVirtualFile() = (this.containingKtFile.virtualFile?.path == null)

    /**
     * Only certain kinds of declarations are relevant for this rule.
     */
    private fun KtDeclaration.isRelevantDeclarationKind(): Boolean =
        when (this) {
            is KtClassOrObject -> true
            is KtNamedFunction -> true
            is KtProperty -> true
            is KtTypeAlias -> true
            is KtSecondaryConstructor -> true
            else -> false
        }

    /**
     * A declaration is effectively public if it and all its enclosing classes/objects are public.
     */
    private fun KtDeclaration.isEffectivelyPublic(): Boolean {
        if (this.isLocalDeclaration()) return false

        val owners = this.parents.filterIsInstance<KtClassOrObject>()
        if (owners.any { it.visibilityModifierTypeOrDefault() != KtTokens.PUBLIC_KEYWORD }) return false
        return this.visibilityModifierTypeOrDefault() == KtTokens.PUBLIC_KEYWORD
    }

    /**
     * A declaration is covered by a stability annotation if it or any of its enclosing classes/objects
     * is annotated with a stability annotation.
     */
    private fun KtDeclaration.isCoveredByStabilityAnnotation(): Boolean {
        if (this.containingKtFile.hasStabilityAnnotation()) return true

        if (this.hasStabilityAnnotation()) return true

        val owners = this.parents.filterIsInstance<KtClassOrObject>()
        return owners.any { it.hasStabilityAnnotation() }
    }

    /**
     * Returns true if the given declaration or class/object has a stability annotation.
     */
    private fun KtModifierListOwner.hasStabilityAnnotation(): Boolean {
        val entries = this.annotationEntries
        if (entries.isEmpty()) return false
        return entries.any { entry ->
            val entryName = entry.typeReference?.text ?: return@any false

            val name = if (entryName.contains('.')) {
                entryName.substringAfterLast('.')
            } else {
                entryName
            }

            name in STABILITY_ANNOTATION_SIMPLE_NAMES
        }
    }

    /**
     * This checks if the file has any stability annotation at the top level.
     */
    private fun KtFile.hasStabilityAnnotation(): Boolean {
        val entries = this.annotationEntries
        if (entries.isEmpty()) return false
        return entries.any { entry ->
            val entryName = entry.typeReference?.text ?: return@any false

            val name = if (entryName.contains('.')) {
                entryName.substringAfterLast('.')
            } else {
                entryName
            }

            name in STABILITY_ANNOTATION_SIMPLE_NAMES
        }
    }

    /**
     * Renders a declaration name for error messages.
     */
    private fun KtDeclaration.renderName(): String = (this as? KtNamedDeclaration)?.name ?: this::class.simpleName.orEmpty()

    /**
     * To ignore local declarations (inside functions/lambdas/accessors/init blocks).
     */
    private fun KtDeclaration.isLocalDeclaration(): Boolean {
        var p = parent
        while (p != null) {
            when (p) {
                // If we hit these, the declaration is not local (it's top-level or class-level).
                is KtFile,
                is KtClassBody,
                is KtClassOrObject -> return false

                // If we hit these first, it's local to a function/lambda/accessor/init block.
                is KtBlockExpression,
                is KtLambdaExpression,
                is KtNamedFunction,
                is KtPropertyAccessor,
                is KtAnonymousInitializer -> return true
            }
            p = p.parent
        }
        return false
    }

    /**
     * This prevents logging files that are just using classes that are not annotated,
     * to have a clean output only with the root API declarations.
     */
    private fun KtDeclaration.isApiRoot(): Boolean {
        val owners = parents.filterIsInstance<KtClassOrObject>()
        val nearestOwner = owners.firstOrNull() ?: return true
        return this == nearestOwner
    }
}
