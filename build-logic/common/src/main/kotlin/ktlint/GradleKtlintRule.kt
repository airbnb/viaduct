package ktlint

import com.pinterest.ktlint.rule.engine.core.api.ElementType.FILE
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

abstract class GradleKtlintRule(ruleId: RuleId) : Rule(
    ruleId = ruleId,
    about = About(maintainer = "Viaduct", repositoryUrl = "", issueTrackerUrl = ""),
) {
    protected var isGradleScript = false
        private set

    override fun beforeVisitChildNodes(
        node: ASTNode,
        autoCorrect: Boolean,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit,
    ) {
        if (node.elementType == FILE) {
            isGradleScript = node.psi.containingFile.name.endsWith(".gradle.kts")
        }
    }
}
