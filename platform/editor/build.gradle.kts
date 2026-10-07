plugins {
    id("autoscript.android-library")
}

android {
    namespace = "com.autoscript.platform.editor"
}

dependencies {
    implementation(project(":domain"))
}

