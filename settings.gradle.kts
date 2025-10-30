pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
        flatDir {
            dirs("libs")
        }

    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            credentials {
                username = "mapbox"
                // ¡IMPORTANTE! Reemplaza esto con tu token secreto de Mapbox
                password = "sk.eyJ1Ijoic2tpbm55cGl6emFhIiwiYSI6ImNtaGN2bWJtZDBmcjgycnB0OHp5dmc2cHUifQ.54ksiC9Vkz-WtuaqDhaFRQ"
            }
        }
    }
}

rootProject.name = "GEOFENCEMAPBOX"
include(":app")
