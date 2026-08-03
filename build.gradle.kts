plugins {
    `java`
}

group = "punishnotify"
version = "1.1.3"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "papermc-snapshots"
        url = uri("https://repo.papermc.io/repository/maven-snapshots/")
    }
    maven {
        name = "essentialsx"
        url = uri("https://repo.essentialsx.net/releases/")
    }
    maven {
        name = "essentialsx-snapshots"
        url = uri("https://repo.essentialsx.net/snapshots/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.essentialsx:EssentialsX:2.20.1") {
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("PunishNotify-${project.version}.jar")
}
