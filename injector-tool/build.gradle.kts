plugins {
    application
}

// Standalone injector window / attach launcher. It has NO dependency on LiquidBounce or any external library — only
// the JDK (com.sun.tools.attach when the jdk.attach module is present) + the bundled jattach binaries for the JRE
// fallback + Swing. That is why it can live as its own Gradle project, separate from the LiquidBounce build.
description = "LiquidBounce injector window: attaches the injector agent to a running Minecraft client JVM."

application {
    mainClass = "InjectorUi"
}

tasks.withType<JavaCompile>().configureEach {
    // Target Java 17 bytecode so the tool runs on any Java 17+ runtime (compiled with whatever JDK 17+ runs Gradle).
    options.release = 17
    options.encoding = "UTF-8"
    // JdkAttacher references com.sun.tools.attach; make the jdk.attach module visible to the unnamed module at compile.
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.attach"))
}

tasks.jar {
    archiveFileName = "liquidbounce-injector-tool.jar"
    manifest { attributes("Main-Class" to "InjectorUi") }
}
