package dev.necauqua.nmod

import com.matthewprenger.cursegradle.CurseExtension
import com.modrinth.minotaur.ModrinthExtension
import groovy.json.JsonSlurper
import groovy.transform.Field
import net.minecraftforge.gradle.common.tasks.SignJar
import net.minecraftforge.gradle.common.util.MinecraftExtension
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.spongepowered.asm.gradle.plugins.MixinExtension

static List<String> git(List<String> args) {
    def res = ['git', *args].execute().text.split('\n').toList()
    return res != [''] ? res : []
}

// this is literally just TypeScript at this point..
interface ExtendedProject extends Project {
    Nmod nmod = null
    IdeaModel idea = null
    JavaPluginExtension java = null
    JavaCompile compileJava = null
    JavaCompile compileTestJava = null
    SourceSetContainer sourceSets = null
    Jar jar = null

    // well surprisingly intellij does support gradle domain objects not in gradle scripts
    // but not doing `container { .. }` configuration calls sadly, only `container.<name>`
    // it would literally be perfect if it worked :(
    Jar jar(@DelegatesTo(Jar) Closure c)
    MinecraftExtension minecraft(@DelegatesTo(MinecraftExtension) Closure c)
    MixinExtension mixin(@DelegatesTo(MixinExtension) Closure c)
    ModrinthExtension modrinth(@DelegatesTo(ModrinthExtension) Closure c)
    CurseExtension curseforge(@DelegatesTo(CurseExtension) Closure c)

//    PublishingExtension publishing(@DelegatesTo(PublishingExtension) Closure c)
    // ^ it's worse with it than without it
}

static def hint(@DelegatesTo(ExtendedProject) Closure c) {
    return c
}

// the lowest possible indentation level for pseudo-gradle config
// without evaluating groovy from a file
@Field
static def before = hint {
    apply plugin: 'java'
    apply plugin: 'maven-publish'
    apply plugin: 'idea'
    apply plugin: 'net.minecraftforge.gradle'
    apply plugin: 'com.matthewprenger.cursegradle'
    apply plugin: 'com.modrinth.minotaur'
}

