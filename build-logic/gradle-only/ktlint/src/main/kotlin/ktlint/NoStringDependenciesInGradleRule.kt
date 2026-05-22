package ktlint

import com.pinterest.ktlint.rule.engine.core.api.ElementType.CALL_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.REFERENCE_EXPRESSION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.STRING_TEMPLATE
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_ARGUMENT
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_ARGUMENT_LIST
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_ARGUMENT_NAME
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class NoStringDependenciesInGradleRule : GradleKtlintRule(RuleId("viaduct:no-string-dependencies-in-gradle")) {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        autoCorrect: Boolean,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit,
    ) {
        super.beforeVisitChildNodes(node, autoCorrect, emit)
        if (!isGradleScript || node.elementType != CALL_EXPRESSION) return
        checkCallExpression(node, emit)
    }

    private fun checkCallExpression(
        node: ASTNode,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit,
    ) {
        val callee = node.findChildByType(REFERENCE_EXPRESSION)?.text ?: return
        if (!isDependencyConfigCall(callee)) return
        if (!isInsideDependenciesBlock(node.psi)) return

        val argList = node.findChildByType(VALUE_ARGUMENT_LIST) ?: return
        val args = argList.getChildren(null).filter { it.elementType == VALUE_ARGUMENT }

        val firstArg = args.firstOrNull()
        if (firstArg != null && firstArg.findChildByType(VALUE_ARGUMENT_NAME) == null) {
            val rawStringNode = firstArg.findChildByType(STRING_TEMPLATE)
                ?: platformWrapperRawString(firstArg)
            rawStringNode
                ?.psi
                ?.let { it as? KtStringTemplateExpression }
                ?.takeIf { !it.hasInterpolation() && !isAllowedText(firstArg.psi?.text?.trim().orEmpty()) }
                ?.let { argExprNode ->
                    emit(
                        node.startOffset,
                        "Dependency coordinates must not be raw string literals. " +
                            "Use $callee(libs.some.alias) or project(\":module\") or platform(libs.some.bom). " +
                            "Found: ${argExprNode.node.text}",
                        false,
                    )
                    return
                }
        }

        val hasNamedStringParams = args.any { argNode ->
            val argPsi = argNode.psi ?: return@any false
            val name = argPsi.firstChild?.text
            val stringNode = argNode.findChildByType(STRING_TEMPLATE)
            val isString = stringNode?.let {
                (it.psi as? KtStringTemplateExpression)?.hasInterpolation() == false
            } ?: false
            name in FORBIDDEN_NAMED_PARAMS && isString
        }
        if (hasNamedStringParams) {
            emit(
                node.startOffset,
                "Use version catalog (libs.*) or project()/platform(libs.*). " +
                    "Do not use group/name/version string parameters.",
                false,
            )
        }
    }

    // platform("raw:string:coord") nests the STRING_TEMPLATE one level deeper inside the CALL_EXPRESSION wrapper
    private fun platformWrapperRawString(argNode: ASTNode): ASTNode? {
        val inner = argNode.findChildByType(CALL_EXPRESSION) ?: return null
        val callee = inner.findChildByType(REFERENCE_EXPRESSION)?.text ?: return null
        if (callee !in PLATFORM_WRAPPERS) return null
        val innerArgList = inner.findChildByType(VALUE_ARGUMENT_LIST) ?: return null
        val innerFirstArg = innerArgList.getChildren(null).firstOrNull { it.elementType == VALUE_ARGUMENT } ?: return null
        return innerFirstArg.findChildByType(STRING_TEMPLATE)
    }

    private fun isDependencyConfigCall(name: String): Boolean =
        name in GradleConstants.KNOWN_CONFIGURATIONS ||
            GradleConstants.CONFIGURATION_SUFFIXES.any { name.endsWith(it) }

    private fun isInsideDependenciesBlock(element: PsiElement?): Boolean {
        var current = element?.parent
        while (current != null) {
            if (isDependenciesCallExpression(current)) return true
            current = current.parent
        }
        return false
    }

    private fun isDependenciesCallExpression(el: PsiElement): Boolean = el is KtCallExpression && el.calleeExpression?.text == "dependencies"

    private fun isAllowedText(text: String): Boolean =
        text.matches(LIBS_ALIAS_REGEX) ||
            text.matches(PROJECT_CALL_REGEX) ||
            text.matches(PLATFORM_LIBS_REGEX) ||
            text.matches(TEST_FIXTURES_PROJECT_REGEX)

    private companion object {
        val PLATFORM_WRAPPERS = setOf("platform", "enforcedPlatform")
        val FORBIDDEN_NAMED_PARAMS = setOf("group", "name", "version")
        val LIBS_ALIAS_REGEX = Regex("""^libs(\.bundles)?\.[A-Za-z0-9_.]+$""")
        val PROJECT_CALL_REGEX = Regex("""^project\s*\(.*\)$""")
        val PLATFORM_LIBS_REGEX = Regex("""^(enforcedPlatform|platform)\s*\(\s*libs(\.bundles)?\.[A-Za-z0-9_.]+\s*\)$""")
        val TEST_FIXTURES_PROJECT_REGEX = Regex("""^testFixtures\s*\(\s*project\s*\(.*\)\s*\)$""")
    }
}
