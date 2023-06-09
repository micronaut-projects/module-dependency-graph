package io.micronaut.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.util.GradleVersion
import java.io.File
import java.io.PrintWriter
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import javax.inject.Inject

private const val MICRONAUT_BASELINE_VERSION = "4."

@CacheableTask
abstract class ProjectGraphBuilder : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reportsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val projectsExcludedFromPlatform: SetProperty<String>

    @get:Input
    val latestBuildPluginsVersion = project.objects.property(String::class.java).value(project.provider {
        URL("https://repo1.maven.org/maven2/io/micronaut/build/internal/common/io.micronaut.build.internal.common.gradle.plugin/maven-metadata.xml").openStream().use { stream ->
            val metadata = stream.bufferedReader().readText()
            val version = metadata.substringAfter("<latest>").substringBefore("</latest>")
            println("Latest build plugins version: $version")
            version
        }
    }).also {
        // Cache result in order to avoid multiple requests to Maven Central
        it.finalizeValueOnRead()
    }

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
        this::class.java.getResourceAsStream("/template.html")?.bufferedReader()?.readText()?.apply {
            val projects = projectToMetadata.keys.sorted()
            var templated = replace("{{ITEMS}}", projects.map { project ->
                val metadata = projectToMetadata[project]!!
                """        <li class="item" onclick="showImage(this, 'project-graph-$project.png')">${metadata.asHtml()}</li>"""
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

        val warnings = mutableSetOf<String>()
        val platformDependencies = "platform".transitiveDeps(projectToMetadata)
        val allModules = projectToMetadata.keys - "platform" - projectsExcludedFromPlatform.getOrElse(Collections.emptySet())
        val missingPlatformDependencies = allModules.filter { it !in platformDependencies }
        if (missingPlatformDependencies.isNotEmpty()) {
            warnings.add("[WARNING] The following modules are not included in the platform: $missingPlatformDependencies")
        }
        val cycles = mutableSetOf<Pair<String, String>>()
        val cyclePaths = mutableSetOf<String>()
        val projects = sortByDependents(projectToMetadata, cycles, cyclePaths)
        cyclePaths.forEach {
            val warning = "[WARNING] A cycle was detected: $it"
            System.err.println(warning)
            warnings.add(warning)
        }
        File(temporaryDir, "build-order-graph.dot").also {
            it.printWriter(charset("UTF-8")).use { dotWriter ->
                writeBuildOrder(dotWriter, projects, cycles, projectToMetadata, warnings)
            }
            invokeGraphviz(outputDir, "build-order", it)
        }
    }

    private fun writeBuildOrder(
        dotFile: PrintWriter,
        projects: List<String>,
        cycles: Set<Pair<String, String>>,
        projectToMetadata: MutableMap<String, ModuleMetadata>,
        warnings: MutableSet<String>
    ) {
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
                current.clear()
                current.add(project)
            }
        }
        if (current.isNotEmpty()) {
            registerNewCluster(current, dotFile, projectToMetadata, allClusters, cycles, warnings)
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
            warnings.add(
                """[WARNING] Could not find a cluster for $remainingDependencies. 
                |This can be caused by a project missing in the checkouts list or a failing build.
                |Using the last cluster as a fallback."""
                    .trimMargin()
            )
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

    private fun sortByDependents(projectToMetadata: Map<String, ModuleMetadata>, cycles: MutableSet<Pair<String, String>>, cyclePaths: MutableSet<String>): List<String> {
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
                        if (!cycles.contains(p2 to p1)) {
                            cycles.add(p1 to p2)
                            p1.findPathTo(projectToMetadata, p2)?.also {
                                cyclePaths.add(it)
                            }
                        }
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
                    val allDependencies = props.get("dependencies").toString().trim()
                    if (allDependencies != "") {
                        allDependencies.split(",").forEach { dependency ->
                            dependencies.add(dependency.toProjectName())
                        }
                        if (name == "gradle") {
                            // Add an implicit dependency to platform, which is not captured by the tool
                            // because it's added at runtime only by the plugin
                            dependencies.add("platform")
                        }
                    }
                    if (projectToMetadata.containsKey(name)) {
                        throw IllegalStateException("Duplicate project name: $name, also found in $dependencyFile")
                    }
                    projectToMetadata[name] = ModuleMetadata(
                        name,
                        props.get("version").toString(),
                        groupId,
                        props.get("status").toString(),
                        props.get("githubSlug").toString(),
                        props.get("micronautVersion").toString(),
                        props.get("gradleVersion").toString(),
                        props.get("settingsPluginVersion").toString(),
                        latestBuildPluginsVersion.get(),
                        props.get("build-status")?.toString(),
                        dependencies.toList()
                    )
                } else if (dependencyFile.name == "ERROR") {
                    val name = projectDir.name.substring("reportForMicronaut".length).lowercase()
                    projectToMetadata[name] = ModuleMetadata(
                        name,
                        "ERROR",
                        "ERROR",
                        "ERROR",
                        "ERROR",
                        "ERROR",
                        "ERROR",
                        "ERROR",
                        latestBuildPluginsVersion.get(),
                        "ERROR",
                        emptyList()
                    )
                }
            }
        }
        return projectToMetadata
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

    fun String.findPathTo(deps: Map<String, ModuleMetadata>, target: String): String? {
        val path = findPathTo(deps, target, this, mutableSetOf())
        if (path != null) {
            return "$path -> $this"
        }
        return null
    }

    fun String.findPathTo(deps: Map<String, ModuleMetadata>, target: String, currentPath: String, visited: MutableSet<String>): String? {
        if (visited.add(this)) {
            val dependencies = deps[this]?.dependencies ?: emptySet()
            dependencies.forEach {
                val path = "$currentPath -> $it"
                if (it == target) {
                    return path
                }
                val candidate = it.findPathTo(deps, target, path, visited)
                if (candidate != null) {
                    return candidate
                }
            }
        }
        return null;
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
        val version: String,
        val group: String,
        val status: String,
        val githubSlug: String,
        val micronautVersion: String,
        val gradleVersion: String,
        val settingsPluginVersion: String,
        val latestSettingsPluginVersion: String,
        val buildStatus: String?,
        val dependencies: List<String>
    ) {

        val buildStatusEmoji = when (buildStatus) {
            "passing" -> Quality.GREEN.emoji
            "failing" -> Quality.RED.emoji
            else -> Quality.YELLOW.emoji
        }

        val gradleEmoji = when (gradleVersion) {
            GradleVersion.current().version -> Quality.GREEN.emoji
            else -> Quality.YELLOW.emoji
        }

        val statusEmoji = when (status) {
            "SNAPSHOT" -> Quality.RED.emoji
            "RELEASE" -> Quality.GREEN.emoji
            else -> Quality.YELLOW.emoji
        }

        val settingsEmoji = when (settingsPluginVersion) {
            latestSettingsPluginVersion -> Quality.GREEN.emoji
            else -> Quality.YELLOW.emoji
        }

        val micronautEmoji = when {
            micronautVersion.startsWith(MICRONAUT_BASELINE_VERSION) && !micronautVersion.endsWith("-SNAPSHOT") -> Quality.GREEN.emoji
            micronautVersion.startsWith(MICRONAUT_BASELINE_VERSION) -> Quality.YELLOW.emoji
            else -> Quality.RED.emoji
        }

        fun asHtml() = """<TABLE BORDER="0" CELLSPACING="1" CELLPADDING="1" STYLE="rounded">
        |<TR><TD><B>$name $version</B></TD></TR>
            |<TR><TD>${statusEmoji} Status $status</TD></TR>
            |${if (name!="core") {"""<TR><TD>${micronautEmoji} Micronaut $micronautVersion</TD></TR>"""} else {""} }
            |<TR><TD>${gradleEmoji} Gradle $gradleVersion</TD></TR>
            |<TR><TD>${settingsEmoji} Settings $settingsPluginVersion</TD></TR>
            |${if (buildStatus != null) """<TR><TD>${buildStatusHtml}</TD></TR>""" else ""}
            |</TABLE>""".trimMargin()

        val buildStatusHtml = if (buildStatus != null) {
            val color = when (buildStatus) {
                "passing" -> "darkgreen"
                "failing" -> "red"
                else -> "yellow"
            }
            """$buildStatusEmoji <B>Build: <FONT COLOR="$color">${buildStatus.uppercase(Locale.ENGLISH)}</FONT></B>"""
        } else {
            ""
        }

        enum class Quality(var emoji: String) {
            RED("&#10060;"),
            GREEN("&#128154;"),
            YELLOW("&#128155;")
        }
    }
}
