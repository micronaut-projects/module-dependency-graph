plugins {
    id("io.micronaut.build.license-report")
}

micronautProjects {
    listOf(
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
        "micronaut-gcp",
        "micronaut-gradle-plugin",
        "micronaut-graphql",
        "micronaut-groovy",
        "micronaut-grpc",
        "micronaut-hibernate-validator",
        "micronaut-ignite",
        "micronaut-jackson-xml",
        "micronaut-jms",
        "micronaut-jmx",
        "micronaut-kafka",
        "micronaut-kotlin",
        "micronaut-kubernetes",
        "micronaut-liquibase",
        "micronaut-logging",
        "micronaut-micrometer",
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
        "micronaut-starter",
        "micronaut-test",
        "micronaut-test-resources",
        "micronaut-toml",
        "micronaut-validation",
        "micronaut-views",
    ).map {
        val (name, branch) = if (it.contains('@')) {
            it.split('@')
        } else {
            listOf(it, "master")
        }
        Pair("https://github.com/micronaut-projects/${name}.git", branch)
    }.forEach {
        checkout(it.first, it.second)
    }
}
