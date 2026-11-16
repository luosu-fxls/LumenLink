pluginManagement {
    repositories {
        // 国内镜像优先：直连 dl.google.com / repo.maven.apache.org 在某些网络下会被限速到
        // 几十 KB/s，镜像能快两个数量级。没镜像的环境会自动回落到官方仓库。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "LumenLink"
include(":app")
