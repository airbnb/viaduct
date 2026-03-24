package viaduct.engine.runtime

import graphql.language.Field

class DocumentRewriterHelper {
    companion object {
        val ALPHA_RENAME_SEPARATOR = "_zzz_" // used when prefixing fields during rewriting

        fun rewriteFieldName(
            prefix: String,
            field: Field
        ): String {
            return "${prefix}_${field.nameWithAlias()}"
        }

        private fun Field.nameWithAlias() =
            if (alias != null && alias != "") {
                "$name$ALPHA_RENAME_SEPARATOR$alias"
            } else {
                name
            }
    }
}
