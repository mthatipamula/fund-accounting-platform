// Shared by the Spring services: JWT security (OIDC), RFC 7807 errors, request logging context.
plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1") }
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-validation")
    compileOnly("org.springframework:spring-tx")   // DataIntegrityViolation and optimistic locking errors
}
