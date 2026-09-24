dependencies {
    implementation(project(":KamiLibs"))
    compileOnly("maven.modrinth:create:${property("create_version")}")
    compileOnly("maven.modrinth:numismatics:${property("numismatics_version")}")
    compileOnly("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}")
}
