package detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration

private val STABILITY_ANNOTATIONS = setOf("StableApi", "ExperimentalApi", "InternalApi", "VisibleForTest")

class SingleApiAnnotationRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "SingleApiAnnotation",
        severity = Severity.Defect,
        description = "A declaration must have at most one API stability annotation.",
        debt = Debt.FIVE_MINS
    )

    override fun visitDeclaration(dcl: KtDeclaration) {
        super.visitDeclaration(dcl)

        val matched = dcl.annotationsMatching(STABILITY_ANNOTATIONS)
        if (matched.size > 1) {
            val name = (dcl as? KtNamedDeclaration)?.name ?: dcl::class.simpleName.orEmpty()
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(dcl),
                    message = "'$name' has multiple stability annotations: ${matched.joinToString()}."
                )
            )
        }
    }
}
