package com.autoscript.build

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * 预编译约定脚本里的版本目录读口 —— included build 的 precompiled script **拿不到** `libs`
 * 类型安全访问器（实测 generatePrecompiledScriptPluginAccessors 不为它生成；`libs.xxx`
 * 直接写是 Unresolved reference）。这里在运行期按名取同一份 catalog，别名与
 * gradle/libs.versions.toml 同源。
 */
object Catalogs {
    fun libs(project: Project): VersionCatalog =
        project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

    /** 目录里的 int 版本号（compileSdk/minSdk 等）。 */
    fun int(project: Project, alias: String): Int =
        libs(project).findVersion(alias).get().toString().toInt()
}
