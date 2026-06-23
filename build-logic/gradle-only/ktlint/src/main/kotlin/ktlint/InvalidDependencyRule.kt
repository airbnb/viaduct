package ktlint

import com.pinterest.ktlint.rule.engine.core.api.ElementType.CALL_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.REFERENCE_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_ARGUMENT
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_ARGUMENT_LIST
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression

class InvalidDependencyRule : GradleKtlintRule(RuleId("viaduct:invalid-dependency")) {
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
        INVALID_ALIASES.forEach { (alias, replacement) ->
            if (dependencyExpression.contains(alias)) {
                emit(
                    node.startOffset,
                    "$alias is an invalid dependency. Use $replacement instead. " +
                        "See impldocs/testing-guidance.md.",
                    false,
                )
            }
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

    internal companion object {
        val INVALID_ALIASES = mapOf(
            "libs.strikt.core" to "Kotest matchers (io.kotest.matchers.*)",
            "libs.kotlin.test" to "JUnit 5 (org.junit.jupiter.api.Assertions.*)",
            "libs.guava.testlib" to "viaduct.utils.collections.EqualsTesterHelper",
        )
    }
}
