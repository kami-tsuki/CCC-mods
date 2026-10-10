dependencies {
    implementation(project(":KamiLibs"))
    compileOnly("maven.modrinth:create:${property("create_version")}")
    compileOnly("net.createmod.ponder:ponder-neoforge:${property("ponder_version")}")
    compileOnly("maven.modrinth:numismatics:${property("numismatics_version")}")
    compileOnly("mezz.jei:jei-1.21.1-common-api:${property("jei_version")}")
    compileOnly("mezz.jei:jei-1.21.1-neoforge-api:${property("jei_version")}")
}
