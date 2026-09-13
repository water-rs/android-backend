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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Asks Maven Central, at lint time, whether anything newer exists. It
        // therefore turns red the moment any dependency publishes a release,
        // with nothing in this repository having changed — a gate a pull
        // request cannot pass or fail on its own merits. Dependency freshness
        // belongs to whoever bumps versions, not to the per-change gate.
        disable += "NewerVersionAvailable"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources of this library and its
            // dependencies to inflate real Material views in a JVM test.
            isIncludeAndroidResources = true
        }
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

// The variant-specific detekt tasks analyse with type resolution, which is what
// finds the rules the source-only `detekt` task cannot see. Their Kotlin front
// end does not compile Java, so the module's own compiled Java classes have to
// reach them as a classpath entry — `WaterUiWebViewClient` is deliberately Java
// (see its doc comment), and without this every Kotlin file that touches it
// analyses with an unresolved reference, which detekt reports as a compiler
// error and which silently degrades the accuracy of every rule in those files.
androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar(Char::uppercase)
    tasks.matching { it.name == "detekt$variantName" }.configureEach {
        (this as dev.detekt.gradle.Detekt)
            .classpath
            .from(
                // Everything the Kotlin compiler itself resolved against, plus
                // the module's own compiled Java, which it did not need because
                // it was handed the Java sources directly.
                tasks.named("compile${variantName}Kotlin")
                    .map { it.property("libraries") as FileCollection },
                tasks.named<JavaCompile>("compile${variantName}JavaWithJavac")
                    .flatMap(JavaCompile::getDestinationDirectory)
            )
    }
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
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
