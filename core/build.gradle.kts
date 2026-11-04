import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lógica pura (sin Android): reconocimiento de órdenes, diálogo y parsers de datos.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Android ya incluye org.json; aquí solo se usa para compilar y para los tests.
    compileOnly("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

tasks.test { useJUnitPlatform() }
