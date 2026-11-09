import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay.cn"
        minSdk = 25
        targetSdk = 37
        versionCode = 98
        versionName = "0.2.15-cn.7"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            val storePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
            } else {
                // Stable CN key: CI runners are ephemeral, so a per-run debug key would change the
                // signature on every build and users could not install updates over each other.
                storeFile = rootProject.file("signing/diplay-cn.jks")
                storePassword = "diplay-cn"
                keyAlias = "diplaycn"
                keyPassword = "diplay-cn"
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":icoca"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a standalone release APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}

// =============================================================================================
// ICCOA CarLink SDK dex injection
//
// AGP does not consume .dex dependencies (implementation/runtimeOnly files("*.dex") never reach
// the APK), so the SDK ships as the next classesN.dex appended after packaging. Rewriting the zip
// invalidates whatever AGP signed, so the APK is re-aligned and re-signed with the same signing
// config the variant already uses.
//
// Sources: icoca/libs/icar-sdk.dex (runtime, injected) and icoca/stubs/icar-sdk-stubs.jar
// (compile-only signature stubs, consumed by :icoca).
// =============================================================================================
val iccoaSdkDex = rootProject.file("icoca/libs/icar-sdk.dex")

/**
 * Everything the injection task needs is resolved here, at configuration time, so the action only
 * captures plain values. Capturing the script object would make the task incompatible with the
 * configuration cache, which this build enables.
 */
