package io.micronaut.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.tooling.GradleConnector
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

abstract class GenerateReport : DefaultTask() {
    @get:InputFile
    abstract val initScript: RegularFileProperty

    @get:InputDirectory
    abstract val projectDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val reportDirectory: DirectoryProperty

    @get:Input
    abstract val micronautProject: Property<String>

    @get:Inject
    abstract val providers: ProviderFactory

    @get:Inject
    abstract val fileOperations: FileSystemOperations

    @TaskAction
    fun report() {
        val initScriptPath = initScript.get().asFile.absolutePath
        println("Injecting init script $initScriptPath")
        val projectDir = projectDirectory.get().asFile
        fileOperations.delete {
            this.delete(reportDirectory.get().asFile)
        }
        Files.createDirectories(reportDirectory.get().asFile.toPath())
        try {
            GradleConnector.newConnector()
                    .forProjectDirectory(projectDir)
                    .connect().use {
                    it.newBuild()
                        .withArguments("-I", initScriptPath, "-DreportDir=" + reportDirectory.asFile.get().absolutePath)
                        .forTasks("extractMicronautDependencies")
                        .setStandardOutput(System.out)
                        .setStandardError(System.err)
                        .run()
                    }
        } catch (e: Exception) {
            // We intentionally ignore the status of the build result
            System.err.println(e.message)
            File(reportDirectory.asFile.get(), "ERROR").printWriter().use { out ->
                out.println("---")
                out.println(e.message)
                e.printStackTrace(out)
                out.println("---")
            }
        }
    }
}
