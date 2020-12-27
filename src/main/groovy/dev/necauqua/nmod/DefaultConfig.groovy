package dev.necauqua.nmod

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import groovy.transform.Immutable
import net.minecraftforge.gradle.common.task.SignJar
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc

// unsurprisingly, groovy continues to show how shit it is
class McVersions {
    static def all = new JsonSlurper()
            .parse(new URL('https://launchermeta.mojang.com/mc/game/version_manifest.json'))
            .versions
            .findAll { it.type == 'release' }
            .collect { it.id.count('.') == 1 ? it.id + '.0' : it.id }

    static def get(modmc) {
        def versions = all
                .findAll { it.startsWith(modmc) }
                .collect { it.endsWith('.0') ? it.substring(0, it.length() - 2) : it }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException('Mod version didn\'t match any minecraft versions!')
        }
        return versions
    }
}

static List<String> git(args) {
    def res = ['git', *args].execute().text.split('\n').toList()
    return res != [''] ? res : []
}

static Map<String, List<String>> parseLog(String startRef = null, String endRef = null) {
    def cmd = ['log', '--reverse', '--format=%b']
    if (startRef) {
        cmd += startRef
    }
    if (endRef) {
        cmd += ('^' + endRef)
    }
    Map<String, List<String>> log = [:]
    List<String> current = []
    for (line in git(cmd)) {
        if (line.endsWith(':')) {
            line = line.substring(0, line.length() - 1).toLowerCase()
            current = log.computeIfAbsent(line) { [] }
        } else if (line.startsWith('  - ')) {
            current.add(line.substring(4))
        }
    }
    return log
}

@Immutable
class Tag {
    String name
    int date
    Map<String, List<String>> log
}

static List<Tag> getUnreleasedChangelog(version, String rootCommit = null) {
    def unreleasedLog = parseLog('HEAD', rootCommit)
    if (!unreleasedLog.isEmpty()) {
        def (commit, date) = git(['log', '-1', '--format=%h|%ct'])[0].split('\\|').toList()
        return [new Tag("v${version}-git-$commit", date.toInteger(), unreleasedLog)]
    }
    def cmd = ['log', '--format=%h|%ct', 'HEAD']
    if (rootCommit) {
        cmd += '^' + rootCommit
    }
    def commitsSinceLastTag = git(cmd)
    if (!commitsSinceLastTag.isEmpty()) {
        def (commit, date) = commitsSinceLastTag[0].split('\\|').toList()
        return [new Tag("v${version}-git-$commit", date.toInteger(), [:])]
    }
    return []
}

static List<Tag> getChangelog(version, String rootCommit = null) {
    def cmd = ['for-each-ref', '--sort=-creatordate', '--format', '%(refname)|%(creatordate:unix)', 'refs/tags', '--merged']
    if (rootCommit) {
        cmd += ['--contains', rootCommit]
    }
    def tags = git(cmd)

    // no tags -> everything (that we have) is unreleased
    if (tags.isEmpty()) {
        return getUnreleasedChangelog(version, rootCommit)
    }

    def (lastTag, lastDate) = tags.removeAt(0).split('\\|')

    tags += rootCommit ? (rootCommit + '|') : null

    def releases = getUnreleasedChangelog(version, lastTag)

    for (t in tags) {
        def (tag, date) = t ? t.split('\\|').toList() : [null, '']

        def releaseLog = parseLog(lastTag, tag)
        if (!releaseLog.isEmpty()) {
            releases += new Tag(lastTag.substring(10), lastDate.toInteger(), releaseLog)
            lastTag = tag
            lastDate = date
        }
    }
    return releases
}

static def section(Map<String, List<String>> log) {
    if (log.isEmpty()) {
        return "### No changelog from last tag up to this commit\n\n"
    }
    def s = new StringBuilder()
    for (key in log.keySet().toSorted()) {
        s << "### ${key.capitalize()}\n"
        for (item in log[key]) {
            s << "  - $item\n"
        }
    }
    s << '\n'
    return s.toString()
}

