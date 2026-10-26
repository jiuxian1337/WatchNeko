import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import versioning.BuildConfig

plugins {
    id("com.gradleup.shadow")
}

tasks.named<ShadowJar>("shadowJar") {
    // Minimization is off on purpose. Shadow's dependency analyzer only follows bytecode references, so
    // classes that exist to be discovered through META-INF/services are deleted while their service file
    // survives. Adventure's DataComponentValueConverter providers are exactly that, and the missing class
    // aborts plugin startup on 26.2 with a ServiceConfigurationError. `minimize { exclude(...) }` does not
    // rescue them in Shadow 9.0.0-rc2, and R8 minimization (which does keep service providers) needs a
    // newer Shadow than this build's Kotlin supports. A jar a few megabytes larger beats one that does not
    // start; revisit when the toolchain moves.
    archiveFileName = "${rootProject.name}-${project.name}-${rootProject.version}.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (BuildConfig.relocate) {
        if (BuildConfig.shadePE) {
            relocate(
                "io.github.retrooper.packetevents",
                "ac.grim.grimac.shaded.io.github.retrooper.packetevents"
            )
            relocate(
                "com.github.retrooper.packetevents",
                "ac.grim.grimac.shaded.com.github.retrooper.packetevents"
            )
            relocate(
                "net.kyori",
                "ac.grim.grimac.shaded.kyori"
            ) // use PE's built-in adventure instead when not shading PE
        }
        relocate("club.minnced", "ac.grim.grimac.shaded.discord-webhooks")
        relocate("org.slf4j", "ac.grim.grimac.shaded.slf4j") // Required by discord-webhooks
        relocate("github.scarsz.configuralize", "ac.grim.grimac.shaded.configuralize")
        relocate("com.github.puregero", "ac.grim.grimac.shaded.com.github.puregero")
        relocate("com.google.code.gson", "ac.grim.grimac.shaded.gson")
        relocate("alexh", "ac.grim.grimac.shaded.maps")
        relocate("it.unimi.dsi.fastutil", "ac.grim.grimac.shaded.fastutil")
        relocate("okhttp3", "ac.grim.grimac.shaded.okhttp3")
        relocate("okio", "ac.grim.grimac.shaded.okio")
        relocate("org.yaml.snakeyaml", "ac.grim.grimac.shaded.snakeyaml")
        relocate("org.json", "ac.grim.grimac.shaded.json")
        relocate("org.intellij", "ac.grim.grimac.shaded.intellij")
        relocate("org.jetbrains", "ac.grim.grimac.shaded.jetbrains")
        relocate("org.incendo", "ac.grim.grimac.shaded.incendo")
        relocate("io.leangen.geantyref", "ac.grim.grimac.shaded.geantyref") // Required by cloud
        relocate("com.zaxxer", "ac.grim.grimac.shaded.zaxxer") // Database history
    }
    mergeServiceFiles()
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
