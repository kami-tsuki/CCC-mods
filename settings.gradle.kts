pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }
}

rootProject.name = providers.gradleProperty("project_name").get()

// KAMI Mods that are in this project
val mods = listOf(
    "KamiLibs",
    "KamiGeology",
    "KamiClaims",
    "KamiEconomy",
    "KamiEssentials",
)

mods.forEach { include(":$it") }