static def makeMarkdown(List<Tag> changelog, String template = null) {
    def s = new StringBuilder()
    for (release in changelog) {
        s << '## [' << release.name.substring(1) << '] '
        s << new Date(release.date * 1000L).format('1yyyy-MM-dd')
        s << '\n' << section(release.log)
    }
    s.setLength(s.length() - 1) // strip last section newline
    return (template ? new File(template).text : '%s').format(s.toString())
}

static def makeForgeUpdates(List<Tag> changelog, String template = null) {
    def result = template ? new JsonSlurper().parse(new File(template)) : [:]

    def recomended = [:]

    for (release in changelog) {
        def version = release.name.substring(1)
        for (mc in McVersions.get(version.split('-')[0])) {
            if (!release.name.contains('git')) {
                recomended.computeIfAbsent(mc) { version }
            }
            def s = section(release.log)
            result.computeIfAbsent(mc, { [:] })[version] = s.substring(0, s.length() - 2)
        }
    }

    def promos = result.computeIfAbsent('promos') { [:] }

    recomended.forEach { mc, mod ->
        promos["${mc}-recommended"] = mod
        promos["${mc}-latest"] = mod
    }

    def latest = changelog[0]?.name
    if (latest?.contains('git')) {
        def version = latest.substring(1)
        for (mc in McVersions.get(version.split('-')[0])) {
            promos["${mc}-latest"] = version
        }
    }
    return JsonOutput.toJson(result)
}

// the lowest possible indentation level for pseudo-gradle config
// without evaluating groovy from a file
@Field
def apply = {
    apply plugin: 'java'
    apply plugin: 'maven-publish'
    apply plugin: 'idea'
    apply plugin: 'net.minecraftforge.gradle'
    apply plugin: 'com.matthewprenger.cursegradle'
    apply plugin: 'co.riiid.gradle'
}

