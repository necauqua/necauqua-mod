plugins {
    `java-gradle-plugin`
    groovy
    maven
    id("com.gradle.plugin-publish") version "0.12.0"
}

group = "dev.necauqua"
version = "0.1.0"

task("publish").doLast {
    if (dependsOn.isEmpty()) {
        throw IllegalStateException("No publishing configurations")
    }
}

gradlePlugin {
    plugins {
        create("necauqua-mod") {
            id = "dev.necauqua.nmod"
            displayName = "necauqua mod"
            description = "Common configuration for necauquas Minecraft mods"
            implementationClass = "dev.necauqua.nmod.NecauquaModPlugin"
        }
    }
}

repositories {
    jcenter()
    maven {
        setUrl("https://plugins.gradle.org/m2/")
    }
    maven {
        setUrl("https://files.minecraftforge.net/maven")
    }
}

configurations {
    register("deployer")
}

dependencies {
    implementation("net.minecraftforge.gradle:ForgeGradle:3.+")
    implementation("co.riiid.gradle:co.riiid.gradle.gradle.plugin:0.4.1")
    implementation("com.matthewprenger.cursegradle:com.matthewprenger.cursegradle.gradle.plugin:1.4.0")

    add("deployer", "org.apache.maven.wagon:wagon-ssh-external:3.4.0")
}

if (listOf("repo.url", "repo.username", "repo.sk").all { project.hasProperty(it) }) {
    task("uploadArchives", Upload::class) {
        repositories {
            withConvention(MavenRepositoryHandlerConvention::class) {
                mavenDeployer {
                    configuration = configurations.getByName("deployer")
                    withGroovyBuilder {
                        "repository"("url" to uri(project.property("repo.url") as String)) {
                            "authentication"(
                                "userName" to project.property("repo.username") as String,
                                "privateKey" to project.property("repo.sk") as String
                            )
                        }
                    }
                }
            }
        }
    }
    tasks["publish"].dependsOn += tasks["uploadArchives"]
}
