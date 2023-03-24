package io.micronaut.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
        val projectToMetadata = buildProjectMetadataMap(reportsDir)
        // Build a first graph will all modules
        generateDependencyGraphImage(projectToMetadata, outputDir, "project-graph")
        // And a second one filtering out "core"
        val filteredProjectToDependencies = projectToMetadata.filterKeys { it != "core" }
            .mapValuesTo(mutableMapOf()) { (_, metadata) ->
                metadata.copy(dependencies = metadata.dependencies.filter { it != "core" }.toList())
            }
            .toMap()
        generateDependencyGraphImage(filteredProjectToDependencies, outputDir, "project-graph-filtered")
        // and generate one graph per project with just their transitive dependencies
        projectToMetadata.keys.forEach { project ->
            val dependencies = project.transitiveDeps(projectToMetadata)
            generateDependencyGraphImage(projectToMetadata.filterKeys { it in dependencies }, outputDir, "project-graph-$project")
        }

        // Generate the HTML
        this::class.java.getResourceAsStream("/index.template")?.bufferedReader()?.readText()?.apply {
            val projects = projectToMetadata.keys.sorted()
            var templated = replace("{{ITEMS}}", projects.map { project ->
                """        <li class="item" onclick="showImage(this, 'project-graph-$project.png')">$project</li>"""
            }.joinToString("\n"))
            templated = templated.replace("{{IMAGES}}", projects.map { project ->
                """ <div class="graph">       
                        <img id="project-graph-$project.png" src="project-graph-$project.png">
                    </div>""".trimIndent()
            }.joinToString("\n"))
            templated = templated.replace("{{GENERATED}}", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(LocalDateTime.now()))
            File(outputDir, "index.html").writer().use {
                it.write(templated)
            }
        }

        // Now generate a text file describing in which order we should build the projects
        val buildOrderFile = File(outputDir, "build-order.txt")
        val warnings = mutableSetOf<String>()
        val platformDependencies = "platform".transitiveDeps(projectToMetadata)
        val allModules = projectToMetadata.keys - "platform" - projectsExcludedFromPlatform.getOrElse(Collections.emptySet())
        val missingPlatformDependencies = allModules.filter { it !in platformDependencies }
        if (missingPlatformDependencies.isNotEmpty()) {
            warnings.add("[WARNING] The following modules are not included in the platform: $missingPlatformDependencies")
        }
        val cycles = mutableSetOf<Pair<String, String>>()
        buildOrderFile.printWriter(charset("UTF-8")).use { txtWriter ->
            val projects = sortByDependents(projectToMetadata, cycles)
            cycles.forEach {
                System.err.println("[WARNING] A cycle exists between ${it.first} and ${it.second}")
            }
            File(temporaryDir, "build-order-graph.dot").also {
                it.printWriter(charset("UTF-8")).use { dotWriter ->
                    writeBuildOrder(txtWriter, dotWriter, projects, cycles, projectToMetadata, warnings)
                }
                invokeGraphviz(outputDir, "build-order", it)
            }
            writeBuildErrors(reportsDir, txtWriter)
        }
    }

    private fun writeBuildErrors(reportsDir: File, writer: PrintWriter) {
        val errors = collectErrors(reportsDir)
        if (errors.isNotEmpty()) {
            writer.println()
            writer.println("--------------------------------------")
            errors.forEach(writer::println)
        }
    }

    private fun writeBuildOrder(
        textFile: PrintWriter,
        dotFile: PrintWriter,
        projects: List<String>,
        cycles: Set<Pair<String, String>>,
        projectToMetadata: MutableMap<String, ModuleMetadata>,
        warnings: MutableSet<String>
    ) {
        textFile.println("Projects should be buildable in the following order, if tests are not executed:")
        dotFile.println("digraph build_order {")
        dotFile.println("  compound=true;")
        dotFile.println("  graph [splines=ortho];")
        dotFile.println("  rank=sink;")
        dotFile.println("  label = \"Projects should be buildable in the following order, if tests are not executed\";")
        dotFile.println("  labelloc = \"t\";")
        dotFile.println("  node [fontsize=14 fontname=\"Verdana\" style=filled shape=box];")
        val current = mutableListOf<String>()
        val allClusters = mutableMapOf<String, Cluster>()
        val comparator: (String, String) -> Int = { p1, p2 ->
            val deps1 = p1.transitiveDeps(projectToMetadata)
            val deps2 = p2.transitiveDeps(projectToMetadata)
            if (deps1.contains(p2)) {
                1
            } else if (deps2.contains(p1)) {
                -1
            } else {
                0
            }
        }
        projects.forEach { project ->
            if (current.isEmpty()) {
                current.add(project)
            } else if (current.all { comparator(it, project) == 0 }) {
                current.add(project)
            } else {
                val cluster = registerNewCluster(current, dotFile, projectToMetadata, allClusters, cycles, warnings)
                allClusters.put(cluster.name, cluster)
                textFile.println("  - ${current.joinToString(", ")}")
                current.clear()
                current.add(project)
            }
        }
        if (current.isNotEmpty()) {
            registerNewCluster(current, dotFile, projectToMetadata, allClusters, cycles, warnings)
            textFile.println("  - ${current.joinToString(", ")}")
        }
        cycles.forEach { (p1, p2) ->
            dotFile.println("  \"$p1\" -> \"$p2\" [color=red penwidth=3];")
        }
        if (!warnings.isEmpty()) {
            val warningsText = warnings.map { w -> w.removePrefix("[WARNING] ") }
                .joinToString("\\l")
            dotFile.println("  subgraph warnings {")
            dotFile.println("    label = \"Warnings\";")
            dotFile.println("    node [shape=plaintext];")
            dotFile.println("    warnings [label=\"${warningsText}\"];")
            dotFile.println("  }")
        }
        dotFile.println("}")
    }

    private fun registerNewCluster(
        current: MutableList<String>,
        dotFile: PrintWriter,
        projectToMetadata: Map<String, ModuleMetadata>,
        allClusters: Map<String, Cluster>,
        cycles: Set<Pair<String, String>>,
        warnings: MutableSet<String>
    ): Cluster {
        val cluster = writeCluster(current, dotFile, projectToMetadata)
        val remainingDependencies = (current.flatMap { it.transitiveDeps(projectToMetadata) }.toSet() - current).toMutableSet()
        val sortedClusters = allClusters.values.reversed().toMutableList()
        while (sortedClusters.isNotEmpty()) {
            val previous = sortedClusters.removeFirst()
            val previousAncestors = previous.transitiveDeps + previous.components()
            val intersect = remainingDependencies.intersect(previousAncestors)
            if (intersect.isNotEmpty()) {
                val head = if (cluster is Cluster.Multi) "lhead=${cluster.name}" else ""
                val tail = if (previous is Cluster.Multi) "ltail=${previous.name}" else ""
                if (!cycles.any { (p1, p2) -> (p1 == previous.name && p2 == cluster.middle) || (p1 == cluster.middle && p2 == previous.name) }) {
                    dotFile.println("  \"${previous.middle}\" -> \"${cluster.middle}\" [$head $tail];")
                }
                remainingDependencies.removeAll(intersect)
                if (remainingDependencies.isEmpty()) {
                    break
                }
            }
        }
        if (remainingDependencies.isNotEmpty()) {
            warnings.add("""[WARNING] Could not find a cluster for $remainingDependencies. 
                |This can be caused by a project missing in the checkouts list or a failing build.
                |Using the last cluster as a fallback."""
                .trimMargin())
            if (allClusters.isEmpty()) {
                throw IllegalStateException("No clusters found. This is not expected.")
            }
            val previous = allClusters.values.last()
            val head = if (cluster is Cluster.Multi) "lhead=${cluster.name}" else ""
            val tail = if (previous is Cluster.Multi) "ltail=${previous.name}" else ""
            if (!cycles.any { (p1, p2) -> (p1 == previous.name && p2 == cluster.middle) || (p1 == cluster.middle && p2 == previous.name) }) {
                dotFile.println("  \"${previous.middle}\" -> \"${cluster.middle}\" [$head $tail];")
            }
        }
        return cluster
    }

    private fun writeCluster(components: List<String>, dotFile: PrintWriter, projectToMetadata: Map<String, ModuleMetadata>): Cluster {
        val id: String
        val transitiveDeps = components.flatMap { projectToMetadata[it]?.dependencies ?: emptyList() }.toSet()
        if (components.size > 1) {
            id = "cluster_${components.first()}"
            dotFile.println("  subgraph $id {")
            dotFile.println("    label = \"\";")
            components.forEach {
                val moduleMetadata = projectToMetadata[it]
                val color = moduleMetadata.color()
                dotFile.println("    \"$it\" [style=filled fillcolor=$color label=<${moduleMetadata?.asHtml()}>];")
            }
            dotFile.println("    color=blue;")
            dotFile.println("  }")
            return Cluster.Multi(id, transitiveDeps, components.toList())
        } else {
            id = components.first()
            val moduleMetadata = projectToMetadata[id]
            val color = moduleMetadata.color()
            dotFile.println("    \"$id\" [style=filled fillcolor=$color label=<${moduleMetadata?.asHtml()}>];")
            return Cluster.Single(id, transitiveDeps)
        }
    }

    private fun sortByDependents(projectToMetadata: Map<String, ModuleMetadata>, cycles: MutableSet<Pair<String, String>>): List<String> {
        val remaining = projectToMetadata.keys.toTypedArray()
        var i = 0
        while (i < remaining.size) {
            var j = i + 1
            while (j < remaining.size) {
                val p1 = remaining[i]
                val t1 = p1.transitiveDeps(projectToMetadata)
                val p2 = remaining[j]
                if (t1.contains(p2)) {
                    val t2 = p2.transitiveDeps(projectToMetadata)
                    if (t2.contains(p1)) {
                        cycles.add(p1 to p2)
                        j++
                    } else {
                        remaining[i] = p2
                        remaining[j] = p1
                        j = i + 1
                    }
                } else {
                    j++
                }
            }
            i++
        }
        return remaining.toList()
    }

    private fun ModuleMetadata?.color() = when (this?.status) {
        "SNAPSHOT" -> "white"
        "RELEASE" -> "aquamarine"
        else -> "aquamarine2"
    }

    private fun buildProjectMetadataMap(reportsDir: File): MutableMap<String, ModuleMetadata> {
        val projectToMetadata = mutableMapOf<String, ModuleMetadata>()
        reportsDir.listFiles().forEach { projectDir ->
            projectDir.listFiles().forEach { dependencyFile ->
                if (dependencyFile.name.endsWith(".properties")) {
                    val dependencies = mutableSetOf<String>()
                    val props = Properties()
                    dependencyFile.inputStream().use { props.load(it) }
                    val groupId = props.get("groupId").toString()
                    val name = groupId.toProjectName()
                    props.get("dependencies").toString().split(",").forEach { dependency ->
                        dependencies.add(dependency.toProjectName())
                    }
                    if (projectToMetadata.containsKey(name)) {
                        throw IllegalStateException("Duplicate project name: $name, also found in $dependencyFile")
                    }
                    projectToMetadata[name] = ModuleMetadata(
                        name,
                        groupId,
                        props.get("status").toString(),
                        props.get("githubSlug").toString(),
                        props.get("gradleVersion").toString(),
                        props.get("settingsPluginVersion").toString(),
                        props.get("build-status")?.toString(),
                        dependencies.toList()
                    )
                }
            }
        }
        return projectToMetadata
    }

    private fun collectErrors(reportsDir: File): MutableSet<String> {
        val errors = mutableSetOf<String>()
        reportsDir.listFiles().forEach { projectDir ->
            var project: String? = null
            var error: String? = null
            projectDir.listFiles().forEach { dependencyFile ->
                if (dependencyFile.name.endsWith(".txt")) {
                    project = dependencyFile.name.substringBeforeLast(".txt").toProjectName()
                } else if (dependencyFile.name == "ERROR") {
                    error = dependencyFile.readText(charset("UTF-8"))
                }
            }
            if (error != null && project != null) {
                errors.add(
                    """Project $project has an error: 
                    |$error""".trimMargin()
                )
            }
        }
        return errors
    }

    private fun generateDependencyGraphImage(
        projectToDependencies: Map<String, ModuleMetadata>,
        outputDir: File,
        graphName: String
    ) {
        // Now that we have the map, we can generate a DOT file
        val dotFile = File(temporaryDir, "${graphName}.dot")
        dotFile.printWriter(charset("UTF-8")).use { writer ->
            writer.println("digraph project_graph {")
            writer.println("  graph [splines=ortho];")
            projectToDependencies.forEach { (project, metadata) ->
                metadata.dependencies.forEach { dependency ->
                    writer.println("  \"$project\" -> \"$dependency\";")
                }
            }
            writer.println("}")
        }
        // Call the dot command to generate the PNG file
        invokeGraphviz(outputDir, graphName, dotFile)
    }

    private fun invokeGraphviz(outputDir: File, graphName: String, dotFile: File) {
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

    fun String.transitiveDeps(deps: Map<String, ModuleMetadata>): Set<String> {
        val transitive = mutableSetOf<String>()
        this.transitiveDeps(deps, transitive)
        return transitive
    }

    private fun String.transitiveDeps(deps: Map<String, ModuleMetadata>, visited: MutableSet<String>): Unit {
        val dependencies = deps[this]?.dependencies ?: emptySet()
        if (visited.add(this)) {
            dependencies.forEach {
                it.transitiveDeps(deps, visited)
            }
        }
    }

    sealed class Cluster(val name: String, val transitiveDeps: Set<String>) {
        abstract val size: Int
        abstract val middle: String
        abstract fun components(): Set<String>

        class Multi(name: String, transitiveDeps: Set<String>, val nodes: List<String>) : Cluster(name, transitiveDeps) {
            override val size: Int
                get() = nodes.size
            override val middle: String
                get() = nodes[size / 2]

            override fun components() = nodes.toSet()

            override fun toString() = "Cluster $name : $nodes"
        }

        class Single(name: String, transitiveDeps: Set<String>) : Cluster(name, transitiveDeps) {
            override val size: Int
                get() = 1
            override val middle: String
                get() = name

            override fun toString() = "Node $name"
            override fun components() = setOf(name)
        }
    }

    data class ModuleMetadata(
        val name: String,
        val group: String,
        val status: String,
        val githubSlug: String,
        val gradleVersion: String,
        val settingsPluginVersion: String,
        val buildStatus: String?,
        val dependencies: List<String>
    ) {
        fun asHtml() = """<TABLE BORDER="0" CELLSPACING="1" CELLPADDING="1" STYLE="rounded">
        |<TR><TD><B>$name</B></TD></TR>
            |<TR><TD>Status: $status</TD></TR>
            |<TR><TD>Gradle: $gradleVersion</TD></TR>
            |<TR><TD>Settings: $settingsPluginVersion</TD></TR>
            |${if (buildStatus != null) """<TR><TD>${buildStatusHtml}</TD></TR>""" else ""}
            |</TABLE>""".trimMargin()

        val buildStatusHtml = if (buildStatus != null) {
            val color = when (buildStatus) {
                "passing" -> "darkgreen"
                "failing" -> "red"
                else -> "yellow"
            }
            """<B>Build: <FONT COLOR="$color">${buildStatus.uppercase(Locale.ENGLISH)}</FONT></B>"""
        } else {
            ""
        }
    }
}
