package dev.necauqua.nmod

import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project

class Nmod {
    String curseID = null
    String modrinthID = null
    String mcversion = null
    List<String> mcversions = null
    String forge = null
    String mixin = null
    def mappings = null // can be string or map
    String coremod = null
    String description = null
    String license = null
    String licenseUrl = null

    String githubRepo = null

    int javaVersion = 8
    List<String> extraMixinConfigs = []
}

@SuppressWarnings('unused')
class NecauquaModPlugin implements Plugin<Project> {

    static def check(Nmod nmod, String name) {
        if (!nmod[name]) {
            throw new IllegalStateException("nmod.$name is required but was not set")
        }
    }

    static List<String> launchermeta = new JsonSlurper()
            .parse(new URL('https://launchermeta.mojang.com/mc/game/version_manifest.json'))
            .versions
            .findAll { it.type == 'release' }
            .collect { it.id }

    // some really dumb matching's happening here
    static List<String> getMcVersions(String mcversion) {
        def versions
        if (mcversion.endsWith(".*")) {
            def prefix = mcversion.substring(0, mcversion.length() - 2)
            versions = launchermeta.findAll { it.startsWith(prefix) }
        } else {
            versions = launchermeta.contains(mcversion) ? [mcversion] : []
        }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException('nmod.mcversion didn\'t match any minecraft versions')
        }
        return versions
    }

    static def normalizeMcversions(Nmod nmod) {
        if (nmod.mcversion == null) {
            if (nmod.mcversions == null) {
                def mcversion = nmod.forge.split('-').first()
                // nmod.forge must be a valid forge version (the dependency will fail later)
                // so no mcversion validation needed here
                nmod.mcversion = mcversion
                nmod.mcversions = [mcversion]
            } else {
                for (mcversion in nmod.mcversions) {
                    if (!launchermeta.contains(mcversion)) {
                        throw new IllegalArgumentException("nmod.mcversions contained an invalid minecraft version: $mcversion")
                    }
                }
                nmod.mcversion = nmod.mcversions.last() // will throw if empty
            }
        } else if (nmod.mcversions == null) {
            nmod.mcversions = getMcVersions(nmod.mcversion)
            nmod.mcversion = nmod.mcversions.first() // launchermeta has newer versions first
        } else {
            throw new IllegalStateException('Cannot set both nmod.mcversion and nmod.mcversions')
        }
    }

    @Override
    void apply(Project project) {
        project.with(DefaultConfig.before)

        def nmod = project.extensions.create("nmod", Nmod)

        // set it to project name by default, so that
        // explicitly setting it to null disables github stuff
        nmod.githubRepo = project.name.toLowerCase()

        // and then we inject it straight into project to bypass default
        // declarative extension application - so it just the closure below
        // where we have all the control
        project.metaClass.nmod = { Closure closure ->
            nmod.with(closure) // do the application

            check(nmod, 'forge')
            check(nmod, 'mappings')

            normalizeMcversions(nmod)

            project.with(DefaultConfig.configure) // and then add all extra declarative configuration
        }
    }
}
