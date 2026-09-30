package com.autoscript.build

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition

/**
 * 「零跨层泄漏」共享架构门 —— 各模块 ArchitectureTest 里 ClassFileImporter + noClasses +
 * resideIn + dependOnAny 四段样板的唯一落点；测试类只声明**本模块的包 + 黑名单**。
 *
 * 由 `autoscript.jvm` / `autoscript.android-library` 约定注入 test 源集（见 build-logic）。
 * 本类住 `com.autoscript.build` 包（各模块黑名单均未列入该包），被测类依赖它不违规。
 *
 * 形状选最通用的一条：`noClasses().resideIn(rule).should().dependOn(forbidden)`——
 * 复合规则（`and().resideOutsideOfPackage` 之类）不硬塞进来，留在各模块测试里自写。
 */
class ArchGate(
    /** ClassFileImporter.importPackages 的分析根。 */
    private val importedPackage: String,
    /** 规则适用包；缺省 = 分析根自身（`<pkg>..`）。 */
    private val rulePackage: String = "$importedPackage..",
) {
    /** 断言：本模块类不得依赖 [forbidden] 中任一包。 */
    fun noLeakTo(vararg forbidden: String) {
        val classes = ClassFileImporter().importPackages(importedPackage)
        ArchRuleDefinition.noClasses()
            .that().resideInAPackage(rulePackage)
            .should().dependOnClassesThat().resideInAnyPackage(*forbidden)
            .check(classes)
    }
}
