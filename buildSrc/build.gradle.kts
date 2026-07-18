plugins {
    `kotlin-dsl`
}

group = "net.ccbluex"

repositories {
    mavenCentral()
}

dependencies {
    // ASM for the Lunar compatibility check (parses LiquidBounce mixins + Lunar's baked classes). 9.8 reads Java 25
    // bytecode (class-file major 69) that LiquidBounce compiles to.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    // asm-commons provides ClassRemapper for JarRelocator.
    implementation("org.ow2.asm:asm-commons:9.8")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
}
