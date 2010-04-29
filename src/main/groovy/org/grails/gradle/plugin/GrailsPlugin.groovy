package org.grails.gradle.plugin

import grails.util.GrailsNameUtils

import org.codehaus.groovy.grails.cli.support.GrailsRootLoader
import org.codehaus.groovy.grails.cli.support.GrailsBuildHelper
import org.gradle.api.Plugin
import org.gradle.api.Project

class GrailsPlugin implements Plugin<Project> {
    /**
     * These Grails commands require the project's runtime dependencies
     * in the Grails root loader because they are not using the runtime
     * classpath (as they are supposed to).
     */
    static final RUNTIME_CLASSPATH_COMMANDS = ["RunApp", "TestApp"] as Set

    void apply(Project project) {
        project.configurations {
            grails_bootstrap
            grails_bootstrap_with_project_runtime
            compile
            runtime.extendsFrom compile
            test.extendsFrom compile
        }

        // Provide a task that allows the user to create a fresh Grails
        // project from a basic Gradle build file.
        project.task("init") << {
            // First make sure that a project version has been configured.
            if (project.version == "unspecified") {
                throw new RuntimeException("[GrailsPlugin] Build file must specify a 'version' property.")
            }

            // Don't create a new project if one already exists.
            if (project.file("application.properties").exists() && project.file("grails-app").exists()) {
                logger.warn "Grails project already exists - SKIPPING"
                return
            }

            // The project name comes from the name of the project
            // directory, but this can be overridden by an argument.
            def projName = project.hasProperty("args") ? project.args : project.projectDir.name
            runGrails("CreateApp", project, "--inplace --appVersion=" + project.version + " " + projName)
        }

        // Most people are used to a "test" target or task, but Grails
        // has "test-app". So we hard-code a "test" task.
        project.task(["overwrite": true], "test") << {
            runGrailsWithProps("TestApp", project)
        }

        // Gradle's Java plugin provides an "assemble" task. We map that
        // to the War command here.
        project.task(["overwrite": true], "assemble") << {
            runGrailsWithProps("War", project)
        }

        // Convert any task executed from the command line into the
        // Grails equivalent command.
        project.tasks.addRule("Grails command") {String name ->
            // Gradle has a tendency to want to create 'args' and 'env'
            // tasks, so block it from doing so.
            if (name == "args" || name == "env") return

            // Add a task for the given Grails command.
            project.task(name) << {
                runGrailsWithProps(GrailsNameUtils.getNameFromScript(name), project)
            }
        }

        // A Grails project has a rather distinctive directory structure,
        // but it is at heart a straight WAR project. This sets up the
        // source directories.
        project.with {
            sourceSets {
                main {
                    groovy {
                        def grailsAppDirs = file("grails-app").listFiles({f -> f.directory } as FileFilter)
                        srcDirs grailsAppDirs, "src/groovy", "src/java"
                    }
                }
            }
        }

    }

    /**
     * Launches Grails and executes the given command. Any command
     * arguments or environment are picked up from the "args" and "env"
     * project properties.
     * @param cmd The Grails command to execute. Note that this should
     * actually be the name of the script, not the command name. So
     * "RunApp" rather than "run-app".
     * @param project The Gradle project to run Grails in.
     */
    private void runGrailsWithProps(String cmd, Project project) {
        def cmdArgs = project.hasProperty("args") ? project.args : null
        def cmdEnv = project.hasProperty("env") ? project.env : null
        runGrails(cmd, project, cmdArgs, cmdEnv)
    }