@Field
static Closure configure = hint {

    def gitDescribe = git(['describe', '--always', '--first-parent'])
    if (gitDescribe.isEmpty()) {
        // new repo with no commits? no git at all? etc
        version = 'unversioned'
    } else {
        version = gitDescribe.first().replaceFirst(~/^v/, '')
    }

    group = 'dev.necauqua.mods'

    java.toolchain.languageVersion.set(JavaLanguageVersion.of(nmod.javaVersion))
    idea.project?.jdkName = Integer.toString(nmod.javaVersion)

    idea.module {
        inheritOutputDirs = false
        outputDir = compileJava.destinationDirectory.getAsFile().get()
        testOutputDir = compileTestJava.destinationDirectory.getAsFile().get()
    }

    //noinspection ConfigurationAvoidance - sourceSets.named fails if object was never defined
    def api = sourceSets.findByName('api')

    if (api) {
        sourceSets.main {
            compileClasspath += api.output
            runtimeClasspath += api.output
        }
        configurations {
            apiCompile.extendsFrom(compile)
            apiImplementation.extendsFrom(implementation)
        }
    }

    sourceSets.main.resources {
        srcDir 'src/generated/resources'
    }

    def at = file('src/main/resources/accesstransformer.cfg')

    minecraft {
        if (nmod.mappings instanceof String) {
            mappings channel: 'official', version: nmod.mappings
        } else {
            mappings nmod.mappings
        }

        if (at.exists()) {
            accessTransformer = at
        }

        runs {
            client {
                workingDirectory file('build/run')
                args '--username', 'necauqua'
                args '--uuid', 'f98e9365-2c52-48c5-8647-6662f70b7e3d'
                property 'forge.logging.console.level', 'debug'
                if (nmod.coremod) {
                    property 'fml.coreMods.load', nmod.coremod
                }
                if (nmod.mixin) {
                    args '--mixin', "${project.name.toLowerCase()}.mixins.json".toString()
                    args '--tweakClass', 'org.spongepowered.asm.launch.MixinTweaker'
                    nmod.extraMixinConfigs.each { args '--mixin', it }
                    properties 'mixin.hotSwap': 'true', 'mixin.debug': 'true', 'mixin.env.disableRefMap': 'true'
                }

                mods.create(project.name) {
                    source sourceSets.main
                    if (api) {
                        source api
                    }
                }
            }
            server {
                workingDirectory file('build/server')
                property 'forge.logging.console.level', 'debug'

                if (nmod.mixin) {
                    args '--mixin', "${project.name.toLowerCase()}.mixins.json".toString()
                    args '--tweakClass', 'org.spongepowered.asm.launch.MixinTweaker'
                    nmod.extraMixinConfigs.each { args '--mixin', it }
                    properties 'mixin.hotSwap': 'true', 'mixin.debug': 'true', 'mixin.env.disableRefMap': 'true'
                }

                mods.create(project.name) {
                    source sourceSets.main
                    if (api) {
                        source api
                    }
                }
            }
        }
    }

    if (nmod.mixin) {
        apply plugin: 'org.spongepowered.mixin'

        mixin {
            add sourceSets.main, "${project.name.toLowerCase()}.mixins.refmap.json"
        }
    }

    configurations {
        packaged
        implementation.extendsFrom packaged
    }

    repositories {
        mavenLocal()
        maven { url = 'https://maven.necauqua.dev' }

        // add the test mods folder if it's there
        flatDir {
            name 'test-mods'
            dir file('test-mods')
        }
    }

    dependencies {
        minecraft "net.minecraftforge:forge:${nmod.forge}"
        if (nmod.mixin) {
            annotationProcessor "org.spongepowered:mixin:${nmod.mixin}:processor"
        }

        // load the test-mods if any
        for (extraModJar in fileTree(dir: 'test-mods', include: '*.jar')) {
            def basename = extraModJar.name.substring(0, extraModJar.name.length() - '.jar'.length())
            def versionSep = basename.lastIndexOf('-')
            assert versionSep != -1
            def artifactId = basename.substring(0, versionSep)
            def version = basename.substring(versionSep + 1)
            runtimeOnly fg.deobf("test-mods:$artifactId:$version")
        }
    }

    // keep most of source/resource preprocessing for 1.12 compat
    tasks.register('processSources', Sync) {
        def processedFolder = buildDir.toPath().resolve('processSources')

        inputs.property('version', project.version)
        from(compileJava.source)
        into(processedFolder)

        compileJava.source = processedFolder

        filter(ReplaceTokens, tokens: [
                VERSION         : project.version,
                MC_VERSION_RANGE: (nmod.mcversions.size() == 1 ?
                        "[${nmod.mcversions.first()}]" :
                        "[${nmod.mcversions.last()},${nmod.mcversions.first()}]").toString(),
                // here we strip the .minor.patch-detail suffix (meh, shut up, I love regex)
                API_VERSION     : project.version.replaceAll('(?:.*?-)(.*?)\\.\\d+\\.\\d+(?:-.*?)?$', '$1'),
        ])
        // ensure there is stuff in compileJava.source in case there are other preprocessing tasks
        mustRunAfter(compileJava.dependsOn.findAll { it instanceof AbstractCopyTask })
    }
    compileJava.dependsOn += processSources

    processResources {
        if (api) {
            from(api.resources.srcDirs)
        }
        rename '(accesstransformer\\.cfg|mods\\.toml|coremods\\.json)', 'META-INF/$1'
    }

    afterEvaluate {
        jar.from(configurations.packaged.collect { it.isDirectory() ? it : zipTree(it) }) {
            exclude 'LICENSE*', 'META-INF/MANIFSET.MF', 'META-INF/maven/**', 'META-INF/*.RSA', 'META-INF/*.SF'
        }
    }
    jar {
        finalizedBy 'reobfJar'
        finalizedBy 'signJar'

        if (api) {
            from api.output.classesDirs
        }
        from 'LICENSE'

        manifest {
            if (nmod.coremod) {
                attributes 'FMLCorePlugin': nmod.coremod, 'FMLCorePluginContainsFMLMod': 'true'
            }
            if (at) {
                attributes 'FMLAT': at.name
            }
            attributes([
                    'Specification-Title'     : 'Forge Minecraft Mod',
                    'Specification-Vendor'    : 'Minecraft Forge',
                    'Specification-Version'   : '1',
                    'Implementation-Title'    : project.name,
                    'Implementation-Version'  : project.version,
                    'Implementation-Vendor'   : 'necauqua',
                    'Implementation-Timestamp': new Date().format('yyyy-MM-dd\'T\'HH:mm:ssZ')
            ])
            if (nmod.mixin) {
                attributes 'MixinConfigs' : (["${project.name.toLowerCase()}.mixins.json"] + nmod.extraMixinConfigs).join(',')
            }
        }
    }

    tasks.register('sourcesJar', Jar) {
        archiveClassifier.set('src')
        from sourceSets.main.allJava
        if (api) {
            from api.allJava
        }
    }

    artifacts.archives sourcesJar

    if (api) {
        tasks.register('javadocs', Javadoc) {
            classpath = sourceSets.main.compileClasspath
            source = api.java
            options.addStringOption('Xdoclint:none', '-quiet')
        }

        tasks.register('javadocJar', Jar) {
            dependsOn tasks.javadocs
            archiveClassifier.set('javadoc')
            from javadoc.destinationDir
        }

        tasks.register('apiJar', Jar) {
            archiveClassifier.set('api')
            from api.output
        }

        artifacts {
            archives javadocJar
            archives apiJar
        }
    }

    tasks.register('signJar', SignJar) {
        dependsOn tasks.reobfJar
        onlyIf { project.hasProperty('keyStore') }
        // gradle executes the whole task body (well it cant stop execution after onlyIf{} call)
        // and then flips out on project.keyStore if it has no keyStore
        if (!project.hasProperty('keyStore')) {
            return
        }
        keyStore = project.keyStore
        alias = project.keyStoreAlias
        storePass = project.keyStorePass
        keyPass = project.keyStoreKeyPass
        inputFile = jar.archiveFile
        outputFile = jar.archiveFile
    }

    def dryRun = System.getenv('DRY_RUN') == "true"

    def isGit = git(['describe', '--first-parent', '--exact-match']).isEmpty()
    def isBeta = project.version.contains('-beta') || project.version.contains('-rc')
    def publishType = isGit ? 'alpha' : isBeta ? 'beta' : 'release'

    def changelogText = 'No changelog'
    try {
        changelogText = file('last-changelog.md').text
    } catch (FileNotFoundException ignored) {
        if (System.getenv("CI") == null) {
            // omegalul
            // this is obviously for rare cases when I'm publishing from my machine
            // and don't have the last-changelog.md in place, as it happens within CI
            def common = [
                    "docker", "run", "--rm",
                    "--workdir", "/workdir",
                    "-v", "${project.rootDir}:/workdir",
                    "-v", "/tmp:/tmp",
                    "ghcr.io/necauqua/changelogs:v1"]
            (common + ["extract", "/tmp/changelog.json", ""])
                    .execute()
                    .waitFor()
            (common + ["render", "/tmp/changelog.json", "", "", "", "/tmp/last-changelog.md", "true", ""])
                    .execute()
                    .waitFor()
            changelogText = file('/tmp/last-changelog.md').text
        }
    }

    if (nmod.curseID && project.hasProperty('curseApiKey')) {
        curseforge {
            apiKey = project.curseApiKey
            project {
                id = nmod.curseID
                changelog = changelogText
                changelogType = 'markdown'
                releaseType = publishType
                nmod.mcversions.each { addGameVersion(it) }
                mainArtifact(jar)
            }
            if (dryRun) {
                curseGradleOptions.debug = true
            }
        }
        tasks.named('curseforge').get().group = 'publishing'
    }

    if (nmod.modrinthID && project.hasProperty('modrinthToken')) {
        modrinth {
            projectId.set(nmod.modrinthID)
            token.set(project.modrinthToken as String)
            changelog.set(changelogText)
            uploadFile.set(jar)
            versionType.set(publishType)
            gameVersions.set(nmod.mcversions)
            if (dryRun) {
                debugMode.set(true)
            }
        }
    }

    // fixup the warning:
    // Task ':publish..' uses this output of task ':signJar' without declaring an explicit or implicit dependency.
    afterEvaluate {
        tasks.named('generateMetadataFileForMavenPublication') {
            dependsOn 'signJar'
        }
    }

    publishing {
        publications {
            maven(MavenPublication) {
                groupId = project.group
                artifactId = project.archivesBaseName.toLowerCase()
                version = project.version

                // fix for https://github.com/MinecraftForge/ForgeGradle/issues/584
                // just removes the whole deps node, good that I don't have any (yet)
                pom.withXml {
                    asNode().remove(asNode().dependencies)
                }

                from components.java

                artifact sourcesJar

                if (api) {
                    artifact apiJar
                    artifact javadocJar
                }

                pom {
                    name = project.name
                    developers {
                        developer {
                            id = 'necauqua'
                            name = 'Anton Bulakh'
                            email = 'self@necauqua.dev'
                        }
                    }
                    if (nmod.description) {
                        description = nmod.description
                    }
                    if (nmod.license) {
                        licenses {
                            license {
                                name = nmod.license
                                if (nmod.licenseUrl) {
                                    url = nmod.licenseUrl
                                }
                            }
                        }
                    }
                    if (nmod.githubRepo) {
                        url = "https://github.com/necauqua/${nmod.githubRepo}#readme"
                        scm {
                            url = "https://github.com/necauqua/${nmod.githubRepo}"
                            connection = developerConnection = "scm:git:https://github.com/necauqua/${nmod.githubRepo}"
                        }
                        if (project.hasProperty('githubToken')) {
                            def get = { String url ->
                                def conn = new URL(url).openConnection()
                                conn.setRequestProperty('Authorization', "token ${project.githubToken}")
                                return new JsonSlurper().parse(conn.getInputStream())
                            }
                            def data = get("https://api.github.com/repos/necauqua/${nmod.githubRepo}")
                            if (!nmod.description) {
                                description = data.description
                            }
                            if (!nmod.license && data.license) {
                                licenses {
                                    license {
                                        name = data.license.name
                                        url = get(data.license.url).html_url
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        repositories {
            if (dryRun) {
                // we don't verify the maven.pass (github token would've already failed)
                // but eh I didn't find a way for a proper dry-run of maven-publish
                return
            }
            if (project.hasProperty('githubToken') && nmod.githubRepo) {
                maven {
                    credentials {
                        username 'necauqua'
                        password project.githubToken
                    }
                    name = 'GitHubPackages'
                    url = "https://maven.pkg.github.com/necauqua/${nmod.githubRepo}"
                }
            }
            if (project.hasProperty('maven.user') && project.hasProperty('maven.pass')) {
                maven {
                    name = 'necauqua'
                    url = 'https://maven.necauqua.dev/'
                    credentials {
                        username project['maven.user']
                        password project['maven.pass']
                    }
                }
            }
        }
    }
}
