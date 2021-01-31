plugins {
    `groovy-gradle-plugin`
    `maven-publish`
}

group = "dev.necauqua"
version = "0.5.6"

gradlePlugin.plugins.create("necauqua-mod") {
    id = "dev.necauqua.nmod"
    displayName = "necauqua mod"
    description = "Common configuration for necauquas Minecraft mods"
    implementationClass = "dev.necauqua.nmod.NecauquaModPlugin"
}

repositories {
    jcenter()
    maven { url = uri("https://maven.necauqua.dev") }
}

dependencies {
    implementation("net.minecraftforge.gradle:ForgeGradle:3.+")
    implementation("co.riiid.gradle:co.riiid.gradle.gradle.plugin:0.4.1")
    implementation("com.matthewprenger.cursegradle:com.matthewprenger.cursegradle.gradle.plugin:1.4.0")
    implementation("org.spongepowered:mixingradle:0.7-SNAPSHOT")
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
