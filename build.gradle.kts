plugins {
    id("io.micronaut.build.license-report")
}

// When using the environment variable, these are the modules to ignore
val blockList = listOf(
    "micronaut-build",
    "micronaut-build-plugins",
    "micronaut-comparisons",
    "micronaut-crac-tests",
    "micronaut-docs",
    "micronaut-docs-deploy",
    "micronaut-docs-index",
    "micronaut-examples",
    "micronaut-fuzzing",
    "micronaut-guides",
    "micronaut-guides-old",
    "micronaut-guides-poc",
    "micronaut-helidon", // Not sure what this is, but it fails locally with permissions errors
    "micronaut-lambda-todo",
    "micronaut-maven-plugin",
    "micronaut-oauth2",
    "micronaut-profiles",
    "micronaut-project-template",
    "micronaut-starter-ui",
)

// For local dev with no env var, these are the modules to pull
val defaultModules = listOf(
    "micronaut-acme",
    "micronaut-aot",
    "micronaut-aws",
    "micronaut-azure",
    "micronaut-build",
    "micronaut-cache",
    "micronaut-camel",
    "micronaut-cassandra",
    "micronaut-coherence",
    "micronaut-core@4.0.x",
    "micronaut-couchbase",
    "micronaut-crac",
    "micronaut-data",
    "micronaut-discovery-client",
    "micronaut-elasticsearch",
    "micronaut-email",
    "micronaut-flyway",
    "micronaut-gcp",
    "micronaut-gradle-plugin",
    "micronaut-graphql",
    "micronaut-groovy",
    "micronaut-grpc",
    "micronaut-hibernate-validator",
    "micronaut-ignite",
    "micronaut-jackson-xml",
    "micronaut-jaxrs",
    "micronaut-jms",
    "micronaut-jmx",
    "micronaut-kafka",
    "micronaut-kotlin",
    "micronaut-kubernetes",
    "micronaut-liquibase",
    "micronaut-logging",
    "micronaut-micrometer",
    "micronaut-microstream",
    "micronaut-mongodb",
    "micronaut-mqtt",
    "micronaut-multitenancy",
    "micronaut-nats",
    "micronaut-neo4j",
    "micronaut-object-storage",
    "micronaut-openapi",
    "micronaut-oracle-cloud",
    "micronaut-picocli",
    "micronaut-platform",
    "micronaut-problem-json",
    "micronaut-pulsar",
    "micronaut-r2dbc",
    "micronaut-rabbitmq",
    "micronaut-reactor",
    "micronaut-redis",
    "micronaut-rss",
    "micronaut-rxjava2",
    "micronaut-rxjava3",
    "micronaut-security",
    "micronaut-serialization",
    "micronaut-servlet",
    "micronaut-session",
    "micronaut-spring",
    "micronaut-sql",
    "micronaut-starter@4.0.x",
    "micronaut-test",
    "micronaut-test-resources",
    "micronaut-tracing",
    "micronaut-toml",
    "micronaut-validation",
    "micronaut-views",
)

micronautProjects {
    project.providers
        .environmentVariable("MICRONAUT_MODULES") // Env var from GH Action
        .map { it.split(',') }
        .map {
            it
                .filter { moduleBranch -> moduleBranch.startsWith("micronaut-") } // Has to start with 'micronaut-'
                .filterNot { moduleBranch -> moduleBranch.contains("-ghsa-") } // Not a security module fork
                .filterNot { moduleBranch -> blockList.contains(moduleBranch.split("@").first()) }
        }
        .getOrElse(defaultModules) // Fallback to the default modules if no env var
        .map {
            val (name, branch) = if (it.contains('@')) {
                val pair = it.split('@')
                if (pair.first() == "micronaut-starter") {
                    listOf("micronaut-starter", "4.0.x")
                } else {
                    pair
                }
            } else {
                listOf(it, "master")
            }
            Pair("https://github.com/micronaut-projects/${name}.git", branch)
        }.forEach {
            checkout(it.first, it.second)
        }
}
