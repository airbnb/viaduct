@file:Suppress("MaxLineLength")

package viaduct.graphql.schema.graphqljava.extensions

import viaduct.graphql.schema.graphqljava.GJSchema

val GJSchema.Field.isPresentation: Boolean
    get() = this.sourceLocation?.sourceName?.contains("schema/presentation") ?: false

val GJSchema.TypeDef.isPresentation: Boolean
    get() = this.sourceLocation?.sourceName?.contains("schema/presentation") ?: false

val GJSchema.Field.isData: Boolean
    get() = this.sourceLocation?.sourceName?.contains("schema/data") ?: false

val GJSchema.TypeDef.isData: Boolean
    get() = this.sourceLocation?.sourceName?.contains("schema/data") ?: false

/** Returns the tenant that defines a field.  The result is typically
 *  a string that matches "<schema-tier>/<tenant-module>", where
 *  schema-tier is one of "data" or "presentation" and tenant-module
 *  is a name that does not include a "/" character.  However, some
 *  tenant modules contain other modules, so <tenant-module> portion
 *  could have (effectively) subdirectories in it.  Also, if source
 *  information was not available for a type or its source name did
 *  not meet our expected conventions, then the constant "NO_TENANT"
 *  is returned.
 */
val GJSchema.Field.tenant: String
    get() = extractTenant(
        this.def.definition
            ?.sourceLocation
            ?.sourceName
    )

/** Returns the tenant that defines a type.  The result is typically
 *  a string that matches "<schema-tier>/<tenant-module>", where
 *  schema-tier is one of "data" or "presentation" and tenant-module
 *  is a name that does not include a "/" character.  However, some
 *  tenant modules contain other modules, so <tenant-module> portion
 *  could have (effectively) subdirectories in it.  Also, if source
 *  information was not available for a type or its source name did
 *  not meet our expected conventions, then the constant "NO_TENANT"
 *  is returned.
 */
val GJSchema.TypeDef.tenant: String
    get() = extractTenant(
        this.def.definition
            ?.sourceLocation
            ?.sourceName
    )

/** Does the actual work of extracting a tenant name (e.g.,
 *  "data/user") from a source-file pathname.  While typically the
 *  prefix "data" or "presentation" is sufficient to indicate in the
 *  source path the start of a tenant name, we've designated some
 *  subdirectories of "data/" and "presentation/" to be their own
 *  tenants.  This function embeds that knowledge.
 */
private fun extractTenant(name: String?): String {
    if (name != null) {
        val m = tenantFinder.find(name)
        if (m != null) return m.groupValues[1]
    }
    return "NO_TENANT"
}

private val tenantFinder by lazy {
    // Google searches suggest that the first match in a list like this wins,
    // and my testing indicates that's the case, but I can't find it in
    // the spec, so if this stops working keep this in mind
    Regex(
        ".*/(data/user/product|data/communitysupport/case|data/communitysupport/credits|" +
            "data/communitysupport/taxonomy|data/communitysupport/userissue|data/price/model|" +
            "data/price/suggestion|data/stays/booking|data/stays/listing|data/stays/panda|" +
            "data/[^/]*|presentation/checkout/experiences|presentation/checkout/stays|presentation/pdp/core|" +
            "presentation/pdp/stays|presentation/prohost/properformance|presentation/stayinsights/hostreviews|" +
            "presentation/stayshostconsole/root|presentation/stayshostconsole/stayshostglobalbanner|" +
            "presentation/stayshostconsole/stayshostnavigation|presentation/[^/]*)/.*"
    )
}

/** Returns true iff field is in an inter-module(!) extension of the
 *  type.  Inter-module means across tenant modules, i.e., if the
 *  tenant of the field-definition is the tenant of the containing
 *  type.
 */
val GJSchema.Field.inExtension: Boolean
    get() = this.tenant != this.containingDef.tenant

/** Returns true iff field is defined in one module but has a type defined
 *  in another.
 */
val GJSchema.Field.hasExternalType: Boolean
    get() = this.tenant != this.type.baseTypeDef.tenant
