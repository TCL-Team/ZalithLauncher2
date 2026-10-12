import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.tasks.MergeSourceSetFolders
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
    id("kotlin-parcelize")
    id("com.movtery.buildkeys")
}

val zalithPackageName = "com.movtery.zalithlauncher"
val launcherAPPName = project.findProperty("launcher_app_name") as? String ?: error("The \"launcher_app_name\" property is not set in gradle.properties.")
val launcherName = project.findProperty("launcher_name") as? String ?: error("The \"launcher_name\" property is not set in gradle.properties.")
val launcherShortName = project.findProperty("launcher_short_name") as? String ?: error("The \"launcher_short_name\" property is not set in gradle.properties.")
val launcherUrl = project.findProperty("url_home") as? String ?: error("The \"url_home\" property is not set in gradle.properties.")

val launcherVersionCode = (project.findProperty("launcher_version_code") as? String)?.toIntOrNull() ?: error("The \"launcher_version_code\" property is not set as an integer in gradle.properties.")
val launcherVersionName = project.findProperty("launcher_version_name") as? String ?: error("The \"launcher_version_name\" property is not set in gradle.properties.")

val defaultOAuthClientID = project.findProperty("oauth_client_id") as? String
val defaultStorePassword = project.findProperty("default_store_password") as? String ?: error("The \"default_store_password\" property is not set in gradle.properties.")
val defaultKeyPassword = project.findProperty("default_key_password") as? String ?: error("The \"default_key_password\" property is not set in gradle.properties.")
val defaultCurseForgeApiKey = project.findProperty("curseforge_api_key") as? String

val projectArch: String = System.getProperty("arch", "all")

fun getKeyFromLocal(envKey: String, fileName: String? = null, default: String? = null): String {
    val key = System.getenv(envKey)
    return key ?: fileName?.let {
        val file = File(rootDir, fileName)
        if (file.canRead() && file.isFile) file.readText() else null
    } ?: default ?: run {
        logger.warn("BUILD: $envKey not set; related features may throw exceptions.")
        ""
    }
}

android {
    namespace = zalithPackageName
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    signingConfigs {
        create("releaseBuild") {
            storeFile = file("zalith_launcher.jks")
            storePassword = getKeyFromLocal("STORE_PASSWORD", ".store_password.txt")
            keyAlias = "movtery_zalith"
            keyPassword = getKeyFromLocal("KEY_PASSWORD", ".key_password.txt")
        }
        create("debugBuild") {
            storeFile = file("zalith_launcher_debug.jks")
            storePassword = defaultStorePassword
            keyAlias = "movtery_zalith_debug"
            keyPassword = defaultKeyPassword
        }
    }

    defaultConfig {
        applicationId = zalithPackageName
        applicationIdSuffix = ".v2"
        minSdk = 26
        targetSdk = 34
        versionCode = launcherVersionCode
        versionName = launcherVersionName
        manifestPlaceholders["launcher_name"] = launcherAPPName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("releaseBuild")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debugBuild")
        }
    }

    splits {
        val arch = projectArch.takeIf { it != "all" } ?: return@splits
        abi {
            isEnable = true
            reset()
            when (arch) {
                "arm" -> include("armeabi-v7a")
                "arm64" -> include("arm64-v8a")
                "x86" -> include("x86")
                "x86_64" -> include("x86_64")
            }
        }
    }

    ndkVersion = "25.2.9519653"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    compileOptions {
        // sora-editor language-textmate
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            //让 android.util.Log 等框架方法在本地单测中返回默认值而非抛出异常
            isReturnDefaultValues = true
        }
    }
}

