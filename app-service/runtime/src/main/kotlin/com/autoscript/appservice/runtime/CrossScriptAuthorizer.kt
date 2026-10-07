package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.permission.BridgeCapability
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot

/**
 * 跨脚本目标授权（A5，§11 / §16「RuntimeChannel 按来源分级过滤；低信任不得控制高信任引擎」）。
 *
 * 判据都是**纯函数**（拿「谁在调、动谁、目标被授权到什么」当参数，不自己持状态）——
 * 这样它可以在没有真引擎、没有桥的情况下被穷举测试，也不会与
 * [RuntimeController.activeRunIds] 那份在途表产生第二份事实。
 *
 * 1. **自我操作不需要跨脚本权限**：`engines.stop(self)` / `engines.status(self)` 是脚本对
 *    **自己这次执行**的生命周期操作（`EngineSessionImpl` 的 `cancel`/`onExit` 就靠它们），
 *    任何掩码都必须放行 —— 否则窄掩码脚本连自己都停不掉。
 * 2. **触达别的执行需要相应能力位**：`CROSS_SCRIPT_OBSERVE`（看）/ `CROSS_SCRIPT_CONTROL`（停）。
 * 3. **还要求目标的授权快照不比调用方更强**（[authorizeTarget] 的 [target] 参数）：
 *    光看调用方有没有控制位是不够的 —— 那样一个**低信任**（窄掩码）的调用方只要自己
 *    有控制位，就能停掉一个**高信任**（全量掩码）的执行。所以还要比目标那一侧：
 *    调用方掩码必须**覆盖**目标掩码。目标授权**查不到**时 fail closed（见下）。
 *
 * **「目标在不在途」不在这里判**：那是 `RuntimeController.authorizeTarget` 的活 ——
 * 在途表就在它手里，把整张表传进纯函数只为判一次包含关系既绕又容易造出第二份事实。
 * 调用方保证 [authorizeTarget] 只在**目标确实在途**时被调用；目标不在途 → 由调用方
 * 回 `ERR_NOT_FOUND`（`engines.status`/`stop` 既有的诚实口径：结算后无状态可读，
 * 也不给「这个号存在过」这类探测面）。
 *
 * **边界**：本类只回答「这次桥调用能不能指向那个 runId」。它不提供隔离，也不阻止
 * 同 UID 代码绕开桥直接操作进程（§11.3 第 1 条）。
 */
class CrossScriptAuthorizer {

    /**
     * 目标授权：调用方 [caller] 要操作 [targetRunId]（**调用方须已确认该 run 在途**）。
     *
     * @param required 触达**别的**执行所需的能力位。
     * @param target **目标那次执行的授权快照**（在途表里存的那一份）。null = 目标在途但宿主
     *   没记下它的授权 —— **fail closed**：不确定目标信任档时按"比调用方强"处理，
     *   只允许调用方本来就够得着的操作（即要求调用方覆盖 [required] 之外还要过下面那条）。
     * @throws AutojsException `ERR_PERMISSION_DENIED`（缺能力位或目标更强）。
     */
    fun authorizeTarget(
        caller: AuthenticatedRunContext,
        targetRunId: Long,
        required: BridgeCapability,
        target: ScriptAuthorizationSnapshot?,
    ) {
        if (targetRunId == caller.engineRunId) return          // 自己：任何掩码都放行
        if (!caller.capabilityMask.contains(required)) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "本次执行未授权触达其他执行（需要 $required，持有 ${caller.capabilityMask}）",
            )
        }
        // 目标信任档：查不到 → fail closed（当作"目标更强"）。有快照则要求调用方覆盖它。
        // 这一步挡的是「低信任调用方有控制位 → 停高信任执行」：调用方掩码必须 >= 目标掩码。
        if (target == null || !caller.capabilityMask.covers(target.mask)) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "不得触达授权不低于本次执行的执行" +
                    "（调用方 ${caller.capabilityMask}，目标 ${target?.mask ?: "授权未知"}）",
            )
        }
    }

    /**
     * 派生执行授权（`engines.exec` 的**目标侧**判据）：调用方 [caller] 要拉起新执行。
     *
     * 除了 `CROSS_SCRIPT_CONTROL`，还要求调用方掩码**覆盖**它将要派生的那份掩码
     * （`childMask`）—— **跨脚本不得提权**：窄掩码的脚本不能靠 exec 一个高信任项目
     * 去拿到自己本来没有的面（那等于把 exec 变成提权通道）。
     * 宿主发起的 run（[caller] 为 null）不受此限：它是链的根。
     *
     * @param childMask 子执行的掩码 —— 取自**已经算好的**那份子授权快照（见
     *   `RuntimeController.start`：同一个 [authorization] 决定既用于本判据，也用于签发，
     *   不存在"检查一套、签发另一套"）。
     * @throws AutojsException `ERR_PERMISSION_DENIED`（缺控制位或会造成提权）。
     */
    fun authorizeStart(caller: AuthenticatedRunContext?, childMask: CapabilityMask) {
        if (caller == null) return                             // 宿主/用户发起：链的根
        if (!caller.capabilityMask.contains(BridgeCapability.CROSS_SCRIPT_CONTROL)) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "本次执行未授权启动其他脚本（需要 ${BridgeCapability.CROSS_SCRIPT_CONTROL}，持有 ${caller.capabilityMask}）",
            )
        }
        if (!caller.capabilityMask.covers(childMask)) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "派生执行不得超出本次执行的授权（子 $childMask 超出父 ${caller.capabilityMask}）",
            )
        }
    }
}
