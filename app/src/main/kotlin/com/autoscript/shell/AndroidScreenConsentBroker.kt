package com.autoscript.shell

import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenConsentHolder
import com.autoscript.domain.automation.ScreenConsentInbox
import com.autoscript.domain.automation.ScreenConsentOutcome
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.platform.capabilities.device.AndroidScreenConsentToken

/**
 * 投屏同意征询（§9.2）：`ScreenConsentHolder` 里的 Activity 宿主 → 一次系统对话框 →
 * 设备层凭据（[AndroidScreenConsentToken]）。
 *
 * 住 `:app` 装配包的理由与 [PlatformWiring] 同：它是**唯一**同时够得着"呈现层注册的宿主"
 * （`:domain` 的 `ScreenConsentHolder`）与"设备层凭据类型"（`:platform:capabilities`）的地方。
 *
 * 三种落点**都不撒谎**，且**一律 `ERR_CAPTURE_DENIED`**（脚本可判别的分类错误）：
 * - 宿主未注册（应用没有存活界面）→ 没人能问用户；
 * - 宿主拉不起对话框（`launched = false`）→ 这次没能把用户送到授权页；
 * - 用户取消 / 系统拒（`granted = false`）→ 如实回取消。
 *
 * **先领待领凭据**（[ScreenConsentInbox]）：用户可能在能力中心已经点过一次「去授权」
 * 并同意了 —— 那份结果就存在待领口里。这里先一次性领走，**不再弹一次对话框**
 * （投屏没有持久 grant，弹第二次等于让用户把刚做过的事重做一遍）。领不到才去问用户。
 *
 * **绝不**在任一条落点上改走别的截图通道（a11y 截图是另一条独立能力）——
 * 静默换通道会让脚本以为在录屏，实际拿到的是 333ms 节流的 a11y 帧。
 */
class AndroidScreenConsentBroker(
    private val inbox: ScreenConsentInbox = ScreenConsentInbox.shared,
) : ScreenConsentBroker {

    override suspend fun requestConsent(): ScreenConsentToken {
        inbox.claim()?.let { return tokenOf(it) }
        val host = ScreenConsentHolder.host
            ?: throw AutojsException(
                ErrorCode.ERR_CAPTURE_DENIED,
                "没有可用的界面来征询投屏授权（应用不在前台/界面已销毁）：本次不开会话，也不改走 a11y 截图",
            )
        val outcome = host.requestScreenConsent()
        if (!outcome.launched) {
            throw AutojsException(
                ErrorCode.ERR_CAPTURE_DENIED,
                "系统投屏授权对话框拉不起来（本次申请没能把用户送到授权页）",
            )
        }
        return tokenOf(outcome)
    }

    /** 一次同意结果 → 设备层凭据（`granted` 如实透传，拒/取消由设备层分类）。 */
    private fun tokenOf(outcome: ScreenConsentOutcome): ScreenConsentToken = AndroidScreenConsentToken(
        resultCode = outcome.resultCode,
        data = outcome.payload as? android.content.Intent,
        // `resultCode == -1` 就是 `Activity.RESULT_OK`（`:domain` 的 RESULT_OK 是同一份契约，
        // 见 `ScreenConsentOutcome.RESULT_OK`）—— 不在这里另写一个常量。
        granted = outcome.granted,
    )
}
