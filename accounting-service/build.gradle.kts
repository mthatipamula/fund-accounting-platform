// Fund accounting: consumes trade events (Kafka), loads market data (gRPC), runs the accounting engine as of any
// date and as known at any moment. Owns funds, share classes, investor capital, period close and reconciliation.
plugins {
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.9.6"
}

dependencies {
    implementation(project(":service-common"))
    implementation(project(":accounting-engine"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-grpc-client")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The gRPC contract is owned by reference-data-service; the client stubs are generated from the same file.
sourceSets { main { proto { srcDir("../reference-data-service/src/main/proto") } } }
protobuf { plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.83.1" } } }
