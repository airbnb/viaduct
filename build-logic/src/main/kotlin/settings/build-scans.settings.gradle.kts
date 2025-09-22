package settings

plugins {
    id("com.gradle.develocity")
}

develocity {
    val usePublicServer = providers.environmentVariable("BUILD_SCAN_PUBLIC_SERVER").orElse("false").get().toBoolean()
    if (!usePublicServer) {
        server = "https://gradle-enterprise.musta.ch"
    }
    buildScan {
        val alwaysPublish = providers.environmentVariable("BUILD_SCAN_ALWAYS_PUBLISH").orElse("false").get().toBoolean()
        if (!alwaysPublish) {
            publishing.onlyIf {
                it.buildResult.failures.isNotEmpty()
            }
        }
        val autoAcceptTerms = providers.environmentVariable("BUILD_SCAN_AUTO_ACCEPT_TERMS").orElse("false").get().toBoolean()
        if (autoAcceptTerms) {
            termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
            termsOfUseAgree.set("yes")
        }
    }
}