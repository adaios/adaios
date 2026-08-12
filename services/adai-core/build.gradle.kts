plugins {
    id("java")
    id("java-library")
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.3.9"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.adaios"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    enabled = true
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Database — Phase 2: 当需要文件索引时启用
    // implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // runtimeOnly("com.mysql:mysql-connector-j")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Jackson JSR310（LocalDateTime 序列化）
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") {
    enabled = false
}

// REVIEW #187/#228：部署为 jar-only（生产无源码树）→ 端点计数打进 classpath 资源。
// 单一口径来源（dev/生产都读这份生成数据）：逐行扫，跳过注释行（javadoc/块注释/行注释），
// 防把注释里出现的注解名计入。ProjectStatusAppService 不再有第二个扫源码实现。
val generateEndpointsFile = tasks.register("generateEndpointsFile") {
    val srcDir = file("src/main/java/com/adaiadai/core/interfaces")
    // REVIEW #240：声明 inputs.dir —— 源目录变化时增量构建才会失效重扫，
    // 否则只声明 outputs.dir 会因 up-to-date 判定恒判最新，新增/删除端点后端点数陈旧。
    inputs.dir(srcDir)
    val outDir = layout.buildDirectory.dir("resources/main/META-INF")
    outputs.dir(outDir)
    doLast {
        val dir = srcDir
        val anns = listOf("GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping")
        var count = 0L
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { f ->
                f.readLines().forEach { raw ->
                    val t = raw.trim()
                    if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEach
                    for (a in anns) {
                        if (Regex("@${a}\\b").containsMatchIn(raw)) count++
                    }
                }
            }
        }
        val out = outDir.get().asFile.resolve("endpoints.txt")
        out.parentFile.mkdirs()
        out.writeText("$count\n")
    }
}

// 让 processResources 依赖生成任务，保证 bootJar 包含 endpoints.txt
tasks.named("processResources") {
    dependsOn(generateEndpointsFile)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs = listOf("-Djava.net.useSystemProxies=false")
}
