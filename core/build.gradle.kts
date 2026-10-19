plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Bytecode 17 so the Android app can use this module, whatever JDK builds it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("com.googlecode.libphonenumber:libphonenumber:8.13.52")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
