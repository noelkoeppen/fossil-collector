import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Reines Kotlin/JVM-Modul: Spiellogik ohne Android-Abhaengigkeiten (GAME_DESIGN.md, Kapitel 0, Regel 4).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Datenvalidierung gegen die 3D-Modelle (SpeciesModelFilesTest): jeder Knochen braucht eine .glb.
    val modelsDir = rootProject.file("art/models3d")
    systemProperty("fossil.modelsDir", modelsDir.absolutePath)
    inputs.dir(modelsDir).withPropertyName("models3d").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("passed", "skipped", "failed")
    }
}
