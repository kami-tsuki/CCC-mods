import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("net.neoforged.moddev") version "2.0.147" apply false
}

val instanceDir: File = file(property("deploy_instance_dir") as String)
val serverDir: File = file(property("deploy_server_dir") as String)

subprojects {
    layout.buildDirectory = rootProject.layout.buildDirectory.dir(name)

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "net.neoforged.moddev")

    val modId = property("mod_id") as String

    group = property("mod_group") as String
    version = property("mod_version") as String

    extensions.configure<BasePluginExtension> { archivesName = modId }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain((property("java_version") as String).toInt())
    }

    extensions.configure<NeoForgeExtension> {
        version = property("neo_version") as String
        mods.register(modId) { sourceSet(the<SourceSetContainer>()["main"]) }
    }

    repositories {
        mavenCentral()
        maven("https://thedarkcolour.github.io/KotlinForForge/") {
            content { includeGroup("thedarkcolour") }
        }
        maven("https://api.modrinth.com/maven") {
            content { includeGroup("maven.modrinth") }
        }
    }

    dependencies {
        add("implementation", "thedarkcolour:kotlinforforge-neoforge:${property("kff_version")}")
        add("testImplementation", kotlin("test"))
    }

    tasks.withType<Test> { useJUnitPlatform() }

    // Shared neoforge.mods.toml templating: every module fills in the same placeholder set,
    // sourced from its own mod_id/mod_name/mod_version/mod_description plus the shared
    // mod_authors/mod_license/kff_version from the root gradle.properties.
    tasks.named<ProcessResources>("processResources") {
        val props = properties.filterValues { it is String }.mapValues { it.value as String } +
            rootProject.subprojects.associate { "${it.property("mod_id")}_version" to it.property("mod_version") as String } +
            mapOf("mod_description" to (findProperty("mod_description") as String? ?: ""))
        inputs.properties(props)
        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
    }

    // A single `deploy` task shape shared by every mod module: wipe this mod's old jar and
    // config from both the dedicated test server and the Prism instance, then drop the fresh
    // jar into both. Config is intentionally wiped too (not merged) - deploys always start a
    // module from its code defaults rather than carrying forward stale/hand-edited state.
    val deployEnabled = (property("deploy_enabled") as String).toBoolean()
    val jar = tasks.named<Jar>("jar")
    val deploy by tasks.registering {
        group = "kami deploy"
        description = "Deploys $modId's jar to the test server and the Prism instance, clearing old jar/config first."
        dependsOn(jar)
        onlyIf { deployEnabled }
        doLast {
            val jarFile = jar.get().archiveFile.get().asFile
            listOf(serverDir, instanceDir).forEach { target ->
                val mods = target.resolve("mods")
                val config = target.resolve("config")
                mods.mkdirs()
                mods.listFiles { f -> f.name.startsWith(modId) && f.extension == "jar" }?.forEach { it.delete() }
                config.listFiles { f -> f.name.startsWith(modId) }?.forEach { it.deleteRecursively() }
                jarFile.copyTo(mods.resolve(jarFile.name), overwrite = true)
            }
        }
    }
    tasks.named("build") { finalizedBy(deploy) }
}
