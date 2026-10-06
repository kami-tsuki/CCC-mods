val jeiVersion = "19.57.0.449"

repositories {
    maven("https://maven.blamejared.com") { content { includeGroup("mezz.jei") } }
}

dependencies {
    implementation(project(":KamiLibs"))
    compileOnly("mezz.jei:jei-1.21.1-common-api:$jeiVersion")
    compileOnly("mezz.jei:jei-1.21.1-neoforge-api:$jeiVersion")
}
