import java.security.MessageDigest

plugins {
    java
}

group = "fi.alavesa"
version = "1.0.4"

repositories {
    mavenCentral()
    // Paper's 26.x API is published here as <mc>.build.<n>-<channel>; there is no -R0.1-SNAPSHOT any more.
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Pinned to an exact stable build for reproducible builds. Bump the build number to move forward.
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    // Soft dependency: never shaded, only touched from luckperms/LuckPermsPrefixes, which loads when LuckPerms is there.
    compileOnly("net.luckperms:api:5.5")
    // The server's own netty, compile-only: same-port pack delivery (resourcepack/SamePortHttp) hooks the
    // Minecraft listening channel. Nothing is shaded.
    compileOnly(fileTree("libs") { include("netty-*.jar") })
}

tasks.withType<JavaCompile>().configureEach {
    // Paper 26.3 requires Java 25. --release 25 lets any JDK >= 25 build it (no toolchain download needed).
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

// ---------------------------------------------------------------------------------------------------------------------
// Resource pack: zip resourcepack/ reproducibly (same input = same bytes = same SHA-1) and write the hash next to it.
//   ./gradlew packZip      -> build/pack/idcards-pack.zip + build/pack/idcards-pack.zip.sha1
// This is the stand-alone pack (for hosting it yourself, or for merging it into a server pack). The pack sources
// also ride inside the plugin jar: the plugin builds plugins/IdCards/idcards-pack.zip from them at start, merging
// the text shader with the ones of other plugins when they are installed (resourcepack/TextShaderMerge).
// ---------------------------------------------------------------------------------------------------------------------
val packZip by tasks.registering(Zip::class) {
    group = "build"
    description = "Zips the resource pack and prints its SHA-1"
    from("resourcepack")
    exclude("**/.DS_Store")
    archiveFileName.set("idcards-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doLast {
        val zip = archiveFile.get().asFile
        val sha1 = MessageDigest.getInstance("SHA-1").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        File(zip.parentFile, zip.name + ".sha1").writeText(sha1 + "\n")
        println("Resource pack: $zip")
        println("SHA-1:         $sha1")
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
    from("resourcepack") {
        into("resourcepack")
        exclude("**/.DS_Store")
    }
}

tasks.build { dependsOn(packZip) }

// prints the compile classpath (used by tools that javap the API)
tasks.register("printCp") { doLast { println(configurations.compileClasspath.get().asPath) } }
