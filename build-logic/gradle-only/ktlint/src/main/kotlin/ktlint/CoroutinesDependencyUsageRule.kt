package ktlint

import com.pinterest.ktlint.rule.engine.core.api.ElementType.CALL_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.REFERENCE_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_ARGUMENT
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_ARGUMENT_LIST
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression

class CoroutinesDependencyUsageRule : GradleKtlintRule(RuleId("viaduct:coroutines-dependency-usage")) {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        autoCorrect: Boolean,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit,
    ) {
        super.beforeVisitChildNodes(node, autoCorrect, emit)
        if (!isGradleScript || node.elementType != CALL_EXPRESSION) return

        val callee = node.findChildByType(REFERENCE_EXPRESSION)?.text ?: return
        if (!isDependencyConfigCall(callee)) return
        if (!isInsideDependenciesBlock(node.psi)) return

        val dependencyExpression = firstDependencyArgumentText(node) ?: return
        if (
            dependencyExpression.contains("libs.kotlinx.coroutines.test") &&
            callee !in TEST_SCOPED_CONFIGURATIONS
        ) {
            emit(
                node.startOffset,
                "Use libs.kotlinx.coroutines.test only in test-scoped configurations.",
                false,
            )
        }
    }

    private fun firstDependencyArgumentText(node: ASTNode): String? {
        val argList = node.findChildByType(VALUE_ARGUMENT_LIST) ?: return null
        return argList
            .getChildren(null)
            .firstOrNull { it.elementType == VALUE_ARGUMENT }
            ?.text
            ?.trim()
    }

    private fun isDependencyConfigCall(name: String): Boolean =
        name in GradleConstants.KNOWN_CONFIGURATIONS ||
            GradleConstants.CONFIGURATION_SUFFIXES.any { name.endsWith(it) }

    private fun isInsideDependenciesBlock(element: PsiElement?): Boolean {
        var current = element?.parent
        while (current != null) {
            if (current is KtCallExpression && current.calleeExpression?.text == "dependencies") return true
            current = current.parent
        }
        return false
    }

    private companion object {
        val TEST_SCOPED_CONFIGURATIONS = setOf(
            "testImplementation",
            "testFixturesImplementation",
            "testFixturesApi",
        )
    }
}
