plugins {
    `java-library`
    `maven-publish`
}

group = "dev.vertex"
// Tracks VertexBrand.ENGINE_VERSION, not the Minecraft version: the module API is
// deliberately independent of which Folia the engine is built on.
version = "0.1.0"

description = "Compile-time API for Vertex Engine modules. Contains no Minecraft types."

java {
    withSourcesJar()
}

publishing {
    // Oxide and any other module compile against this artifact, and only against this artifact.
    // GitHub Packages rather than repo.papermc.io: this is not a Paper artifact, and both
    // consumers are in the same account.
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/dronzer-tb/vertex-engine")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "vertex-engine-api"
        }
    }
}

dependencies {
    // The root build adds this without a version, taking it from a BOM the Folia subprojects
    // declare and this one does not. Pinned here to the same version Paper's API uses.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}
