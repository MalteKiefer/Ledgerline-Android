pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.google\\.dagger.*"); includeGroupByRegex("com\\.google\\.testing\\.platform.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.google\\.dagger.*"); includeGroupByRegex("com\\.google\\.testing\\.platform.*") } }
        mavenCentral()
    }
}
rootProject.name = "Ledgerline-Android"
include(":app")