    /**
     * Launches Grails and executes the given command.
     * @param cmd The Grails command to execute. Note that this should
     * actually be the name of the script, not the command name. So
     * "RunApp" rather than "run-app".
     * @param project The Gradle project to run Grails in.
     * @param args (Optional) Any arguments (as a single, space-separated
     * string) that you want to pass to the Grails command. Defaults to
     * <code>null</code> (no args).
     * @param env (Optional) The environment to run the Grails command
     * in. Defaults to <code>null</code>, which means that the command
     * uses whatever its default environment is.
     */
    private void runGrails(String cmd, Project project, String args = null, String env = null) {
        setupConfigurationsOnce(project)

        // Add the "tools.jar" to the classpath so that the Grails
        // scripts can run native2ascii. First assume that "java.home"
        // points to a JRE within a JDK.
        def javaHome = System.getProperty("java.home");
        def toolsJar = new File(javaHome, "../lib/tools.jar");
        if (!toolsJar.exists()) {
            // The "tools.jar" cannot be found with that path, so
            // now try with the assumption that "java.home" points
            // to a JDK.
            toolsJar = new File(javaHome, "tools.jar");
        }

        // There is no tools.jar, so native2ascii may not work. Note
        // that on Mac OS X, native2ascii is already on the classpath.
        if (!toolsJar.exists() && !System.getProperty('os.name') == 'Mac OS X') {
            project.logger.warn "[GrailsPlugin] Cannot find tools.jar in JAVA_HOME, so native2ascii may not work."
        }

        // Get the 'grails_bootstrap' configuration as a list of URLs
        // and add tools.jar to it.
        def configurationToUse = RUNTIME_CLASSPATH_COMMANDS.contains(cmd) ?
            project.configurations.grails_bootstrap_with_project_runtime : project.configurations.grails_bootstrap
        def classpath = configurationToUse.files.collect { it.toURI().toURL() }
        classpath << toolsJar.toURI().toURL()

        // So we know what files are on what classpaths.
        project.logger.info "Classpath for Grails root loader:\n  ${classpath.join('\n  ')}"
        project.logger.info "Compile classpath:\n  ${project.configurations.compile.files.join('\n  ')}"
        project.logger.info "Test classpath:\n  ${project.configurations.test.files.join('\n  ')}"
        project.logger.info "Runtime classpath:\n  ${project.configurations.runtime.files.join('\n  ')}"
        project.logger.info "Command to be executed $cmd with $args and env $env"

        // Finally, kick off Grails with the given command. GrailsBuildHelper
        // allows us to easily configure the Grails build settings and the
        // various lists of dependencies. It also ensures that the Grails
        // build system runs in its own class loader so that Gradle's
        // dependencies don't conflict with it.
        def rootLoader = new GrailsRootLoader(classpath as URL[], ClassLoader.systemClassLoader)
        def grailsHelper = new GrailsBuildHelper(rootLoader, null, project.projectDir.absolutePath)
        grailsHelper.compileDependencies = project.configurations.compile.files as List
        grailsHelper.testDependencies = project.configurations.test.files as List
        grailsHelper.runtimeDependencies = project.configurations.runtime.files as List
        grailsHelper.projectWorkDir = project.buildDir
        grailsHelper.classesDir = new File(project.buildDir, "classes")
        grailsHelper.testClassesDir = new File(project.buildDir, "test-classes")
        grailsHelper.resourcesDir = new File(project.buildDir, "resources")
        grailsHelper.projectPluginsDir = new File(project.buildDir, "plugins")

        // Grails 1.2+ only. Previous versions of Grails don't have the
        // 'dependenciesExternallyConfigured' property. Note that this
        // is a HACK because the 'settings' field is private.
        //
        // We can't simply check whether the property exists on the
        // helper because it's the 1.2 version, whereas the project may
        // be using Grails version 1.1. That's why we have to get hold
        // of the actual BuildSettings instance.
        def buildSettings = grailsHelper.settings
        if (buildSettings.metaClass.hasProperty(buildSettings, "dependenciesExternallyConfigured")) {
            grailsHelper.dependenciesExternallyConfigured = true
        }

        // Using a Groovy trick here, because we either want to call
        // the execute() method that takes two arguments, or the one
        // that takes three. So rather than calling those methods
        // explicitly within a condition, we create an argument list
        // and then use the spread operator.
        def methodArgs = [cmd, args]
        if (env) methodArgs << env

        def retval = grailsHelper.execute(* methodArgs)
        if (retval != 0) {
            throw new RuntimeException("[GrailsPlugin] Grails returned non-zero value: " + retval);
        }
    }

    /**
     * Sets up the project configuration dependencies
     * @param project The Gradle project to run Grails in.
     */
    private void setupConfigurationsOnce(Project project) {
        // Only need to set the dependencies up once
        if (project.configurations.grails_bootstrap.allDependencies.size() == 0) {
            // Start by checking that the project has both Grails and a
            // logging implementation as dependencies. Otherwise we fail
            // the build.
            def runtimeDeps = project.configurations.runtime.allDependencies
            def grailsDep = runtimeDeps.find { it.group == 'org.grails' && it.name.startsWith('grails-') }
            if (!grailsDep) {
                throw new RuntimeException("[GrailsPlugin] Your project does not contain any 'grails-*' dependencies in 'compile' or 'runtime'.")
            }

            def loggingDep = runtimeDeps.find { it.group == 'org.slf4j' && it.name.startsWith('slf4j-') }
            if (!loggingDep) {
                throw new RuntimeException("[GrailsPlugin] Your project does not contain an SLF4J logging implementation dependency.")
            }

            // Set up the 'grails_bootstrap' configuration so that it contains
            // all the dependencies required by the Grails build system. This
            // pretty much means everything used by the scripts too.
            // This configuration should happen before we need to call runGrails(),
            // since we may want to assign task dependencies which would then cause
            // an exception about changing configurations in a unresolved state.
            project.logger.info "Using grails version ${grailsDep.version}"
            project.dependencies {
                grails_bootstrap "org.grails:grails-bootstrap:${grailsDep.version}",
                        "org.grails:grails-core:${grailsDep.version}",
                        "org.grails:grails-docs:${grailsDep.version}",
                        "org.grails:grails-resources:${grailsDep.version}",
                        "org.grails:grails-scripts:${grailsDep.version}",
                        ":org.springframework.test:3.0.0.RELEASE",
                        ':ant:1.7.1',
                        ':ant-junit:1.7.1',
                        ':ant-launcher:1.7.1',
                        ':ant-nodeps:1.7.1',
                        ':ant-trax:1.7.1',
                        ':gant_groovy1.7:1.9.2',
                        ':gpars:0.9',
                        ':groovy-all:1.7.2',
                        ':ivy:2.1.0',
                        ':jline:0.9.91',
                        ':serializer:2.7.1',
                        ':servlet-api:2.5',
                        "org.slf4j:slf4j-api:${loggingDep.version}",
                        "org.slf4j:slf4j-log4j12:${loggingDep.version}",
                        ':svnkit:1.3.1'

                // Set up a configuration for commands that require the project's runtime dependencies
                grails_bootstrap_with_project_runtime project.configurations.runtime + project.configurations.grails_bootstrap
            }
        }
    }
}
