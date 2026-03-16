package detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration

private val NON_STABLE_ANNOTATIONS = setOf("ExperimentalApi", "InternalApi", "VisibleForTest")

class ApiAnnotationConflictRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "ApiAnnotationConflict",
        severity = Severity.Defect,
        description = "@StableApi members must not be declared inside non-stable classes.",
        debt = Debt.TEN_MINS
    )

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)

        val classAnnotation = NON_STABLE_ANNOTATIONS.firstOrNull { classOrObject.hasAnnotation(it) } ?: return

        classOrObject.declarations.forEach { member ->
            if (member.hasAnnotation("StableApi")) {
                reportConflict(member, classOrObject, classAnnotation)
            }
        }
    }

    private fun reportConflict(
        member: KtDeclaration,
        enclosingClass: KtClassOrObject,
        classAnnotation: String
    ) {
        val memberName = (member as? KtNamedDeclaration)?.name ?: member::class.simpleName.orEmpty()
        val className = enclosingClass.name.orEmpty()
        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(member),
                message = "'$memberName' is @StableApi but is declared inside '$className' which is @$classAnnotation."
            )
        )
    }
}
