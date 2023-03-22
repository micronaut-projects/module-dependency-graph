# Micronaut projects dependency graph builder

This project is responsible for generating the dependency graph of the Micronaut projects.
It is aimed at facilitating the release process, by determining in which order the projects should be built and published.
For example, if `micronaut-foo` depends on `micronaut-bar` and `micronaut-core`, then it will tell us that `bar` and `core` needs to be built first, then `foo`.

The report tool is capable of determining cycles between projects, which will prevent from releasing.

The output of this project will be found in the `build/graph` directory.
It consists of:

- a full graph of the dependencies between the projects
- a simplified graph of the dependencies which excludes `core` (because all projects transitively depend on core)
- a text file explaining in which order to generate the graphs and validation problems (e.g warnings)

The tool works by looking at the _groupId_ of each project, so if 2 projects share the same _groupId_, the result is going to be wrong.
This should be considered an error, though.

## How to use

1. Edit the `build.gradle.kts` file to include all Micronaut projects you want to include in the report. It is possible to checkout a particular branch by adding `@branch` at the end of the project identifier.
2. Run `./gradlew allReports` : this will checkout all Micronaut projects in the `checkouts` directory and generate one report per project, in the `build/reports` directory. This report contains the dependencies of the project.
3. Run `./gradlew graphBuilder` to generate the dependency graph

There is no hard dependency between the 2 tasks in order to avoid having to checkout all projects when testing the graph builder, but you can execute both at once: `./gradlew allReports graphBuilder`
