plugins {
    `kotlin-dsl`
}

group = "net.ccbluex"

repositories {
    mavenCentral()
}

dependencies {
    // ASM for relocating the bundled okhttp/okio in the packaged jar (JarRelocator). 9.8 reads Java 25 bytecode
    // (class-file major 69) that LiquidBounce compiles to; asm-commons provides ClassRemapper.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
}
