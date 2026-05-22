package ktlint

import com.pinterest.ktlint.rule.engine.core.api.ElementType.CALL_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.REFERENCE_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

class NoPrintlnInGradleRule : GradleKtlintRule(RuleId("viaduct:no-println-in-gradle")) {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        autoCorrect: Boolean,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit,
    ) {
        super.beforeVisitChildNodes(node, autoCorrect, emit)
        if (!isGradleScript || node.elementType != CALL_EXPRESSION) return
        val callee = node.findChildByType(REFERENCE_EXPRESSION)
        if (callee?.text == "println") {
            emit(node.startOffset, "Use logger.lifecycle/info/debug instead of println in Gradle scripts.", false)
        }
    }
}
