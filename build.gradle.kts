import versioning.BuildConfig
import java.text.SimpleDateFormat
import java.util.*

BuildConfig.init(project)

val baseVersion = SimpleDateFormat("yy.MM.dd").format(Date())
group = "cc.watchneko"
version = baseVersion
description =
    "Libre simulation anticheat designed for 1.21 with 1.8-1.21 support, powered by PacketEvents 2.0."

ext["timestamp"] = System.currentTimeMillis().toString()

println("Build configuration:")
println("    shadePE             = ${BuildConfig.shadePE}")
println("    relocate            = ${BuildConfig.relocate}")
println("    mavenLocalOverride  = ${BuildConfig.mavenLocalOverride}")
println("    release             = ${BuildConfig.release}")
println("    version             = $version")

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.isFork = true
        options.isIncremental = true
    }
}
