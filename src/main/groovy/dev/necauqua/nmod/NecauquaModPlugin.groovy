package dev.necauqua.nmod


import org.gradle.api.Plugin
import org.gradle.api.Project

class Nmod {
    String curseID = null
    String version = null
    String forge = null
    String mappings = null
    String coremod = null
    String description = null
    String license = null
    String licenseUrl = null

    String githubRepo = null
}

@SuppressWarnings('unused')
class NecauquaModPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.with(new DefaultConfig().apply)

        def nmod = project.extensions.create("nmod", Nmod)

        // set it to project name by default, so that
        // explicitly setting it to null disables github stuff
        nmod.githubRepo = project.name

        // and then we inject it straight into project to bypass default
        // declarative extension application - so it just the closure below
        // where we have all the control
        project.metaClass.nmod = { closure ->
            nmod.with(closure) // do the application
            project.with(new DefaultConfig().configure) // and then add all extra declarative configuration
        }
    }
}
