val instanceModsDir: File by extra

dependencies {
    compileOnly(fileTree(instanceModsDir) { include("CreateNumismatics-*.jar", "create-1.21.1-*.jar", "xaeroworldmap-neoforge-*.jar") })
}
