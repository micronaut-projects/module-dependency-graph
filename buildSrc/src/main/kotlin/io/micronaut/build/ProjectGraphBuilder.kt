package io.micronaut.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.File
import java.util.*
import javax.inject.Inject

@CacheableTask
abstract class ProjectGraphBuilder : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reportsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val projectsExcludedFromPlatform: SetProperty<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun buildGraph() {
        val reportsDir = reportsDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile
        // This algorithm works in 2 steps:
        // 1. In the reports directory, we have a subdirectory for each Micronaut
        // project, which contains a TXT file which name corresponds to the groupID
        // of the project. The file contains the list of group ids of the projects
        // it depends on.
        // 2. We build the dependency graph by reconciling the dependencies of each
        // project with the dependencies of the projects it depends on.
        val projectToDependencies = buildProjectDependenciesMap(reportsDir)
        // Build a first graph will all modules
        generateDependencyGraphImage(projectToDependencies, outputDir, "project-graph")
        // And a second one filtering out "core"
        val filteredProjectToDependencies = projectToDependencies.filterKeys { it != "core" }
            .mapValuesTo(mutableMapOf()) { (_, dependencies) ->
                dependencies.filter { it != "core" }.toMutableSet()
            }
            .toMap()
        generateDependencyGraphImage(filteredProjectToDependencies, outputDir, "project-graph-filtered")
        // Now generate a text file describing in which order we should build the projects
        val buildOrderFile = File(outputDir, "build-order.txt")
        val warnings = mutableSetOf<String>()
        val platformDependencies = "platform".transitiveDeps(projectToDependencies)
        val allModules = projectToDependencies.keys - "platform" - projectsExcludedFromPlatform.getOrElse(Collections.emptySet())
        val missingPlatformDependencies = allModules.filter { it !in platformDependencies }
        if (missingPlatformDependencies.isNotEmpty()) {
            warnings.add("[WARNING] The following modules are not included in the platform: $missingPlatformDependencies")
        }
        buildOrderFile.printWriter(charset("UTF-8")).use { writer ->
            val comparator: (o1: String, o2: String) -> Int = { p1, p2 ->
                val deps1 = p1.transitiveDeps(projectToDependencies)
                val deps2 = p2.transitiveDeps(projectToDependencies)
                if (deps1.contains(p2) && deps2.contains(p1)) {
                    warnings.add("[WARNING] Circular dependency between $p1 and $p2")
                }
                if (deps1.contains(p2)) {
                    1
                } else if (deps2.contains(p1)) {
                    -1
                } else {
                    deps1.size.compareTo(deps2.size)
                }
            }
            val projects = projectToDependencies.keys.sortedWith(comparator)
            warnings.forEach {
                System.err.println(it)
                writer.println(it)
            }
            writer.println("Projects should be buildable in the following order, if tests are not executed:")
            val aggregate = mutableListOf<String>()
            projects.forEach { project ->
                if (aggregate.isEmpty()) {
                    aggregate.add(project)
                } else if (aggregate.all { comparator(it, project) == 0 }) {
                    aggregate.add(project)
                } else {
                    writer.println("  - ${aggregate.joinToString(", ")}")
                    aggregate.clear()
                    aggregate.add(project)
                }
            }
            if (aggregate.isNotEmpty()) {
                writer.println("  - ${aggregate.joinToString(", ")}")
            }
            val errors = collectErrors(reportsDir)
            if (errors.isNotEmpty()) {
                writer.println()
                writer.println("--------------------------------------")
                errors.forEach(writer::println)
            }
        }
    }

    private fun buildProjectDependenciesMap(reportsDir: File): MutableMap<String, MutableSet<String>> {
        val projectToDependencies = mutableMapOf<String, MutableSet<String>>()
        reportsDir.listFiles().forEach { projectDir ->
            projectDir.listFiles().forEach { dependencyFile ->
                val dependencies = mutableSetOf<String>()
                if (dependencyFile.name.endsWith(".txt")) {
                    val groupId = dependencyFile.name.substringBeforeLast(".txt").toProjectName()
                    dependencyFile.readLines(charset("UTF-8")).forEach { dependency ->
                        dependencies.add(dependency.toProjectName())
                    }
                    projectToDependencies[groupId] = dependencies
                }
            }
        }
        return projectToDependencies
    }

    private fun collectErrors(reportsDir: File): MutableSet<String> {
        val errors = mutableSetOf<String>()
        reportsDir.listFiles().forEach { projectDir ->
            var project: String? = null
            var error: String? = null
            projectDir.listFiles().forEach { dependencyFile ->
                val dependencies = mutableSetOf<String>()
                if (dependencyFile.name.endsWith(".txt")) {
                    project = dependencyFile.name.substringBeforeLast(".txt").toProjectName()
                } else if (dependencyFile.name == "ERROR") {
                    error = dependencyFile.readText(charset("UTF-8"))
                }
            }
            if (error != null && project != null) {
                errors.add("""Project $project has an error: 
                    |$error""".trimMargin())
            }
        }
        return errors
    }

    private fun generateDependencyGraphImage(
        projectToDependencies: Map<String, Set<String>>,
        outputDir: File,
        graphName: String
    ) {
        // Now that we have the map, we can generate a DOT file
        val dotFile = File(temporaryDir, "${graphName}.dot")
        dotFile.printWriter(charset("UTF-8")).use { writer ->
            writer.println("digraph project_graph {")
            projectToDependencies.forEach { (project, dependencies) ->
                dependencies.forEach { dependency ->
                    writer.println("  \"$project\" -> \"$dependency\";")
                }
            }
            writer.println("}")
        }
        // Call the dot command to generate the PNG file
        execOperations.exec {
            executable = "dot"
            args = listOf("-Tpng", "-o", File(outputDir, "${graphName}.png").absolutePath, dotFile.absolutePath)
        }
    }

    fun String.toProjectName(): String {
        val name = this.replace(Regex("io[.]micronaut[.]?"), "")
        if (name == "") {
            return "core"
        }
        if (name == "build.internal") {
            return "build"
        }
        return name
    }

    fun String.transitiveDeps(deps: Map<String, Set<String>>): Set<String> {
        val transitive = mutableSetOf<String>()
        this.transitiveDeps(deps, transitive)
        return transitive
    }

    private fun String.transitiveDeps(deps: Map<String, Set<String>>, visited: MutableSet<String>): Unit {
        val dependencies = deps[this] ?: emptySet()
        if (visited.add(this)) {
            dependencies.forEach {
                it.transitiveDeps(deps, visited)
            }
        }
    }
}
