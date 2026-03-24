package viaduct.tenant.codegen.bytecode

import viaduct.codegen.utils.KmName

// Utilities expressed as extension functions go here.  Constants and
// other utility functions go in Config.kt

// *** Name-related utilities *** //

/** Translate the name of a non-scalar GraphQL type into
 *  the kotlinx-metadata name for the type that will
 *  represent it. */
fun String.kmFQN(pkg: KmName): KmName = KmName("$pkg/$this")
