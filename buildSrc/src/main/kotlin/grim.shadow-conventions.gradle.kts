import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import versioning.BuildConfig

plugins {
    id("com.gradleup.shadow")
}

tasks.named<ShadowJar>("shadowJar") {
    minimize {
        // Adventure loads platform serializers and data component converters at runtime.
        exclude(dependency("net.kyori:.*:.*"))
    }
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
        // The package is com.google.gson; com.google.code.gson is only the Maven group id, so a rule
        // written against the group id matches nothing and leaves the classes unrelocated. That is not a
        // cosmetic problem: the server ships its own, much older, Gson under the same package name, and
        // whichever copy the class loader reaches first wins. Building against 2.11 and running against an
        // old server copy is how JsonArray.add(Number) became a NoSuchMethodError at runtime.
        relocate("com.google.gson", "ac.grim.grimac.shaded.gson")
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

/**
 * Fails the build when a relocation rule left classes behind under their original package.
 *
 * <p>A relocate rule whose `from` is not a real package name matches nothing and silently does nothing —
 * which is how `com.google.code.gson` (a Maven group id) sat in this file while the actual classes,
 * `com.google.gson`, stayed unrelocated in the jar. The build stayed green; the failure only appeared at
 * runtime, as a `NoSuchMethodError` from the server's older copy of the same library winning the class
 * lookup. A rule that silently does nothing is worse than no rule, so this check turns it into a build
 * failure at the point where it is cheap to see.
 */
val verifyRelocations by tasks.registering {
    val jar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    val sourcePackages = if (BuildConfig.relocate) listOf(
        "club.minnced", "org.slf4j", "github.scarsz.configuralize", "com.github.puregero",
        "com.google.gson", "alexh", "it.unimi.dsi.fastutil", "okhttp3", "okio",
        "org.yaml.snakeyaml", "org.json", "org.intellij", "org.jetbrains", "org.incendo",
        "io.leangen.geantyref", "com.zaxxer",
    ) else emptyList()
    inputs.file(jar)
    inputs.property("sourcePackages", sourcePackages)
    dependsOn(tasks.named("shadowJar"))

    doLast {
        val archive = java.util.zip.ZipFile(jar.get().asFile)
        val entries = archive.use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val leaked = sourcePackages.flatMap { pkg ->
            val prefix = pkg.replace('.', '/') + "/"
            entries.filter { it.startsWith(prefix) }.map { "$pkg -> $it" }
        }
        if (leaked.isNotEmpty()) {
            throw GradleException(
                "relocation left ${leaked.size} class(es) under their original package. A relocate rule " +
                    "whose source is not a real package name matches nothing. First few:\n  " +
                    leaked.take(5).joinToString("\n  ")
            )
        }
    }
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
    dependsOn(verifyRelocations)
}
