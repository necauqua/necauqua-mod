package dev.necauqua.nmod

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import groovy.transform.Immutable
import net.minecraftforge.gradle.common.task.SignJar
import org.gradle.api.tasks.bundling.Jar

// unsurprisingly, groovy continues to show how shit it is
class McVersions {
    static def all = new JsonSlurper()
            .parse(new URL('https://launchermeta.mojang.com/mc/game/version_manifest.json'))
            .versions
            .findAll { it.type == 'release' }
            .collect { it.id.count('.') == 1 ? it.id + '.0' : it.id }

    static def get(modmc) {
        return all
                .findAll { it.startsWith(modmc) }
                .collect { it.endsWith('.0') ? it.substring(0, it.length() - 2) : it }
    }
}

static List<String> git(_args) {
    def args = []
    args += 'git'
    args += _args
    def res = args.execute().text.split('\n').toList()
    return res != [''] ? res : []
}

static Map<String, List<String>> gitLog(String startRef = null, String endRef = null) {
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
        } else if (line.startsWith("  - ")) {
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

static Tag makeUnreleasedPseudoTag(project, log) {
    def (lastCommit, lastCommitDate) = git(['log', '-1', '--format=%h|%ct'])[0].split('\\|').toList()
    return new Tag("v${project.version}-git-$lastCommit", lastCommitDate.toInteger(), log)
}

static List<Tag> getChangelog(project, String rootCommit = null) {
    def cmd = ['for-each-ref', '--sort=-creatordate', '--format', '%(refname)|%(creatordate:unix)', 'refs/tags']
    if (rootCommit) {
        cmd += ['--contains', rootCommit]
    }
    def tags = git(cmd)

    // no tags -> everything (that we have) is unreleased
    if (tags.isEmpty()) {
        def unreleasedLog = gitLog('HEAD', rootCommit)
        return unreleasedLog.isEmpty() ?
                [] :
                [makeUnreleasedPseudoTag(project, unreleasedLog)]
    }

    def (lastTag, lastDate) = tags.removeAt(0).split('\\|')

    tags += rootCommit ? (rootCommit + '|') : null

    def releases = []

    def unreleasedLog = gitLog('HEAD', lastTag)
    if (!unreleasedLog.isEmpty()) {
        releases += makeUnreleasedPseudoTag(project, unreleasedLog)
    }

    for (t in tags) {
        def (tag, date) = t ? t.split('\\|').toList() : [null, '']

        def releaseLog = gitLog(lastTag, tag)
        if (!releaseLog.isEmpty()) {
            releases += new Tag(lastTag.substring(10), lastDate.toInteger(), releaseLog)
            lastTag = tag
            lastDate = date
        }
    }
    return releases
}

static def section(Map<String, List<String>> log) {
    def s = new StringBuilder()
    for (key in log.keySet().toSorted()) {
        s <<  "### ${key.capitalize()}\n"
        for (item in log[key]) {
            s << "  - $item\n"
        }
    }
    s << '\n'
    return s.toString()
}

static def makeMarkdown(List<Tag> changelog, String template=null) {
    def s = new StringBuilder()
    for (release in changelog) {
        s << "## [${release.name.substring(1)}] ${new Date(release.date * 1000L).format('1yyyy-MM-dd')}\n"
        s << section(release.log)
    }
    s.setLength(s.length() - 1) // strip last newline
    return String.format(template ? new File(template).text : '%s', s.toString())
}

static def makeForgeUpdates(List<Tag> changelog, String template=null) {
    def result = template ? new JsonSlurper().parse(new File(template)) : [:]

    def recomended = [:]

    for (release in changelog) {
        def version = release.name.substring(1)
        for (mc in McVersions.get(version.split("-")[0])) {
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

    def latest = changelog.find()
    if (latest?.name?.contains('git')) {
        def version = latest.name.substring(1)
        for (mc in McVersions.get(version.split('-')[0])) {
            promos["${mc}-latest"] = version
        }
    }
    return JsonOutput.toJson(result)
}

@Field
def configure = {
    apply plugin: 'java'
    apply plugin: 'idea'
    apply plugin: 'net.minecraftforge.gradle'
    apply plugin: 'com.matthewprenger.cursegradle'
    apply plugin: 'co.riiid.gradle'

    version = nmod.version
    group = 'dev.necauqua.mods'

    def modversion = nmod.version
    def modmc = modversion.split('-')[0]
    def forgemc = nmod.forge.split('-')[0]
    def mcversions = McVersions.get(modmc)

    if (mcversions.isEmpty()) {
        throw new IllegalArgumentException("Mod version didn't match any minecraft versions!")
    }

    def tags = getChangelog(project)
    def latestChangelog = section(tags[0].log)
    latestChangelog = latestChangelog.substring(0, latestChangelog.length() - 1)

    idea.project.jdkName =
            sourceCompatibility =
                    targetCompatibility =
                            compileJava.sourceCompatibility =
                                    compileJava.targetCompatibility = '1.8' // omfg

    idea.module.inheritOutputDirs = true

    def ats = fileTree('src/main/resources')
            .matching { include '*_at.cfg' }
            .asList()
    if (ats.size() > 1) {
        throw new IllegalStateException("Found more than one Access Transformer! $it")
    }
    def at = ats.find()

    minecraft {
        mappings channel: 'snapshot', version: nmod.mappings

        accessTransformer = at

        def replacements = [
                '@VERSION@'         : modversion,
                '@MC_VERSION_RANGE@': mcversions.size() == 1 ?
                        "[${mcversions.first()}]" :
                        "[${mcversions.last()},${mcversions.first()}]",
                // here we strip the .minor.patch-detail suffix (meh, shut up, I love regex)
                '@API_VERSION@'     : modversion.replaceAll('(?:.*?-)(.*?)\\.\\d+\\.\\d+(?:-.*?)?$', '$1'),
        ]

        runs {
            client {
                workingDirectory file('build/run')
                args += [
                        '--username', 'necauqua',
                        '--uuid', 'f98e9365-2c52-48c5-8647-6662f70b7e3d'
                ]
                tokens replacements
                property 'forge.logging.markers', 'SCAN,REGISTRIES,REGISTRYDUMP'
                property 'forge.logging.console.level', 'debug'
                if (nmod.coremod) {
                    property 'fml.coreMods.load', nmod.coremod
                }
            }
            client2 {
                workingDirectory file('build/run')
                args += [
                        '--username', 'necauqua2',
                ]
                main 'net.minecraftforge.legacydev.MainClient'
                tokens replacements
                property 'forge.logging.markers', 'SCAN,REGISTRIES,REGISTRYDUMP'
                property 'forge.logging.console.level', 'debug'
                if (nmod.coremod) {
                    property 'fml.coreMods.load', nmod.coremod
                }
            }
            server {
                tokens replacements
                workingDirectory file('build/server')
                property 'forge.logging.markers', 'SCAN,REGISTRIES,REGISTRYDUMP'
                property 'forge.logging.console.level', 'debug'
            }
        }
    }

    dependencies {
        minecraft "net.minecraftforge:forge:${nmod.forge}"
    }

    processResources {
        inputs.property 'version', nmod.version
        inputs.property 'mcversion', forgemc
        from(sourceSets.main.resources.srcDirs) {
            include 'mcmod.info'
            expand 'version': nmod.version, 'mcversion': forgemc
        }
        from(sourceSets.main.resources.srcDirs) {
            exclude 'mcmod.info'
        }
        rename '(.+_at.cfg)', 'META-INF/$1'
    }

    jar {
        finalizedBy 'reobfJar'
        manifest {
            if (nmod.coremod) {
                attributes 'FMLCorePlugin': nmod.coremod, 'FMLCorePluginContainsFMLMod': 'true'
            }
            if (at) {
                attributes 'FMLAT': at.name
            }
        }
        from 'LICENSE'
    }

    // hack for resources to be actually loaded with new forgegradle in 1.12
    // I guess new fg isn't very suited/tested for 1.12
    afterEvaluate {
        tasks.findByName('prepareRuns')?.doLast {
            copy {
                from file(buildDir, 'resources/main')
                into file(buildDir, 'classes/java/main')
            }
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

    task('deobfJar', type: Jar) {
        from sourceSets.main.output
        manifest = jar.manifest
        classifier = 'deobf'
    }

    artifacts.archives deobfJar

    build.dependsOn signJar

    task('publish') {
        group = 'publishing'
    }

    if (nmod.curseID && hasProperty('curseApiKey')) {
        curseforge {
            apiKey = curseApiKey
            options {
                debug = true
            }
            project {
                id = nmod.curseID
                changelog = latestChangelog
                changelogType = 'markdown'
                releaseType = modversion.contains('-git') ?
                        'alpha' :
                        modversion.contains('-beta') || modversion.contains('-rc') ?
                                'beta' :
                                'release'
                mcversions.each { addGameVersion(it) }
                mainArtifact(jar)
                addArtifact(deobfJar)
                if (nmod.apiPath) {
                    addArtifact(apiJar)
                }
            }
        }
        tasks['curseforge'].group = 'publishing'
        afterEvaluate {
            tasks["curseforge${nmod.curseID}"].group = null
        }
        publish.dependsOn curseforge
    }

    if (hasProperty('githubToken')) {
        github {
            token = project.githubToken
            owner = 'necauqua'
            repo = nmod.githubRepo ?: project.name
            tagName = "v${project.version}"
            name = tagName
            body = latestChangelog
            assets = [jar.archivePath]
            prerelease = modversion.contains('-beta') || modversion.contains('-rc')
        }
        githubRelease.group = 'publishing'
        githubRelease.dependsOn build
        publish.dependsOn githubRelease
    }

    defaultTasks 'clean', 'setupCiWorkspace', 'build'

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
}
