plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // KSP is pinned to the Kotlin release in use; Room 2.8.x code generation runs through KSP.
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
