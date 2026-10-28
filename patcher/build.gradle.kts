plugins {
    id("com.android.library")
}

android {
    namespace = "dev.kaos.kaosgfx.patcher"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("io.github.reandroid:ARSCLib:1.3.5")
    implementation("com.android.tools.build:apksig:9.0.1")

    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") {
    dependsOn(":bootstrap:generateBootstrapDex")
    dependsOn(":bridge:packageNativeLibsToPatcherResources")
}
