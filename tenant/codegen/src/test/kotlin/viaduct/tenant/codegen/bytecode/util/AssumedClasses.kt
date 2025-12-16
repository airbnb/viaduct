package viaduct.tenant.codegen.bytecode.util

import viaduct.codegen.utils.KmName

val assumedClassesForCodegen = setOf(
    KmName("com/airbnb/viaduct/schema/base/ValueBase"),
    KmName("com/airbnb/viaduct/schema/base/BuilderBase"),
    KmName("com/airbnb/viaduct/schema/base/ViaductGeneratedObject"),
    KmName("com/airbnb/viaduct/schema/base/ViaductInputType"),
    KmName("com/airbnb/viaduct/schema/base/ViaductOutputType"),
    KmName("com/airbnb/viaduct/schema/root/IMutation"),
    KmName("com/airbnb/viaduct/schema/root/IQuery"),
    KmName("com/airbnb/viaduct/types/IHasId"),
    KmName("com/airbnb/viaduct/types/pointers/NodeTypeWithoutData"),
)
