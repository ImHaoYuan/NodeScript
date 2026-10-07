package com.autoscript.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.autoscript.domain.editor.SyntaxKind
import com.autoscript.domain.editor.SyntaxSpan

/**
 * 把原生报回来的区间贴成一份着色正文（[AnnotatedString]），供 `BasicTextField` 的
 * `visualTransformation` 用。
 *
 * **只换样式、不换字符**：`AnnotatedString` 的正文与入参 [text] 逐字相同，不插行分隔符
 * （插了就会连带把光标的 source↔transformed 偏移和行数算错）。返回 null = 一条都没贴上
 * （调用方退回纯 [String]，省掉一次无谓的转换）。
 *
 * 三处**必须**丢弃的区间（判据都在这里，不散在渲染里）：
 * - 越界或空区间 —— 结果算的是**上一拍**的正文（防抖期间用户还在敲字），偏移可能按旧正文算；
 * - 与已贴上的区间重叠 —— `AnnotatedString` 的 offsetMap 不接受重叠区间，硬贴会抛；
 * - 完全越界的整条丢弃即可，不需要截断：截断出来的半句染上去比不染更误导。
 *
 * @param colorOf kind → 颜色（取色口见 `:ui` 的 `syntaxColor`，主题在这里不参与判断）。
 */
internal fun highlightedText(
    text: String,
    spans: List<SyntaxSpan>,
    colorOf: (SyntaxKind) -> Color,
): AnnotatedString? {
    if (spans.isEmpty() || text.isEmpty()) return null
    val builder = AnnotatedString.Builder(text)
    var applied = 0
    var previousEnd = 0
    for (span in spans) {
        if (isPaintable(span, previousEnd, text.length)) {
            builder.addStyle(SpanStyle(color = colorOf(span.kind)), span.start, span.end)
            previousEnd = span.end
            applied++
        }
    }
    return if (applied == 0) null else builder.toAnnotatedString()
}

/** 非空、在正文范围内、且不与上一条已贴区间重叠（`AnnotatedString` 不接受重叠样式之外的乱序）。 */
private fun isPaintable(span: SyntaxSpan, previousEnd: Int, length: Int): Boolean =
    span.start in previousEnd until span.end && span.end <= length
