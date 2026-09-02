plugins {
    id("com.android.library")
    id("dev.detekt") version "2.0.0-alpha.6"
}

android {
    namespace = "dev.waterui.android.runtime"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        targetSdk = 37
        abortOnError = true
        warningsAsErrors = true
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

detekt {
    toolVersion = "2.0.0-alpha.6"
    buildUponDefaultConfig = true
    ignoreFailures = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    basePath = rootProject.layout.projectDirectory
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        checkstyle.required.set(true)
        html.required.set(true)
        sarif.required.set(true)
        markdown.required.set(false)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.fragment:fragment:1.9.0")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.window:window-core-android:1.5.1")
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.11.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("me.zhanghai.android.fastscroll:library:1.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
