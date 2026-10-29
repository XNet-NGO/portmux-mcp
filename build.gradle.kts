plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "org.xnet"
version = "0.2.3"

repositories {
    mavenCentral()
}

val mcpVersion = "0.15.0"
val ktorVersion = "3.5.1"

dependencies {
    // Official Model Context Protocol Kotlin SDK (server side).
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")

    // Ktor server engine (the SDK uses Ktor but does not pull an engine in).
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.xnet.portmuxmcp.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
