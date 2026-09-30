// `:app` 随包产物装配（审查步骤 1：原 `app/build.gradle.kts` 内联两任务迁入约定，
// 机器路径全清 —— /root、/tmp 类缺省一律不入构建脚本）：
//   · prepareBridgeDistAssets（§12.4 facade dist → assets/bridge-dist/）
//   · prepareEngineNativeLibs（§19 引擎三件 + libopencv 选填 + addon 随包）
// 「三件齐/半套红/全无警」与选填件「缺位只 warn」语义逐字保留；ANDROID_NDK_HOME
// 无缺省且只在三件齐分支必填（没 NDK 的机器走「全无 → 警告」照常 assemble）。
import java.io.File

// facade dist 随包的生成位（§12.4）：声明须在 android.sourceSets 引用之前（kts 顺序求值）。
val bridgeDistAssetsDir = layout.buildDirectory.dir("generated/bridgeDistAssets/bridge-dist")

// ── facade dist 随包（§12.4 资产交付轨）──────────────────────────────────────
// `bridge/js/dist` 是 git 跟踪的 tsc 产物（CI 无 npm build 也在 —— 与 E2E 对 dist 的
// 同一条判据：缺 = 仓库破损）。构建期拷进 assets/bridge-dist/，装配期由
// BridgeDistDeploy 落位到 filesDir/node_modules/auto（require('auto') 的解析点）。
// 不走 npm build 依赖：随包的是**已提交**的 dist，tsc 只在改 src 时由开发者重跑。
val prepareBridgeDistAssets = tasks.register("prepareBridgeDistAssets") {
    val srcDist = rootProject.layout.projectDirectory.dir("bridge/js/dist")
    inputs.dir(srcDist)
    outputs.dir(bridgeDistAssetsDir)
    doLast {
        val out = bridgeDistAssetsDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        var copied = 0
        srcDist.asFile.listFiles()?.forEach { f ->
            if (f.isFile) {
                f.copyTo(File(out, f.name), overwrite = true)
                copied++
            }
        }
        // 空 dist 不落盘（与 BridgeDistDeploy 的空字节防线同精神：0 个文件的 assets
        // 目录 = "没货"，部署侧如实空报告 —— 但这里更该红：dist 缺件是仓库破损）。
        require(copied > 0) { "bridge/js/dist 无文件可随包（仓库破损？srcDist=$srcDist）" }
        require(File(out, "bootstrap.js").isFile) {
            "dist 缺 bootstrap.js（attachNative 打包入口的落点，§12.4）"
        }
        require(File(out, "index.js").isFile) {
            "dist 缺 index.js（require('auto') 的缺省入口，§12.1）"
        }
    }
}

// ── 引擎二进制随包（§19 jniLibs 交付轨）────────────────────────────────────────
// 三个来源都不在 git（NDK/node-runtime-build 产物），所以**不能**像 bridge/js/dist
// 那样缺了就红 —— 分层诚实：
//   · 三件齐（noden + libnode + libc++_shared）→ 拷进 generated/engineNativeLibs/arm64-v8a/
//     （noden 改名 libnoden.so —— PackageManager 只提取 *.so，exec 要真文件）；
//   · 半套（有 noden 没 libnode，或反过来）→ **红**：半个交付比没交付更糟
//     （APK 看起来有引擎、设备上必 exit 2；预检虽点名，但那是运行期才炸的形态）；
//   · 全无 → 警告 + 空产出：装配照过（开发机没跑 build-native.sh 不挡 assemble），
//     设备侧 execute 预检点名绝对路径（已有单测）。
// addon 独立：.node 不进 jniLibs（PM 不提取非 .so），走 assets/bridge-addon/。
val engineNativeLibsDir = layout.buildDirectory.dir("generated/engineNativeLibs")
val engineAddonAssetsDir = layout.buildDirectory.dir("generated/engineAddonAssets/bridge-addon")

