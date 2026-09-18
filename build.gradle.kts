// Prunoid — Root build script | Gradle 9.5.1 + AGP 9.2.1 + compileSdk 37
// Root-permission SDK component auditor (IFW primary + pm disable secondary)

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
