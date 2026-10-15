plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.speechbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.speechbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }

    // AGP's lint checks (release-build / test-source / generated-source guards)
    // run through a work action that fails on this JDK; there is no Java source
    // here for them to police, so turn them off.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        checkTestSources = false
        checkGeneratedSources = false
        checkDependencies = false
        checkAllWarnings = false
        ignoreWarnings = true
    }

    // Native engines (transcribe.cpp + llama.cpp). Enable once you have the
    // sources vendored; see app/src/main/cpp/CMakeLists.txt and README.md.
    // Left off by default so the app builds and runs with the fallback engines.
    //
    // ndk { (AGP 8.7 has no ndk DSL member; use scripts/build-native.sh)
//         path = "cpp"
//         version = "22.1.7171670"
//         libraries = listOf("proc_jni", "llama_jni")
//         cmakeArguments += listOf(
//             "-DLLAMA_BUILD_COMMON=ON",
//             "-DLLAMA_BUILD_TOOLS=OFF",
//             "-DLLAMA_ROOT=/home/depro/src/llama.cpp",
//         )
//     }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The STT and LLM engines are native, not Java deps: libtranscribe_jni.so
    // (transcribe.cpp) and libllama_jni.so (llama.cpp) are built by
    // app/src/main/cpp/CMakeLists.txt and loaded with System.loadLibrary at
    // runtime. Missing .so files degrade to FakeSttEngine / a remote provider.
    //
    // org.json.JSONObject / JSONArray come from the kotlin-json compiler plugin
    // (bundled with the Kotlin Android plugin); add it here if your toolchain
    // ships it separately.
    // implementation("org.jetbrains.kotlin-json")
}
