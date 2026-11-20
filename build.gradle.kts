plugins {
    id("com.android.application") version "9.5.2" apply false
    // AGP 9 has Kotlin built in; the compose compiler plugin must match the Kotlin version
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
