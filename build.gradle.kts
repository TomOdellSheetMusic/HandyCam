buildscript {
    dependencies {
        // Keep AGP's related Bouncy Castle modules aligned on a current release.
        classpath("org.bouncycastle:bcprov-jdk18on:1.86")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
        classpath("org.bouncycastle:bcutil-jdk18on:1.86")
        classpath("org.bouncycastle:bcpg-jdk18on:1.86")

        // Security updates for transitive build-tool dependencies reported by Dependabot.
        classpath(platform("io.netty:netty-bom:4.1.137.Final"))
        constraints {
            classpath("com.google.guava:guava:33.7.2-jre")
            classpath("com.google.protobuf:protobuf-java:3.25.5")
            classpath("com.google.protobuf:protobuf-kotlin:3.25.5")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.commons:commons-compress:1.26.0")
            classpath("org.apache.commons:commons-lang3:3.18.0")
            classpath("io.opentelemetry:opentelemetry-api:1.67.0")
            classpath("io.opentelemetry:opentelemetry-context:1.67.0")
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
