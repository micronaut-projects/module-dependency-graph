package io.micronaut.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
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
            val gradlew = if (System.getProperty("os.name").lowercase().contains("win")) "gradlew.bat" else "./gradlew"
            val reportDirPath = reportDirectory.asFile.get().absolutePath
            val command = listOf(
                gradlew,
                "--no-daemon",
                "-I", initScriptPath,
                "-DreportDir=$reportDirPath",
                "extractMicronautDependencies"
            )
            val process = ProcessBuilder(command)
                .directory(projectDir)
                .start()

            val stdoutThread = Thread {
                process.inputStream.copyTo(System.out)
            }
            val stderrThread = Thread {
                process.errorStream.copyTo(System.err)
            }
            stdoutThread.start()
            stderrThread.start()

            val exit = process.waitFor()
            stdoutThread.join()
            stderrThread.join()

            if (exit != 0) {
                throw RuntimeException("Gradle build failed with exit code $exit")
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