// The official MobileGlues APK is the upstream distribution channel for its native renderer.
// Resolve the latest stable GitHub release during every Android build and stage its renderer .so
// as a generated JNI library. Its shaderconv and SPIRV-Cross dependencies are already packaged
// by this project, avoiding duplicate native-library entries; downloaded files stay in ignored caches.
val mobileGluesBuildOutput = layout.buildDirectory.dir("generated/mobileglues")
val mobileGluesJniLibs = mobileGluesBuildOutput.map { it.dir("jniLibs") }
// AGP 9's SourceSet API accepts path strings, not Gradle Providers; preBuild below explicitly
// depends on the task that populates this generated directory.
android.sourceSets.getByName("main").jniLibs.directories.add(mobileGluesJniLibs.get().asFile.absolutePath)

val downloadMobileGlues by tasks.registering {
    group = "build setup"
    description = "Fetches the latest stable MobileGlues renderer binaries from GitHub."
    outputs.dir(mobileGluesJniLibs)
    // Query GitHub on each build so a newly published upstream renderer is not hidden by Gradle's cache.
    outputs.upToDateWhen { false }

    doLast {
        val cacheDir = File(rootProject.projectDir, ".gradle/mobileglues")
        val cachedReleaseJson = File(cacheDir, "latest-release.json")
        val cachedEtag = File(cacheDir, "latest-release.etag")
        val cachedApk = File(cacheDir, "MobileGlues-latest.apk")
        val cachedIdentity = File(cacheDir, "installed-release.txt")
        val generatedRoot = mobileGluesBuildOutput.get().asFile
        val outputDir = mobileGluesJniLibs.get().asFile
        val outputIdentity = File(generatedRoot, "installed-release.txt")
        val outputAbisFile = File(generatedRoot, "mobileglues-abis.txt")
        val releaseApi = "https://api.github.com/repos/MobileGL-Dev/MobileGlues-release/releases/latest"

        check(cacheDir.exists() || cacheDir.mkdirs()) {
            "Could not create the MobileGlues Gradle cache at ${cacheDir.absolutePath}"
        }

        var releaseJson: String? = null
        if (!gradle.startParameter.isOffline) {
            var connection: HttpURLConnection? = null
            try {
                val httpConnection = URL(releaseApi).openConnection() as HttpURLConnection
                connection = httpConnection
                httpConnection.instanceFollowRedirects = true
                httpConnection.connectTimeout = 20_000
                httpConnection.readTimeout = 30_000
                httpConnection.setRequestProperty("Accept", "application/vnd.github+json")
                httpConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                httpConnection.setRequestProperty("User-Agent", "ZalithLauncher-build")
                System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { token ->
                    httpConnection.setRequestProperty("Authorization", "Bearer $token")
                }
                cachedEtag.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { etag ->
                    httpConnection.setRequestProperty("If-None-Match", etag)
                }

                when (httpConnection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        releaseJson = httpConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        cachedReleaseJson.writeText(releaseJson!!)
                        httpConnection.getHeaderField("ETag")?.let { etag -> cachedEtag.writeText(etag) }
                    }
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        releaseJson = cachedReleaseJson.takeIf { it.isFile }?.readText()
                            ?: throw GradleException("GitHub returned 304 but no cached MobileGlues release metadata exists.")
                    }
                    else -> throw GradleException("GitHub returned HTTP ${httpConnection.responseCode} while checking MobileGlues releases.")
                }
            } catch (error: Exception) {
                logger.warn("[MobileGlues] Could not check GitHub for a newer release: ${error.message}")
            } finally {
                connection?.disconnect()
            }
        }

        if (releaseJson == null && cachedReleaseJson.isFile) {
            logger.lifecycle("[MobileGlues] Using the last checked release metadata from the local Gradle cache.")
            releaseJson = cachedReleaseJson.readText()
        }
        val releasePayload = releaseJson
            ?: throw GradleException("Could not retrieve MobileGlues release information and no cached release is available.")
        val release = JsonSlurper().parseText(releasePayload) as? Map<*, *>
            ?: throw GradleException("The MobileGlues GitHub release response was not a JSON object.")
        if (release["draft"] == true || release["prerelease"] == true) {
            throw GradleException("GitHub did not return a stable MobileGlues release.")
        }

        val releaseTag = release["tag_name"] as? String
            ?: throw GradleException("The latest MobileGlues release has no tag name.")
        val assets = release["assets"] as? List<*>
            ?: throw GradleException("The latest MobileGlues release has no assets list.")
        val apkAsset = assets.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { (it["name"] as? String)?.endsWith(".apk", ignoreCase = true) == true }
            ?: throw GradleException("The latest MobileGlues release does not contain an APK asset.")
        val assetName = apkAsset["name"] as? String
            ?: throw GradleException("The MobileGlues APK asset has no file name.")
        val downloadUrl = apkAsset["browser_download_url"] as? String
            ?: throw GradleException("The MobileGlues APK asset has no download URL.")
        if (!downloadUrl.startsWith("https://github.com/MobileGL-Dev/MobileGlues-release/releases/download/")) {
            throw GradleException("Refusing an unexpected MobileGlues download URL: $downloadUrl")
        }
        val expectedSize = (apkAsset["size"] as? Number)?.toLong()?.takeIf { it > 0L }
        val rawDigest = apkAsset["digest"] as? String
        val expectedSha256 = rawDigest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(":")
        val releaseIdentity = "$releaseTag|$assetName|${rawDigest.orEmpty()}|${expectedSize ?: ""}"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun isValidCachedApk(file: File): Boolean {
            if (!file.isFile) return false
            if (expectedSize != null && file.length() != expectedSize) return false
            if (expectedSha256 != null && sha256(file) != expectedSha256) return false
            return cachedIdentity.takeIf { it.isFile }?.readText() == releaseIdentity
        }

        if (!isValidCachedApk(cachedApk)) {
            logger.lifecycle("[MobileGlues] Downloading $releaseTag ($assetName) from the official release repository.")
            val partialApk = File(cacheDir, "MobileGlues-latest.apk.part")
            partialApk.delete()
            var connection: HttpURLConnection? = null
            try {
                val httpConnection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection = httpConnection
                httpConnection.instanceFollowRedirects = true
                httpConnection.connectTimeout = 20_000
                httpConnection.readTimeout = 120_000
                httpConnection.setRequestProperty("Accept", "application/octet-stream")
                httpConnection.setRequestProperty("User-Agent", "ZalithLauncher-build")
                if (httpConnection.responseCode !in 200..299) {
                    throw GradleException("GitHub returned HTTP ${httpConnection.responseCode} while downloading MobileGlues.")
                }
                httpConnection.inputStream.buffered().use { input ->
                    partialApk.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            } finally {
                connection?.disconnect()
            }

            if (expectedSize != null && partialApk.length() != expectedSize) {
                partialApk.delete()
                throw GradleException("Downloaded MobileGlues APK size did not match GitHub metadata.")
            }
            if (expectedSha256 != null && sha256(partialApk) != expectedSha256) {
                partialApk.delete()
                throw GradleException("Downloaded MobileGlues APK failed its GitHub SHA-256 check.")
            }
            if (cachedApk.exists() && !cachedApk.delete()) {
                partialApk.delete()
                throw GradleException("Could not replace the cached MobileGlues APK.")
            }
            if (!partialApk.renameTo(cachedApk)) {
                partialApk.copyTo(cachedApk, overwrite = true)
                partialApk.delete()
            }
            cachedIdentity.writeText(releaseIdentity)
        } else {
            logger.lifecycle("[MobileGlues] $releaseTag is already cached; checking generated JNI libraries.")
        }

        val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        fun extractRendererLibraries(apk: File, destination: File): Set<String> {
            destination.deleteRecursively()
            check(destination.exists() || destination.mkdirs()) {
                "Could not create the temporary MobileGlues JNI directory."
            }
            val extractedAbis = mutableSetOf<String>()
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val parts = entry.name.split('/')
                    if (parts.size != 3 || parts[0] != "lib" || parts[1] !in supportedAbis || parts[2] != "libmobileglues.so") continue

                    val libraryFile = File(destination, "${parts[1]}/libmobileglues.so")
                    check(libraryFile.parentFile?.mkdirs() == true || libraryFile.parentFile?.isDirectory == true) {
                        "Could not create the ${parts[1]} MobileGlues library directory."
                    }
                    zip.getInputStream(entry).buffered().use { input ->
                        libraryFile.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    check(libraryFile.length() > 0L) { "The MobileGlues library for ${parts[1]} is empty." }
                    extractedAbis += parts[1]
                }
            }
            if (extractedAbis.isEmpty()) {
                throw GradleException("The $releaseTag APK contains no supported libmobileglues.so binaries.")
            }
            return extractedAbis
        }

        fun verifyBundledDependencies(abis: Set<String>) {
            val spirvCrossAar = File(projectDir, "libs/spirv-cross-natives.aar")
            if (!spirvCrossAar.isFile) {
                throw GradleException("MobileGlues requires the bundled spirv-cross-natives.aar dependency.")
            }
            ZipFile(spirvCrossAar).use { aar ->
                for (abi in abis) {
                    val shaderconv = File(projectDir, "src/main/jniLibs/$abi/libshaderconv.so")
                    if (!shaderconv.isFile || shaderconv.length() == 0L) {
                        throw GradleException("MobileGlues requires libshaderconv.so for $abi, but it is not bundled.")
                    }
                    if (aar.getEntry("jni/$abi/libspirv-cross-c-shared.so") == null) {
                        throw GradleException("MobileGlues requires libspirv-cross-c-shared.so for $abi, but it is not bundled.")
                    }
                }
            }
        }

        val requestedAbi = when (projectArch) {
            "arm" -> "armeabi-v7a"
            "arm64" -> "arm64-v8a"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> null
        }
        val cachedAbis = outputAbisFile.takeIf { it.isFile }
            ?.readLines()
            ?.map(String::trim)
            ?.filter { it in supportedAbis }
            ?.toSet()
            .orEmpty()
        val cachedOutputIsComplete = cachedAbis.isNotEmpty() && cachedAbis.all { abi ->
            val library = File(outputDir, "$abi/libmobileglues.so")
            library.isFile && library.length() > 0L
        }
        if (outputIdentity.isFile && outputIdentity.readText() == releaseIdentity && cachedOutputIsComplete) {
            verifyBundledDependencies(cachedAbis)
            if (requestedAbi != null && requestedAbi !in cachedAbis) {
                logger.warn("[MobileGlues] Upstream $releaseTag has no $requestedAbi binary; this ABI build will not include MobileGlues.")
            }
            logger.lifecycle("[MobileGlues] JNI libraries for $releaseTag are already staged.")
            return@doLast
        }

        val stagedDir = File(generatedRoot, "jniLibs.pending")
        val extractedAbis = extractRendererLibraries(cachedApk, stagedDir)
        verifyBundledDependencies(extractedAbis)
        if (requestedAbi != null && requestedAbi !in extractedAbis) {
            logger.warn("[MobileGlues] Upstream $releaseTag has no $requestedAbi binary; this ABI build will not include MobileGlues.")
        }

        if (outputDir.exists() && !outputDir.deleteRecursively()) {
            stagedDir.deleteRecursively()
            throw GradleException("Could not replace the generated MobileGlues JNI directory.")
        }
        if (!stagedDir.renameTo(outputDir)) {
            stagedDir.copyRecursively(outputDir, overwrite = true)
            stagedDir.deleteRecursively()
        }
        outputAbisFile.writeText(extractedAbis.sorted().joinToString("\n", postfix = "\n"))
        outputIdentity.writeText(releaseIdentity)
        logger.lifecycle("[MobileGlues] Staged $releaseTag native library for ABI(s): ${extractedAbis.sorted().joinToString()}")
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadMobileGlues)
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                afterEvaluate {
                    val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
                    task.inputs.property("lwjglArch", projectArch)
                    task.doLast {
                        val assetsDir = task.outputDir.get().asFile
                        val tag = "JREAssetsCleanup"
                        logger.lifecycle("[$tag] arch: $projectArch")
                        val jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")
                        jreList.forEach { jreVersion ->
                            val runtimeDir = File("$assetsDir/runtimes/$jreVersion")
                            logger.lifecycle("[$tag] runtimeDir: ${runtimeDir.absolutePath}")
                            runtimeDir.listFiles()?.forEach {
                                if (projectArch != "all" && it.name != "version" && !it.name.contains("universal") && it.name != "bin-$projectArch.tar.xz") {
                                    logger.lifecycle("[$tag] delete: $it : ${it.delete()}")
                                }
                            }
                        }

                        if (projectArch == "all") return@doLast
                        val abi = when (projectArch) {
                            "arm" -> "armeabi-v7a"
                            "arm64" -> "arm64-v8a"
                            "x86" -> "x86"
                            "x86_64" -> "x86_64"
                            else -> return@doLast
                        }
                        val lwjglVersions = file("libs").listFiles { f ->
                            f.name.matches(Regex("lwjgl-\\d+\\.\\d+\\.\\d+-natives-release\\.aar"))
                        }
                            ?.map { Regex("lwjgl-(\\d+\\.\\d+\\.\\d+)-natives-release\\.aar").find(it.name)!!.groupValues[1] }
                            ?: emptyList()
                        lwjglVersions.forEach { version ->
                            val nativesDir = File(assetsDir, "app_runtime/lwjgl/$version/natives")
                            if (nativesDir.isDirectory) {
                                nativesDir.listFiles()?.forEach { dir ->
                                    if (dir.isDirectory && dir.name != abi) {
                                        logger.lifecycle("Removing non-target-arch natives: $dir")
                                        dir.deleteRecursively()
                                    }
                                }
                            }
                        }
                    }
                }

                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    val baseName = "$launcherName-${if (variant.buildType == "release") launcherVersionName else "Debug-$launcherVersionName"}"
                    output.outputFileName = if (abi == "all") "$baseName.apk" else "$baseName-$abi.apk"
                }
            }
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

