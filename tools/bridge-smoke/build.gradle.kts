plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }

// Salin apa adanya dua berkas produksi yang diuji ke source set smoke.
val copyRealSources = tasks.register<Copy>("copyRealSources") {
    from(rootDir.resolve("../../app/src/main/java")) {
        include("agu/analys/bridge/AgentHttpServer.kt", "agu/analys/bridge/AgentOutputModels.kt")
    }
    into(layout.buildDirectory.dir("real-src"))
}

sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("real-src")) }
tasks.named("compileKotlin") { dependsOn(copyRealSources) }

dependencies {
    implementation("io.ktor:ktor-server-cio-jvm:2.3.9")
    implementation("io.ktor:ktor-server-websockets-jvm:2.3.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}
application { mainClass.set("smoke.MainKt") }
