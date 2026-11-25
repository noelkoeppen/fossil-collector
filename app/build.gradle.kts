import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 3D-Modelle als Assets (AP2-10, GAME_DESIGN.md Kapitel 8): kopiert fuer jede Art aus
 * `species.json` die Knochen-Meshes `art/models3d/<speciesId>/bones/<boneId>.glb` nach
 * `models3d/<speciesId>/bones/` in den Assets. Nichts wird doppelt eingecheckt, und eine neue Art
 * braucht keinen Code; Modellordner ohne Eintrag in `species.json` kommen nicht ins APK.
 * Vollstaendigkeit und Format prueft `ModelAssetsTest`.
 */
abstract class SyncModelAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modelsDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val speciesFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun sync() {
        // species.json ist eine Liste von Arten mit `id` und `bones`.
        val species = (JsonSlurper().parse(speciesFile.get().asFile) as List<*>).map { it as Map<*, *> }
        fileSystem.sync {
            into(outputDir)
            species.forEach { entry ->
                val id = entry["id"] as String
                val files = (entry["bones"] as List<*>).map { "$it.glb" }
                from(modelsDir.dir("$id/bones")) {
                    include(files)
                    into("models3d/$id/bones")
                }
            }
        }
    }
}

android {
    namespace = "de.noelkoeppen.fossilcollector"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.noelkoeppen.fossilcollector"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Versionsanzeige in den Einstellungen (AP-24): BuildConfig.VERSION_NAME
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // Knochenbilder (BoneImageAssetsTest): fuer jeden Knochen ein Bild und eine Maske.
            val assetsDir = file("src/main/assets")
            it.systemProperty("fossil.assetsDir", assetsDir.absolutePath)
            it.inputs.dir(assetsDir).withPropertyName("assets").withPathSensitivity(PathSensitivity.RELATIVE)
            // Vorschau der Grabungsflaeche (AP2-07, DigPreviewExportTest): nur mit -Pfossil.previewDir=<Ordner>.
            providers.gradleProperty("fossil.previewDir").orNull?.let { dir -> it.systemProperty("fossil.previewDir", dir) }
            // Gleiche Beleuchtung wie die Knochenbilder (AP2-10, ModelShadingTest).
            val boneImagesScript = rootProject.file("tools/models3d/bone_images.py")
            it.systemProperty("fossil.boneImagesScript", boneImagesScript.absolutePath)
            it.inputs.file(boneImagesScript).withPropertyName("boneImagesScript").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

// Je Variante eine Kopie der Modelle als generierte Assets (AP2-10); die Unit-Tests der Variante
// pruefen genau diese Kopie (ModelAssetsTest, Systemeigenschaft fossil.modelAssetsDir).
androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val sync = tasks.register<SyncModelAssets>("sync${suffix}ModelAssets") {
            modelsDir.set(rootProject.layout.projectDirectory.dir("art/models3d"))
            speciesFile.set(rootProject.layout.projectDirectory.file("core/src/main/resources/data/species.json"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(sync, SyncModelAssets::outputDir)
        val syncedDir = sync.flatMap { it.outputDir }
        tasks.withType<Test>().configureEach {
            if (name == "test${suffix}UnitTest") {
                inputs.dir(syncedDir).withPropertyName("modelAssets").withPathSensitivity(PathSensitivity.RELATIVE)
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider { listOf("-Dfossil.modelAssetsDir=${syncedDir.get().asFile.absolutePath}") },
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    // JSON-Block der .glb-Dateien (GlbReader, AP2-10); kommt ueber core ohnehin ins APK.
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM-Unit-Tests ohne Android (SaveStorage, Tab-Regeln, Raten), JUnit 5 wie in core.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
