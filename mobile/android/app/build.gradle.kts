import groovy.json.JsonSlurper
plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val repository = rootDir.resolve("../..")
val appVersion = (JsonSlurper().parse(repository.resolve("package.json")) as Map<*, *>)["version"].toString()
android {
    namespace = "com.voicestudio.mobile"
    compileSdk = 36
    ndkVersion = providers.environmentVariable("ANDROID_NDK_VERSION").orElse("27.1.12297006").get()
    defaultConfig {
        // A separate application id: installs beside the official VoiceStudio app instead of replacing it.
        applicationId = "io.github.fakesss.vozia"
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 36
        // Upstream version x.y.z becomes x*10^8+y*10^5+z*100 plus this fork's build number;
        // -PvoziaBuild=N (CI) raises it so every update installs over the previous one.
        val build = providers.gradleProperty("voziaBuild").orElse("1").get().toInt()
        versionCode = appVersion.split(".").take(3).map { it.toInt() }.let { it[0]*100_000_000+it[1]*100_000+it[2]*100 } + build
        versionName = "$appVersion-vozia.$build"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // A fixed, public debug key so debug builds from CI and local machines update each other.
        getByName("debug") {
            storeFile = rootDir.resolve("keystore/debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
        // The release key never lives in the repository: pass it via environment variables.
        val keystore = providers.environmentVariable("VOZIA_KEYSTORE").orNull
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("VOZIA_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("VOZIA_KEY_ALIAS").orElse("vozia").get()
            keyPassword = providers.environmentVariable("VOZIA_KEY_PASSWORD").orElse(providers.environmentVariable("VOZIA_KEYSTORE_PASSWORD")).get()
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        getByName("release") {
            // JNI looks sherpa-onnx config fields up by name, so the release build is not obfuscated.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    splits {
        abi {
            isEnable = true
            reset(); include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["main"].assets.srcDir(rootDir.resolve("../native/android/build/assets"))
    sourceSets["main"].jniLibs.srcDir(rootDir.resolve("../native/android/build/jniLibs"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/catalogs"))
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/catalogResources"))
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/brandResources"))
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
val bundleCatalogs by tasks.registering {
    val catalogs = repository.resolve("electron/src/renderer/src/i18n/locales")
    val contracts = repository.resolve("mobile/contract")
    inputs.dir(catalogs)
    inputs.file(repository.resolve("electron/public/signal-field.webp"))
    inputs.file(repository.resolve("electron/src/shared/languages.json"))
    inputs.file(repository.resolve("LICENSE"))
    inputs.files(fileTree(contracts) { include("strings.*.json", "tokens.json", "openapi.json", "models.json") })
    outputs.dir(layout.buildDirectory.dir("generated/catalogs"))
    outputs.dir(layout.buildDirectory.dir("generated/catalogResources"))
    doLast {
        val assets = layout.buildDirectory.dir("generated/catalogs").get().asFile
        assets.deleteRecursively()
        assets.mkdirs()
        repository.resolve("electron/public/signal-field.webp").copyTo(assets.resolve("signal-field.webp"), overwrite=true)
        repository.resolve("electron/src/shared/languages.json").copyTo(assets.resolve("languages.json"), overwrite=true)
        val out = assets.resolve("locales").apply { mkdirs() }
        assets.resolve("licenses").mkdirs()
        repository.resolve("LICENSE").copyTo(assets.resolve("licenses/VoiceStudio-AGPL-3.0.txt"), overwrite=true)
        catalogs.listFiles()!!.filter { it.extension == "json" }.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
        contracts.listFiles()!!.filter { it.name.startsWith("strings.") && it.name.endsWith(".json") }.forEach { it.copyTo(assets.resolve(it.name), overwrite = true) }
        contracts.resolve("models.json").copyTo(assets.resolve("models.json"), overwrite = true)
        contracts.resolve("tokens.json").copyTo(assets.resolve("tokens.json"), overwrite = true)
        val en = JsonSlurper().parse(catalogs.resolve("en.json")) as Map<*, *>
        val label = (en["app"] as Map<*, *>)["name"].toString() + " VozIA"
        val res = layout.buildDirectory.dir("generated/catalogResources/values").get().asFile.apply { mkdirs() }
        res.resolve("strings.xml").writeText("<resources><string name=\"app_name\">$label</string></resources>")
    }
}
tasks.named("preBuild") { dependsOn(bundleCatalogs) }
dependencies {
    implementation(files(rootDir.resolve("../native/android/build/downloads/sherpa.aar")).builtBy(":core:local:fetchRuntimes"))
    implementation(project(":features"))
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.viewmodel)
    implementation(libs.navigation)
    implementation(libs.coroutines)
    implementation(libs.serialization)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation(libs.compose.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.mockito)
    testImplementation(libs.media)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.work.testing)
    testImplementation(libs.datastore)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.work)
    debugImplementation(libs.compose.test.manifest)
}

tasks.withType<Test>().configureEach { systemProperty("androidRoot", rootDir.absolutePath) }

// Resolve the SDK through Gradle, then let Robolectric read it offline. Its
// default Maven resolver writes a lock in user.home, outside sandbox roots.
val robolectricSdk by configurations.creating
 dependencies { robolectricSdk(libs.robolectric.sdk) }
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}

val buildNativeRuntimes by tasks.registering(Exec::class) {
    dependsOn(":core:local:fetchRuntimes")
    val nativeRoot = rootDir.resolve("../native/android")
    inputs.files(fileTree(nativeRoot) { include("*.cpp", "*.h", "*.json", "*.py", "*.sh", "CMakeLists.txt") }, nativeRoot.resolve("../pins.json"))
    inputs.files(fileTree("src/main/assets/licenses") { include("native-*") })
    outputs.dir(nativeRoot.resolve("build/jniLibs"))
    outputs.dir(nativeRoot.resolve("build/assets"))
    commandLine("bash", nativeRoot.resolve("build.sh"), "--skip-fetch")
}
tasks.named("preBuild") { dependsOn(buildNativeRuntimes) }

val generateBrandResources by tasks.registering(Exec::class) {
    val generator = rootDir.resolve("scripts/generate_brand_resources.py")
    inputs.files(generator, repository.resolve("docs/logo.svg"), repository.resolve("mobile/contract/tokens.json"))
    outputs.dir(layout.buildDirectory.dir("generated/brandResources"))
    commandLine("python3", generator, "--output", layout.buildDirectory.dir("generated/brandResources").get().asFile)
}
tasks.named("preBuild") { dependsOn(generateBrandResources) }
