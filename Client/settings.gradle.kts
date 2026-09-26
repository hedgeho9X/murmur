/** Android 应用与生成 API 模块的统一构建入口。 */
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "murmur"
include(":app", ":api")
