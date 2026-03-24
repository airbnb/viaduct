package viaduct.codegen.km

import viaduct.codegen.ct.KmClassTree

/** The external API to this package returns these ClassBuilders to
 *  clients.  Subclasses allow those clients to populate a ClassBuilder
 *  but not to actually build one themselves - that's done internally. */
abstract class ClassBuilder {
    internal abstract fun build(): KmClassTree
}
