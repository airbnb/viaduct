package viaduct.types

open class Text

class I18nText : Text() {
    companion object {
        val V1 = I18nText()
        val V2 = I18nText()
    }
}
