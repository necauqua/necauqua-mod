plugins {
    `java-gradle-plugin`
    groovy
    maven
    id("com.gradle.plugin-publish") version "0.12.0"
}

group = "dev.necauqua"
version = "0.1.7"

gradlePlugin.plugins.create("necauqua-mod") {
    id = "dev.necauqua.nmod"
    displayName = "necauqua mod"
    description = "Common configuration for necauquas Minecraft mods"
    implementationClass = "dev.necauqua.nmod.NecauquaModPlugin"
}

configurations.register("deployer")

repositories {
    jcenter()
    maven { url = uri("https://plugins.gradle.org/m2/") }
    maven { url = uri("https://files.minecraftforge.net/maven") }
}

dependencies {
    implementation("net.minecraftforge.gradle:ForgeGradle:3.+")
    implementation("co.riiid.gradle:co.riiid.gradle.gradle.plugin:0.4.1")
    implementation("com.matthewprenger.cursegradle:com.matthewprenger.cursegradle.gradle.plugin:1.4.0")

    add("deployer", "org.apache.maven.wagon:wagon-ssh-external:3.4.0")
}

val publish = task("publish").doLast {
    if (dependsOn.isEmpty()) {
        throw IllegalStateException("No publishing configurations")
    }
}

if (listOf("repo.url", "repo.username", "repo.sk").all { project.hasProperty(it) }) {
    // tasks.getByName<Upload>("uploadArchives") { .. } does not work (tries to cast Upload to Unit somehow idk)
    tasks.withType<Upload> {
        repositories.withConvention(MavenRepositoryHandlerConvention::class) {
            mavenDeployer {
                withGroovyBuilder {
                    "setConfiguration"(configurations["deployer"])
                    "repository"("url" to uri(project.property("repo.url") as String)) {
                        "authentication"(
                            "userName" to project.property("repo.username"),
                            "password" to project.property("repo.sk")
                        )
                    }
                }
            }
        }
        publish.dependsOn += this
    }
}
