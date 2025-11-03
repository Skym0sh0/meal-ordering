import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.*
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Target
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.LocalTime

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.postgresql:postgresql:42.7.4")
        classpath("org.testcontainers:postgresql:1.21.3")
        classpath("org.jooq:jooq-codegen:3.19.26")
        classpath("org.flywaydb:flyway-core:11.14.0")
        classpath("org.flywaydb:flyway-database-postgresql:11.15.0")
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.14.0"
    id("com.google.cloud.tools.jib") version "3.4.5"
    id("com.gorylenko.gradle-git-properties") version "2.5.2"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jersey")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.session:spring-session-core")
    implementation("io.micrometer:micrometer-core:1.15.4")
    implementation("io.micrometer:micrometer-registry-prometheus:1.15.4")
    implementation("net.ttddyy.observation:datasource-micrometer:1.1.2")
    implementation("io.swagger.core.v3:swagger-models:2.2.38")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.38")

    implementation("org.apache.commons:commons-lang3:3.18.0")
    implementation("com.google.guava:guava:33.5.0-jre")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

gitProperties {
    extProperty = "git.properties"
}

tasks.register("prepareGitProperties") {
    dependsOn("generateGitProperties")

    doFirst {
        val gitProps = (project.ext["git.properties"] as? Map<*, *>)?.mapNotNull { (key, value) ->
            if (key is String && value is String)
                key to value
            else
                null
        }?.toMap() ?: mapOf()

        gitProps.forEach { (key, value) ->
            project.extra.set(key, value)
        }
    }
}

tasks.named("compileJava") {
    dependsOn("prepareGitProperties")
}

tasks.register<Copy>("copyWebApp") {
    dependsOn(":frontend:build")

    from("$rootDir/frontend/build/dist")
    into(layout.buildDirectory.dir("resources/main/static/"))
}

tasks.named("processResources") {
    dependsOn("copyWebApp")
}

tasks.register("jooqCodegen") {
    group = "build"

    inputs.files(layout.projectDirectory.dir("src/main/resources/db/migration"))
    outputs.dir(file(layout.buildDirectory.dir("generated/sources/jooq")))

    doLast {
        PostgreSQLContainer<Nothing>(
            DockerImageName.parse("postgres@sha256:d13ef786196545cd69aff1945929fc868712196e195bc66581fb1bfe81649eaf")
        ).use {
            it.start()

            Flyway.configure()
                .failOnMissingLocations(true)
                .dataSource(it.jdbcUrl, it.username, it.password)
                .locations("${Location.FILESYSTEM_PREFIX}${layout.projectDirectory.dir("src/main/resources/db/migration").asFile.path}")
                .load()
                .migrate()

            Configuration()
                .withJdbc(
                    Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(it.jdbcUrl)
                        .withUser(it.username)
                        .withPassword(it.password)
                )
                .withGenerator(
                    Generator()
                        .withDatabase(
                            Database()
                                .withInputSchema("public")
                                .withOutputSchema("meal_ordering")
                                .withExcludes(".*flyway.*")
                        )
                        .withGenerate(
                            Generate()
                                .withFluentSetters(true)
                                .withDeprecated(false)
                                .withRelations(true)
                        )
                        .withTarget(
                            Target()
                                .withPackageName("generated.sky.meal.ordering.schema")
                                .withDirectory(layout.buildDirectory.dir("generated/sources/jooq").get().asFile.path)
                                .withEncoding("UTF-8")
                                .withClean(true)
                        )
                ).also(GenerationTool::generate)
        }
    }
}

tasks.named("compileJava") {
    dependsOn("jooqCodegen")
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(layout.projectDirectory.dir("..").dir("spec").file("openapi-spec.yml").asFile.path)
    outputDir.set(layout.buildDirectory.dir("generated/sources/spec").get().asFile.path)
    apiPackage.set("generated.sky.meal.ordering.rest.api")
    modelPackage.set("generated.sky.meal.ordering.rest.model")
    typeMappings.set(
        mapOf(
            "time" to "LocalTime",
            "duration" to "Duration",
        )
    )
    importMappings.set(
        mapOf(
            "LocalTime" to LocalTime::class.java.getName(),
            "Duration" to Duration::class.java.getName()
        )
    )
    configOptions.set(
        mapOf(
            "library" to "spring-boot",
            "dateLibrary" to "java8",
            "useJakartaEe" to "true",
            "generateBuilders" to "true",
            "legacyDiscriminatorBehavior" to "false",
            "openApiNullable" to "false",
            "delegatePattern" to "false",
            "useSpringBoot3" to "true",
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "returnSuccessCode" to "true",
            "useTags" to "true"
        )
    )
}

tasks.named("compileJava") {
    dependsOn(tasks.openApiGenerate)
    dependsOn("prepareGitProperties")
}

sourceSets {
    main {
        java.srcDirs(
            layout.buildDirectory.dir("generated/sources/jooq/generated").get().asFile,
            layout.buildDirectory.dir("generated/sources/spec/src/main").get().asFile
        )
    }
}

jib {
    from {
        image =
            "registry://eclipse-temurin:22.0.1_8-jre@sha256:7294e7c43aba19e457c7476e8fefffa2f89ed8167417935bcd576bd2cbd90de8"
    }
    to {
        image = (System.getenv("DOCKER_REPOSITORY") ?: "default-docker-repository") + "/in-the-mealtime"
        tags = setOf(System.getenv("RELEASE_VERSION") ?: "latest")
        auth {
            username = System.getenv("DOCKER_HUB_USERNAME") ?: "default-username"
            password = System.getenv("DOCKER_HUB_PASSWORD") ?: "default-password"
        }
    }
    container {
        creationTime.set(project.provider { project.extra["git.commit.time"] as String })
        mainClass = "de.sky.meal.ordering.mealordering.InTheMealtimeApplication"
        ports = listOf("8080")
        labels.set(project.provider {
            mapOf(
                "git.url" to project.extra["git.remote.origin.url"] as String,
                "git.time" to project.extra["git.commit.time"] as String,
                "git.commit" to project.extra["git.commit.id"] as String,
                "git.email" to project.extra["git.commit.user.email"] as String,
            )
        })
    }
}