buildKeys {
    string("OAUTH_CLIENT_ID", getKeyFromLocal("OAUTH_CLIENT_ID", ".oauth_client_id.txt", defaultOAuthClientID), true)
    string("LAUNCHER_NAME", launcherAPPName, true)
    string("LAUNCHER_IDENTIFIER", launcherName, true)
    string("LAUNCHER_SHORT_NAME", launcherShortName, true)
    string("URL_HOME", launcherUrl, true)
    string("CURSEFORGE_API", getKeyFromLocal("CURSEFORGE_API_KEY", ".curseforge_api.txt", defaultCurseForgeApiKey), true)
    string("BUILD_ARCH", projectArch)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.nav3)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.material.color.utilities)
    implementation(libs.materialKolor)
    implementation(libs.reorderable)
    implementation(libs.compose.markdown)
    implementation(libs.dev.haze)
    implementation(libs.dev.haze.blur)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(platform(libs.editor.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    //Project
    implementation(project(":LayerController"))
    implementation(project(":ColorPicker"))
    implementation(project(":CardGrid"))
    implementation(project(":Terracotta"))
    implementation(project(":InputMap"))
    implementation(project(":Guide"))
    //Utils
    implementation(libs.bytehook)
    implementation(libs.gson)
    implementation(libs.commons.io)
    implementation(libs.commons.codec)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zip4j)
    implementation(libs.okio)
    implementation(libs.okhttp)
    implementation(libs.ktor.http)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.minidns.hla)
    implementation(libs.toml4j)
    implementation(libs.maven.artifact)
    implementation(libs.mmkv)
    implementation(libs.fishnet)
    implementation(libs.process.phoenix)
    implementation(libs.lunarcalendar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    //Safe
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    //Support
    implementation(libs.proxy.client.android)
    //Hilt
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    //Test
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
