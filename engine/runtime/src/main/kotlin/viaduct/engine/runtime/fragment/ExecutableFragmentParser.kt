package viaduct.engine.runtime.fragment

import graphql.language.Document
import viaduct.engine.api.fragment.Fragment

interface ExecutableFragmentParser {
    fun parse(fragment: Fragment): Document
}
