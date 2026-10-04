import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
}

val shade by configurations.creating

configurations.compileOnly { extendsFrom(shade) }
configurations.named("additionalRuntimeClasspath") { extendsFrom(shade) }
configurations.runtimeElements { extendsFrom(shade) }

dependencies {
    compileOnly("maven.modrinth:create:${property("create_version")}")
    compileOnly("maven.modrinth:numismatics:${property("numismatics_version")}")
    compileOnly("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}")
    shade("net.dv8tion:JDA:${property("jda_version")}") {
        exclude(group = "club.minnced", module = "opus-java")
        exclude(group = "com.google.crypto.tink")
        exclude(group = "org.slf4j")
        exclude(group = "org.jetbrains.kotlin")
    }
}

tasks.named<Jar>("jar") { archiveClassifier = "slim" }

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    configurations.set(listOf(shade))
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    listOf("net.dv8tion", "com.fasterxml.jackson", "okhttp3", "okio", "gnu.trove", "org.apache.commons.collections4", "com.neovisionaries")
        .forEach { relocate(it, "kami.libs.shaded.$it") }
}

tasks.named("assemble") { dependsOn(shadowJar) }
