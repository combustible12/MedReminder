pluginManagement { repositories { google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.testing.*") } }; maven("https://repo1.maven.org/maven2"); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.testing.*") } }; maven("https://repo1.maven.org/maven2"); mavenCentral() } }
rootProject.name = "MedReminder"
include(":app")
