package com.autoscript.ui.theme

import androidx.compose.ui.graphics.Color
import com.autoscript.domain.editor.SyntaxKind

/**
 * 语法着色的取色口（**只此一处**）。
 *
 * 键是 `:domain` 的 [SyntaxKind]，`when` 穷尽枚举 —— 桥面新增一种 kind 时这里编译期就红，
 * 不会安静地少染一类（少染是高亮面里最难发现的一种错：没有报错、没有崩溃，只是某一类词
 * 一直是正文色）。
 *
 * 取色是**观感参考**而非 TG 抄本（TG 的编辑器本就没有语法着色）：深色档取 IntelliJ /
 * VSCode 暗色主题的中位观感，浅色档取同族配色。正文色 [Colors.text] 一律不动 ——
 * 未命中任何 kind 的文本仍按正文色画，着色只是叠在上面的一层。
 */
fun syntaxColor(kind: SyntaxKind, dark: Boolean): Color = when (kind) {
    SyntaxKind.KEYWORD -> if (dark) Color(0xFFCF8E6D) else Color(0xFF0033B3)
    SyntaxKind.STRING -> if (dark) Color(0xFF6AAB73) else Color(0xFF067D17)
    SyntaxKind.COMMENT -> if (dark) Color(0xFF7F848E) else Color(0xFF8C8C8C)
    SyntaxKind.NUMBER -> if (dark) Color(0xFF2AACB8) else Color(0xFF1750EB)
    SyntaxKind.FUNCTION_NAME -> if (dark) Color(0xFF56A8F5) else Color(0xFF00627A)
    SyntaxKind.OPERATOR -> if (dark) Color(0xFFD0D4DA) else Color(0xFF1A1D21)
    SyntaxKind.TYPE -> if (dark) Color(0xFF4EC9B0) else Color(0xFF7A3E9D)
}
