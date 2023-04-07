plugins {
    `groovy-gradle-plugin`
    `maven-publish`
}

group = "dev.necauqua"
version = "0.9.5"

gradlePlugin.plugins.create("necauqua-mod") {
    id = "dev.necauqua.nmod"
    displayName = "necauqua mod"
    description = "Common Gradle configuration for Minecraft mods made by necauqua"
    implementationClass = "dev.necauqua.nmod.NecauquaModPlugin"
}

repositories {
    maven { url = uri("https://maven.necauqua.dev") }
}

dependencies {
    runtimeOnly(gradleApi())
    runtimeOnly(localGroovy())
    implementation("net.minecraftforge.gradle:ForgeGradle:5.1.+") { isChanging = true }
    implementation("org.spongepowered:mixingradle:0.7-SNAPSHOT")
    implementation("com.matthewprenger.cursegradle:com.matthewprenger.cursegradle.gradle.plugin:1.4.0")
    implementation("com.modrinth.minotaur:com.modrinth.minotaur.gradle.plugin:2.+")
}

publishing {
    publications {
        create<MavenPublication>("main") {
            from(components["java"])
            pom {
                name.set("necauqua-mod")
                description.set("A dumb Gradle plugin that sets up all the defaults related to making a Minecraft mod if you are *me*")
                url.set("https://github.com/necauqua/necauqua-mod#readme")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/mit-license")
                    }
                }
                developers {
                    developer {
                        id.set("necauqua")
                        name.set("Anton Bulakh")
                        email.set("self@necauqua.dev")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/necauqua/necauqua-mod")
                    developerConnection.set("scm:git:https://github.com/necauqua/necauqua-mod")
                    url.set("https://github.com/necauqua/necauqua-mod#readme")
                }
            }
        }
    }
    repositories {
        if (listOf("maven.user", "maven.pass").all(project::hasProperty)) {
            maven {
                name = "necauqua"
                url = uri("https://maven.necauqua.dev")
                credentials {
                    username = project.properties["maven.user"] as String
                    password = project.properties["maven.pass"] as String
                }
            }
        }
    }
}