val injectIccoaDex by tasks.registering {
    group = "build"
    description = "Append the ICCOA CarLink SDK dex to the packaged APKs and re-sign them"

    val dexFile = iccoaSdkDex
    val buildToolsDir = run {
        val props = Properties()
        val local = rootProject.file("local.properties")
        if (local.exists()) FileInputStream(local).use { props.load(it) }
        val candidates = mutableListOf<File>()
        System.getenv("ANDROID_HOME")?.let { candidates.add(File(it)) }
        System.getenv("ANDROID_SDK_ROOT")?.let { candidates.add(File(it)) }
        props.getProperty("sdk.dir")?.let { candidates.add(File(it)) }
        val sdkDir = candidates.firstOrNull { it.isDirectory }
        sdkDir?.resolve("build-tools")?.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { it.resolve("lib/apksigner.jar").isFile }
            ?.filter { it.resolve("zipalign.exe").isFile || it.resolve("zipalign").isFile }
            ?.maxByOrNull { it.name }
    }
    val zipalignFile = buildToolsDir?.let {
        it.resolve("zipalign.exe").takeIf { tool -> tool.isFile } ?: it.resolve("zipalign")
    }
    val apksignerFile = buildToolsDir?.resolve("lib/apksigner.jar")
    val apkRootDir = layout.buildDirectory.dir("outputs/apk").get().asFile
    val signingByType: Map<String, Map<String, String?>> = listOf("debug", "release").associateWith { name ->
        val config = android.signingConfigs.getByName(name)
        mapOf(
            "storeFile" to config.storeFile?.absolutePath,
            "storePassword" to config.storePassword,
            "keyAlias" to config.keyAlias,
            "keyPassword" to config.keyPassword,
        )
    }

    inputs.file(dexFile).withPropertyName("iccoaSdkDex").optional()
    outputs.upToDateWhen { false }
    onlyIf { dexFile.isFile }

    doLast {
        val dexBytes = dexFile.readBytes()
        requireNotNull(buildToolsDir) {
            "找不到可用的 build-tools：设置 ANDROID_HOME 或在 local.properties 写 sdk.dir"
        }
        val zipalign = requireNotNull(zipalignFile) { "build-tools 里没有 zipalign" }
        val apksignerJar = requireNotNull(apksignerFile) { "build-tools 里没有 lib/apksigner.jar" }

        fun runTool(command: List<String>) {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            check(code == 0) { "${command.first()} 失败（退出码 $code）：\n$output" }
        }

        val apkRoot = apkRootDir
        val apks = listOf("debug", "release")
            .map { apkRoot.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { dir ->
                (dir.listFiles() ?: emptyArray()).filter {
                    it.isFile && it.name.endsWith(".apk") &&
                        !it.name.endsWith("-injected.apk") && !it.name.endsWith("-aligned.apk")
                }
            }
        require(apks.isNotEmpty()) { "找不到 APK：先运行 assembleDebug / assembleRelease" }

        apks.forEach { apk ->
            val isRelease = apk.parentFile?.name == "release"
            val base = apk.name.removeSuffix(".apk")
            val injected = File(apk.parentFile, "$base-injected.apk")
            val aligned = File(apk.parentFile, "$base-aligned.apk")

            // --- 1. append classesN.dex, dropping META-INF signatures and any stale copy ---
            val zip = ZipFile(apk)
            val entries = zip.entries().toList()
            // Re-running the build must not append the same dex twice.
            val stale = entries.filter { entry ->
                entry.name.matches(Regex("classes\\d+\\.dex")) &&
                    entry.size == dexBytes.size.toLong() &&
                    zip.getInputStream(entry).readBytes().contentEquals(dexBytes)
            }.map { it.name }.toSet()
            if (stale.isNotEmpty()) logger.lifecycle(">>> [${apk.name}] 移除历史注入副本: ${stale.joinToString(", ")}")

            val kept = entries.filter { it.name !in stale }
            val names = kept.map { it.name }.toSet()
            var n = 2
            while ("classes$n.dex" in names) n++
            val dexName = "classes$n.dex"

            BufferedOutputStream(FileOutputStream(injected)).use { out ->
                val zout = ZipOutputStream(out)
                for (entry in kept) {
                    val name = entry.name
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".SF") || name.endsWith(".MF") ||
                            name.endsWith(".DSA") || name.endsWith(".EC"))) continue
                    val data = zip.getInputStream(entry).readBytes()
                    val copy = ZipEntry(name)
                    copy.method = entry.method
                    copy.size = entry.size
                    copy.crc = entry.crc
                    copy.time = entry.time
                    // STORED entries must declare compressedSize == size; DEFLATED must not.
                    if (entry.method == ZipEntry.STORED) copy.compressedSize = entry.size
                    zout.putNextEntry(copy)
                    zout.write(data)
                }
                val dexEntry = ZipEntry(dexName)
                dexEntry.method = ZipEntry.DEFLATED
                zout.putNextEntry(dexEntry)
                zout.write(dexBytes)
                zout.closeEntry()
                zout.close()
            }
            zip.close()
            logger.lifecycle(">>> [${apk.name}] 已附加 $dexName (${dexFile.length()} B)")

            // --- 2. align ---
            runTool(listOf(zipalign.absolutePath, "-p", "-f", "4", injected.absolutePath, aligned.absolutePath))

            // --- 3. re-sign with the variant's own signing config ---
            val signing = signingByType.getValue(if (isRelease) "release" else "debug")
            val storeFilePath = signing["storeFile"]
            val storeFile = storeFilePath?.let { File(it) }
            if (storeFile != null && storeFile.isFile) {
                val args = mutableListOf(
                    "java", "-jar", apksignerJar.absolutePath, "sign",
                    "--ks", storeFile.absolutePath,
                    "--ks-pass", "pass:" + (signing["storePassword"] ?: ""),
                )
                signing["keyAlias"]?.let { args += listOf("--ks-key-alias", it) }
                signing["keyPassword"]?.let { args += listOf("--key-pass", "pass:$it") }
                args += listOf("--out", apk.absolutePath, aligned.absolutePath)
                runTool(args)
                logger.lifecycle(">>> ${apk.name} 已注入 ICCOA SDK 并重签：${apk.absolutePath}")
            } else {
                // Without a keystore apksigner is also the only writer back to the output path;
                // copying keeps the injected package complete and merely unsigned.
                aligned.copyTo(apk, overwrite = true)
                logger.warn(">>> ${apk.name} 无可用签名密钥 → 保留注入后的未签名包")
            }
            injected.delete()
            aligned.delete()
        }
    }
}

// APK assembly tasks exist only after variants are configured, so hook them late.
afterEvaluate {
    tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }
        .configureEach { finalizedBy(injectIccoaDex) }
}
