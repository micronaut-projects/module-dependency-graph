import io.micronaut.build.MicronautProjectsExtension
import io.micronaut.build.GenerateReport
import io.micronaut.build.ProjectGraphBuilder

val micronautProjects = extensions.create<MicronautProjectsExtension>("micronautProjects", layout.projectDirectory.file("src/init.gradle"))

val allReports by tasks.registering {
    dependsOn(tasks.withType<GenerateReport>())
}

val graphBuilder = tasks.register<ProjectGraphBuilder>("graphBuilder") {
    mustRunAfter(allReports)
    outputDirectory.set(layout.buildDirectory.dir("graph"))
    reportsDirectory.set(layout.buildDirectory.dir("reports"))
    projectsExcludedFromPlatform.addAll(listOf("build", "gradle"))
}
