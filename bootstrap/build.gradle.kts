plugins {
    id("com.android.library")
}

android {
    namespace = "dev.kaos.kaosgfx.bootstrap"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

tasks.register("generateBootstrapDex") {
    dependsOn("compileReleaseJavaWithJavac")

    val buildToolsDir = "/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0"
    val d8Path = "$buildToolsDir/d8"
    val androidJar = "/opt/homebrew/share/android-commandlinetools/platforms/android-36/android.jar"
    val classesDir = layout.buildDirectory.dir("intermediates/javac/release/compileReleaseJavaWithJavac/classes")
    val outputDir = layout.buildDirectory.dir("outputs/dex")
    val patcherResourcesDir = file("${project.rootDir}/patcher/src/main/resources")

    doLast {
        val outDex = outputDir.get().asFile
        outDex.mkdirs()

        val classFiles = fileTree(classesDir.get().asFile).matching {
            include("**/*.class")
        }.files.map { it.absolutePath }

        if (classFiles.isEmpty()) {
            throw GradleException("No class files found in ${classesDir.get().asFile}")
        }

        val args = mutableListOf(
            d8Path,
            "--release",
            "--min-api", "28",
            "--lib", androidJar,
            "--output", outDex.absolutePath
        )
        args.addAll(classFiles)

        val process = ProcessBuilder(args).inheritIO().start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("D8 failed with exit code $exitCode")
        }

        // Copy generated classes.dex to patcher resources as bootstrap.dex
        val generatedDex = File(outDex, "classes.dex")
        if (generatedDex.exists()) {
            patcherResourcesDir.mkdirs()
            val targetDex = File(patcherResourcesDir, "bootstrap.dex")
            generatedDex.copyTo(targetDex, overwrite = true)
            logger.lifecycle("Bootstrap DEX copied to ${targetDex.absolutePath} (${targetDex.length()} bytes)")
        }
    }
}
