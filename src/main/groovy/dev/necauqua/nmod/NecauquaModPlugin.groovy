package dev.necauqua.nmod

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import groovy.transform.Immutable
import net.minecraftforge.gradle.common.task.SignJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar

class Nmod {
    String curseID = null
    String version = null
    String forge = null
    String mappings = null
    String apiPath = null
    String coremod = null
    String githubRepo = null
}

@SuppressWarnings('unused')
class NecauquaModPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def nmod = project.extensions.create("nmod", Nmod)

        // and then we inject it straight into project to bypass default
        // declarative extension application - so it just the closure below
        // where we have all the control
        project.metaClass.nmod = { closure ->
            nmod.with(closure) // do the application
            project.with(new DefaultConfig().configure) // and then add all extra declarative configuration
        }
    }
}