@Field
def configure = {

    def tags = getChangelog(nmod.version)

    task('generateChangelog') {
        def template = project.hasProperty('changelogTemplate') ? project.changelogTemplate : null
        doLast {
            print(makeMarkdown(tags, template))
        }
    }
    task('generateForgeUpdates') {
        def template = project.hasProperty('updatesTemplate') ? project.updatesTemplate : null
        doLast {
            print(makeForgeUpdates(tags, template))
        }
    }

    def latestChangelog = tags ? section(tags[0].log) : '\n'
    latestChangelog = latestChangelog.substring(0, latestChangelog.length() - 1)

    version = tags ? tags[0].name.substring(1) : nmod.version
    group = 'dev.necauqua.mods'

    def forgemc = nmod.forge.split('-')[0]
    def mcversions = McVersions.get(version.split('-')[0])

    idea.project.jdkName =
            sourceCompatibility =
                    targetCompatibility =
                            compileJava.sourceCompatibility =
                                    compileJava.targetCompatibility = '1.8' // omfg

    idea.module.inheritOutputDirs = true

    def api = sourceSets.findByName('api')

    if (api) {
        sourceSets.main {
            compileClasspath += api.output
            runtimeClasspath += api.output
        }
        configurations {
            apiCompile.extendsFrom(compile)
        }
    }

    def at = file('src/main/resources/accesstransformer.cfg')

    minecraft {
        mappings channel: 'snapshot', version: nmod.mappings

        if (at.exists()) {
            accessTransformer = at
        }

        runs {
            client {
                workingDirectory file('build/run')
                args += [
                        '--username', 'necauqua',
                        '--uuid', 'f98e9365-2c52-48c5-8647-6662f70b7e3d'
                ]
                property 'forge.logging.console.level', 'debug'
                if (nmod.coremod) {
                    property 'fml.coreMods.load', nmod.coremod
                }

                mods.create(project.name) {
                    source sourceSets.main
                    if (api) {
                        source api
                    }
                }
            }
            client2 {
                workingDirectory file('build/run')
                args += [
                        '--username', 'necauqua2',
                ]
                property 'forge.logging.console.level', 'debug'
                if (nmod.coremod) {
                    property 'fml.coreMods.load', nmod.coremod
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

                mods.create(project.name) {
                    source sourceSets.main
                    if (api) {
                        source api
                    }
                }
            }
        }
    }

    dependencies {
        minecraft "net.minecraftforge:forge:${nmod.forge}"
    }

    // keep most of source/resource preprocessing for 1.12 compat
    task('processSources', type: Copy) {
        def processedFolder = buildDir.toPath().resolve('processSources')

        inputs.property('version', project.version)
        from(compileJava.source)
        into(processedFolder)

        compileJava.source = processedFolder

        filter(ReplaceTokens, tokens: [
                VERSION         : project.version,
                MC_VERSION_RANGE: (mcversions.size() == 1 ?
                        "[${mcversions.first()}]" :
                        "[${mcversions.last()},${mcversions.first()}]").toString(),
                // here we strip the .minor.patch-detail suffix (meh, shut up, I love regex)
                API_VERSION     : project.version.replaceAll('(?:.*?-)(.*?)\\.\\d+\\.\\d+(?:-.*?)?$', '$1'),
        ])
        // ensure there is stuff in compileJava.source in case there are other preprocessing tasks
        mustRunAfter(compileJava.dependsOn.findAll { it instanceof Copy })
    }
    compileJava.dependsOn += processSources

    processResources {
        inputs.property('version', project.version)
        from(sourceSets.main.resources.srcDirs) {
            include 'mcmod.info'
            expand 'version': project.version, 'mcversion': forgemc
        }
        from(sourceSets.main.resources.srcDirs) {
            exclude 'mcmod.info'
        }
        if (api) {
            from(api.resources.srcDirs)
        }
        rename '(accesstransformer\\.cfg|mods\\.toml|coremods\\.json)', 'META-INF/$1'
    }

    jar {
        finalizedBy 'reobfJar'
        finalizedBy 'signJar'

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
        }
        if (api) {
            from api.output.classesDirs
        }
        from 'LICENSE'
    }

    task('sourcesJar', type: Jar) {
        classifier = 'src'
        from sourceSets.main.allJava
        if (sourceSets.api) {
            from sourceSets.api.allJava
        }
    }

    artifacts.archives sourcesJar

    if (api) {
        task('javadocs', type: Javadoc) {
            classpath = sourceSets.main.compileClasspath
            source = api.java
            options.addStringOption('Xdoclint:none', '-quiet')
        }

        task('javadocJar', type: Jar, dependsOn: 'javadocs') {
            classifier = 'javadoc'
            from javadoc.destinationDir
        }

        task('apiJar', type: Jar) {
            from api.output
            classifier = 'api'
        }

        artifacts {
            archives javadocJar
            archives apiJar
        }
    }

    task('signJar', type: SignJar, dependsOn: 'reobfJar') {
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
        inputFile = jar.archivePath
        outputFile = jar.archivePath
    }

    def isBeta = project.version.contains('-beta') || project.version.contains('-rc')
    def isGit = project.version.contains('-git')

    if (nmod.curseID && project.hasProperty('curseApiKey')) {
        curseforge {
            apiKey = project.curseApiKey
            project {
                id = nmod.curseID
                changelog = latestChangelog
                changelogType = 'markdown'
                releaseType = isGit ? 'alpha' : isBeta ? 'beta' : 'release'
                mcversions.each { addGameVersion(it) }
                mainArtifact(jar)
            }
        }
        tasks['curseforge'].group = 'publishing'
    }

    if (nmod.githubRepo && project.hasProperty('githubToken') && !project.version.contains('git')) {
        github {
            token = project.githubToken
            owner = 'necauqua'
            repo = nmod.githubRepo
            tagName = "v${project.version}"
            name = tagName
            body = latestChangelog
            assets = [jar.archivePath]
            prerelease = isBeta || isGit
        }
        tasks['githubRelease'].group = 'publishing'
        tasks['githubRelease'].dependsOn += 'build'
    }

    publishing {
        publications {
            maven(MavenPublication) {
                groupId = project.group
                artifactId = project.archivesBaseName
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
                            def get = { url ->
                                def conn = new URL(url).openConnection()
                                conn.setRequestProperty('Authorization', "token ${project.githubToken}")
                                return new JsonSlurper().parse(conn.getInputStream())
                            }
                            def data = get("https://api.github.com/repos/necauqua/${nmod.githubRepo}")
                            if (!nmod.description) {
                                description = data.description
                            }
                            if (!nmod.license) {
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
