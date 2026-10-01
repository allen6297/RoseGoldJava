import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        bundledModule("intellij.spellchecker")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
}

tasks.processResources {
    from(rootDir) {
        include("builtin/std/**")
        include("native/**")
    }
}

tasks.register<JavaExec>("rg") {
    group = "application"
    description = "RoseGold CLI (check, run, test, fmt, new, ir, llvm). Example: ./gradlew rg --args=\"run examples/hello.rg\""
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.named("main").get().output
    mainClass.set("com.rosegoldc.lang.Main")
    workingDir = rootDir
    standardInput = System.`in`
}
