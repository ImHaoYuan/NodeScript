plugins {
    id("com.android.library")
}

android {
    namespace = "com.autoscript.bridge.treesitter"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}