val prepareEngineNativeLibs = tasks.register("prepareEngineNativeLibs") {
    val nodenSrc = rootProject.layout.projectDirectory
        .file("engine/node-process/build/native-local/noden")
    val addonSrc = rootProject.layout.projectDirectory
        .file("engine/node-process/build/native-local/bridge_native.node")
    // libnode 候选序（先命中先用）：显式 env → node-runtime-build 出口。
    // 本机临时出口不入表（审查步骤 1 清机器路径）：复现旧配方 export LIBNODE=/path/to/libnode.so。
    val libnodeCandidates = listOfNotNull(
        System.getenv("LIBNODE")?.let { File(it) },
        rootProject.layout.projectDirectory.file("node-runtime-build/out/libnode.so").asFile,
    )
    // libc++_shared：libnode 的 NEEDED（readelf 实证），NDK sysroot 同款 ABI。
    // ANDROID_NDK_HOME 无缺省（审查步骤 1：机器路径不入构建脚本），且求值刻意留在
    // doLast 的「三件齐」分支里 —— 全无 → 警告 的分支没 NDK 也要能 assemble。
    // r28c 与 node-runtime-build/VERSIONS.env 的 NDK_VERSION 同源。
    // libopencv.so 候选位（§9.2 图像管线）：显式 env → node-runtime-build 出口
    // （build-opencv.sh 的 OUT）→ image-native.yml 的 artifact 落点位。
    // 与引擎三件套**同一条选填纪律**：缺位不红（装配侧 JniOps.loadOrNull() 拿不到
    // so 即不喂分析器，桥对 images.* 回 ERR_NOT_IMPLEMENTED），在位才随包。
    // 装载名与文件名必须同为 opencv：JniOps.loadLibrary("opencv") 找的是 libopencv.so。
    // 本机临时验证位不入表（审查步骤 1）：复现旧配方 export LIBOPENCV=/path/to/libopencv.so。
    val libopencvCandidates = listOfNotNull(
        System.getenv("LIBOPENCV")?.let { File(it) },
        rootProject.layout.projectDirectory
            .file("node-runtime-build/out-opencv/libopencv.so").asFile,
    )
    outputs.dir(engineNativeLibsDir)
    outputs.dir(engineAddonAssetsDir)
    // 来源不在 git：每次装配现查现拷（outputs.upToDateWhen false —— 否则 build-native.sh
    // 刚重编的 noden 会被"outputs 已存在"跳过，装进 APK 的还是旧件）。
    outputs.upToDateWhen { false }
    doLast {
        val abiDir = engineNativeLibsDir.get().asFile.resolve("arm64-v8a")
        abiDir.deleteRecursively()
        abiDir.mkdirs()
        val addonOut = engineAddonAssetsDir.get().asFile
        addonOut.deleteRecursively()
        addonOut.mkdirs()

        val noden = nodenSrc.asFile.takeIf { it.isFile }
        val libnode = libnodeCandidates.firstOrNull { it.isFile }
        when {
            noden != null && libnode != null -> {
                val ndkHome = System.getenv("ANDROID_NDK_HOME") ?: throw GradleException(
                    "三件齐要随包 libc++_shared.so，但缺 ANDROID_NDK_HOME（export " +
                        "ANDROID_NDK_HOME=/path/to/android-ndk-r28c；版本见 " +
                        "node-runtime-build/VERSIONS.env 的 NDK_VERSION）",
                )
                val cxxShared = File(
                    ndkHome,
                    "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
                )
                if (!cxxShared.isFile) {
                    throw GradleException(
                        "libnode 在但 libc++_shared 缺：$cxxShared（libnode 的 NEEDED，" +
                            "缺它设备上 dlopen 必败 —— 检查 ANDROID_NDK_HOME=$ndkHome）",
                    )
                }
                noden.copyTo(File(abiDir, "libnoden.so"), overwrite = true)
                libnode.copyTo(File(abiDir, "libnode.so"), overwrite = true)
                cxxShared.copyTo(File(abiDir, "libc++_shared.so"), overwrite = true)
                logger.lifecycle(
                    "[engine-natives] 三件齐 → lib/arm64-v8a/{libnoden.so,libnode.so,libc++_shared.so}",
                )
            }
            noden != null || libnode != null -> throw GradleException(
                "半个引擎交付比没交付更糟：noden=${noden?.absolutePath ?: "缺"} " +
                    "libnode=${libnode?.absolutePath ?: "缺"} " +
                    "（两者都来自本机构建：build-native.sh 出 noden、node-runtime-build/out " +
                    "出 libnode —— 补齐其一再 assemble，或 export LIBNODE=/path/to/libnode.so 显式给）",
            )
            else -> logger.warn(
                "[engine-natives] 未交付（noden/libnode 都缺）：APK 无引擎二进制。" +
                    "跑 engine/node-process/scripts/build-native.sh + node-runtime-build 出 " +
                    "libnode（或 export LIBNODE=…）后再 assemble；设备侧 execute 预检会点名。",
            )
        }

        // libopencv.so（§9.2 图像面）：选填件，与引擎三件套同目录同纪律（有就随包，
        // 无则不红 —— 装配侧据此不喂 images 分析器，脚本拿到的是诚实的 NOT_IMPLEMENTED）。
        val libimg = libopencvCandidates.firstOrNull { it.isFile }
        if (libimg != null) {
            if (libimg.length() == 0L) {
                throw GradleException(
                    "libopencv.so 源是 0 字节：${libimg.absolutePath}" +
                        "（空 so 落包 = 运行期 UnsatisfiedLinkError，比缺件更难查）",
                )
            }
            libimg.copyTo(File(abiDir, "libopencv.so"), overwrite = true)
            logger.lifecycle(
                "[engine-natives] libopencv.so → lib/arm64-v8a/（§9.2 图像面；" +
                    "source=${libimg.absolutePath}）",
            )
        } else {
            logger.warn(
                "[engine-natives] libopencv.so 未交付（候选位 = node-runtime-build/" +
                    "out-opencv/ 或 export LIBOPENCV=…）：images.* 运行时如实 " +
                    "ERR_NOT_IMPLEMENTED —— 跑 node-runtime-build/scripts/build-opencv.sh " +
                    "或下载 image-native.yml 的 artifact。",
            )
        }

        // addon（独立选填件）：有就随包成 assets/bridge-addon/bridge_native.node。
        val addon = addonSrc.asFile
        if (addon.isFile) {
            if (addon.length() == 0L) {
                throw GradleException("addon 源是 0 字节：${addon.absolutePath}（空 .node 落包=运行期 SyntaxError）")
            }
            addon.copyTo(File(addonOut, "bridge_native.node"), overwrite = true)
            logger.lifecycle("[engine-natives] addon → assets/bridge-addon/bridge_native.node")
        } else {
            logger.warn(
                "[engine-natives] addon 未交付（${addon.absolutePath}）：装配期不注入 " +
                    "AUTOSCRIPT_BRIDGE_ADDON，脚本照跑、桥调用点 ERR_ENGINE_STOPPED（选填纪律）。",
            )
        }
    }
}

// 资产合并前必须先生成（AGP 的 preBuild 每变体都有；matching 覆盖配置期尚未注册的情形）。
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareBridgeDistAssets)
    dependsOn(prepareEngineNativeLibs)
}
