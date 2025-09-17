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
    "micronaut-cla",
    "micronaut-docs-mn1",
    "micronaut-docs-mn2",
    "micronaut-docs-mn3",
    "micronaut-docs-index",
    "micronaut-docs-deploy",
    "micronaut-documentation",
    "micronaut-aws-lambda-benchmarks",
    "micronaut-http-benchmarks",
    "micronaut-upgrade",
)

// For local dev with no env var, these are the modules to pull
val defaultModules = listOf(
    "micronaut-acme@5.5.x",
    "micronaut-aot@2.9.x",
    "micronaut-aws@4.11.x",
    "micronaut-azure@5.11.x",
    "micronaut-build@8.0.x",
    "micronaut-cache@5.3.x",
    "micronaut-camel@master",
    "micronaut-cassandra@6.8.x",
    "micronaut-coherence@master",
    "micronaut-core@4.10.x",
    "micronaut-couchbase@master",
    "micronaut-crac@2.7.x",
    "micronaut-data@4.13.x",
    "micronaut-discovery-client@4.7.x",
    "micronaut-elasticsearch@5.9.x",
    "micronaut-email@2.9.x",
    "micronaut-flyway@7.8.x",
    "micronaut-gcp@5.12.x",
    "micronaut-gradle-plugin@master",
    "micronaut-graphql@4.8.x",
    "micronaut-groovy@4.7.x",
    "micronaut-grpc@4.11.x",
    "micronaut-hibernate-validator@4.8.x",
    "micronaut-ignite@master",
    "micronaut-jackson-xml@4.7.x",
    "micronaut-jaxrs@4.9.x",
    "micronaut-jms@4.3.x",
    "micronaut-jmx@4.7.x",
    "micronaut-kafka@5.9.x",
    "micronaut-kotlin@4.7.x",
    "micronaut-kubernetes@7.1.x",
    "micronaut-liquibase@7.0.x",
    "micronaut-logging@1.7.x",
    "micronaut-micrometer@5.12.x",
    "micronaut-microstream@2.9.x",
    "micronaut-mongodb@5.7.x",
    "micronaut-mqtt@3.7.x",
    "micronaut-multitenancy@5.6.x",
    "micronaut-nats@4.8.x",
    "micronaut-neo4j@6.10.x",
    "micronaut-object-storage@2.10.x",
    "micronaut-openapi@6.18.x",
    "micronaut-oracle-cloud@5.3.x",
    "micronaut-picocli@5.8.x",
    "micronaut-platform@4.10.x",
    "micronaut-problem-json@3.8.x",
    "micronaut-pulsar@2.7.x",
    "micronaut-r2dbc@6.1.x",
    "micronaut-rabbitmq@4.8.x",
    "micronaut-reactor@3.8.x",
    "micronaut-redis@6.8.x",
    "micronaut-rss@4.7.x",
    "micronaut-rxjava2@2.8.x",
    "micronaut-rxjava3@3.8.x",
    "micronaut-security@4.14.x",
    "micronaut-serialization@2.15.x",
    "micronaut-servlet@5.5.x",
    "micronaut-session@4.7.x",
    "micronaut-spring@5.11.x",
    "micronaut-sql@6.2.x",
    "micronaut-starter@4.10.x",
    "micronaut-sourcegen@1.9.x",
    "micronaut-test@4.8.x",
    "micronaut-test-resources@2.10.x",
    "micronaut-tracing@7.1.x",
    "micronaut-toml@2.7.x",
    "micronaut-validation@4.10.x",
    "micronaut-views@5.9.x",
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
                    listOf("micronaut-starter", "4.10.x")
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
