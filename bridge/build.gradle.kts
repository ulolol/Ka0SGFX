plugins {
    id("com.android.library")
}

android {
    namespace = "dev.kaos.kaosgfx.bridge"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static")
                cppFlags("-std=c++20")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        prefab = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("com.bytedance:bytehook:1.1.1")
    implementation("com.bytedance.android:shadowhook:1.1.1")
}

tasks.register("packageNativeLibsToPatcherResources") {
    dependsOn("mergeReleaseNativeLibs", "stripReleaseDebugSymbols")

    val strippedLibsDir = layout.buildDirectory.dir("intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a")
    val patcherArm64Dir = file("${project.rootDir}/patcher/src/main/resources/arm64-v8a")

    doLast {
        val srcDir = strippedLibsDir.get().asFile
        if (!srcDir.exists()) {
            throw GradleException("Native libs directory not found: ${srcDir.absolutePath}")
        }
        patcherArm64Dir.mkdirs()

        val libs = listOf(
            "libkaosgfx_bridge.so",
            "libbytehook.so",
            "libshadowhook.so",
            "libhook_impl.so",
            "libfile_redirect_hook.so",
            "libmain_hook.so",
            "libgsl_alloc_hook.so"
        )

        for (lib in libs) {
            val file = File(srcDir, lib)
            if (file.exists()) {
                val dest = File(patcherArm64Dir, "$lib.bin")
                file.copyTo(dest, overwrite = true)
                logger.lifecycle("Copied $lib (${dest.length()} bytes) to ${dest.absolutePath}")
            } else {
                logger.warn("Warning: expected $lib not found in $srcDir")
            }
        }
    }
}
