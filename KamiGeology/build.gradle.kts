dependencies {
    implementation(project(":KamiLibs"))
    compileOnly("mezz.jei:jei-1.21.1-common-api:${property("jei_version")}")
    compileOnly("mezz.jei:jei-1.21.1-neoforge-api:${property("jei_version")}")
}
