package expectedspkg.legacy

import actualspkg.Testing

class LegacyPrivateSetterVar {
    var a: String = TODO()
        private set
}

class LegacyAnnotatedGetterVal {
    @get:Testing
    val a: String = TODO()
}
