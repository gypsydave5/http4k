import org.http4k.internal.ModuleLicense.Apache2

description = "http4k incubator module"

val license by project.extra { Apache2 }

plugins {
    id("org.http4k.community")
}

dependencies {
    api(project(":http4k-core"))
    api(project(":http4k-format-moshi"))

    api(Square.moshi.adapters)
    implementation(project(mapOf("path" to ":http4k-testing-webdriver")))

    compileOnly(platform("org.junit:junit-bom:_"))
    compileOnly("org.junit.jupiter:junit-jupiter-api")

    testImplementation(project(":http4k-client-apache"))

    testImplementation("dev.forkhandles:values4k:_")

    testImplementation(testFixtures(project(":http4k-core")))
    testImplementation(project(":http4k-server-helidon"))
    testImplementation(project(":http4k-testing-approval"))
    testImplementation(testFixtures(project(":http4k-api-openapi")))
}
