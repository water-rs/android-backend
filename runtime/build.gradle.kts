plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "dev.waterui.android"
version = "0.1.0-SNAPSHOT"

// JavaCPP version
val javacppVersion = "1.5.10"

// JavaCPP Parser configuration
val javacppOutputDir = layout.buildDirectory.dir("generated/javacpp/java")
val javacppConfigClass = "dev.waterui.android.ffi.WaterUIConfig"

// Configuration for JavaCPP Parser classpath
val javacppParser: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

android {
    namespace = "dev.waterui.android.runtime"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")


    }

    buildFeatures {
        compose = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
            // Include JavaCPP-generated Java files
            java.srcDir(javacppOutputDir)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        targetSdk = 36
        abortOnError = true
        warningsAsErrors = true
        textReport = true
        xmlReport = true
        htmlReport = true
        sarifReport = true
    }
}

kotlin {
    jvmToolchain(21)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    basePath = rootDir.absolutePath
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        xml.required.set(true)
        html.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JavaCPP for native bindings
    implementation("org.bytedeco:javacpp:$javacppVersion")

    // JavaCPP Parser for build-time code generation
    javacppParser("org.bytedeco:javacpp:$javacppVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// Directory for compiled config class
val javacppClassesDir = layout.buildDirectory.dir("javacpp/classes")

// Task to compile WaterUIConfig.java
tasks.register<JavaCompile>("compileJavaCPPConfig") {
    group = "build"
    description = "Compiles the JavaCPP configuration class"

    source = fileTree("src/main/java/dev/waterui/android/ffi") {
        include("WaterUIConfig.java")
    }
    classpath = javacppParser
    destinationDirectory.set(javacppClassesDir)

    sourceCompatibility = "21"
    targetCompatibility = "21"
}

// Task to run JavaCPP Builder/Parser and generate WaterUILib.java
tasks.register<JavaExec>("generateJavaCPP") {
    group = "build"
    description = "Generates Java bindings from waterui.h using JavaCPP"
    dependsOn("compileJavaCPPConfig")

    // Input: WaterUIConfig.java and waterui.h
    inputs.file("src/main/java/dev/waterui/android/ffi/WaterUIConfig.java")
    inputs.file("src/main/cpp/waterui.h")

    // Output: generated Java files
    outputs.dir(javacppOutputDir)

    // Use JavaCPP Builder which handles parsing
    mainClass.set("org.bytedeco.javacpp.tools.Builder")
    classpath = javacppParser + files(javacppClassesDir)

    // Builder arguments: parse the config class and output Java files
    args = listOf(
        "-classpath", (javacppParser + files(javacppClassesDir)).asPath,
        "-d", javacppOutputDir.get().asFile.absolutePath,
        "-nocompile",  // Don't compile to native, just generate Java
        "-nodelete",   // Keep generated files
        javacppConfigClass
    )

    doFirst {
        javacppOutputDir.get().asFile.mkdirs()
    }
}

// Make sure JavaCPP generation runs before Java/Kotlin compilation
tasks.matching { it.name.startsWith("compile") && it.name.contains("Java") && !it.name.contains("JavaCPP") }.configureEach {
    dependsOn("generateJavaCPP")
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn("generateJavaCPP")
}
