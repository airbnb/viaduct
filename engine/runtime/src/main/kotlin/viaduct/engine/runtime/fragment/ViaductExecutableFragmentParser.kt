package viaduct.engine.runtime.fragment

import graphql.language.Document
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.parse.CachedDocumentParser

class ViaductExecutableFragmentParser : ExecutableFragmentParser {
    override fun parse(fragment: Fragment): Document = CachedDocumentParser.parseDocument(fragment.document)
}
