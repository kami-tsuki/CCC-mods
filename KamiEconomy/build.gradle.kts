val instanceModsDir: File by extra

dependencies {
    implementation(project(":KamiLibs"))
    compileOnly(fileTree(instanceModsDir) { include("CreateNumismatics-*.jar", "create-1.21.1-*.jar") })
}
