plugins {
    id("autoscript.jvm")
}

// npm 安装/审批/镜像验证 + `npm` 命名空间桥处理器（docs §10/§14）。
// 2026-09-30 审查步骤 5 自 :app-service:packager 拆出（npm/ 对父包零 import，切分即净）。
// 纯 JVM（零 `import android.`）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    // 真实 npm e2e 默认跳过：CI 走 -PskipNpmE2E（本机不带 flag 即跑，宿主机 node+npm 存在才启用）。
    // 清单：HostNodeNpmE2ETest（install/ci 真跑）+ NpmCacheSeedDeployerTest 的金标准
    // （仅凭种子 npm ci --offline）——两者都要拉真 npm 进程。
    if (project.hasProperty("skipNpmE2E")) {
        exclude("**/HostNodeNpmE2ETest*")
        exclude("**/NpmCacheSeedDeployerTest*")
    }
}
