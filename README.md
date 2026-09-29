# AutoScript

内置 Node.js 的安卓自动化平台（对标 AutoJsPro v9）：脚本作者用 Node.js/JavaScript 编写自动化脚本，运行在安卓系统之上。

- `docs/framework-design.md`：框架架构设计（**契约的单一事实来源**：进程模型 / 桥接层 / 执行层 / npm 支持 / 安全模型 / 路线图）。
- `docs/design-decisions.md`：决策记录（已拍板项 + 被推翻/改过的口径，原口径不删）。
- `docs/design-status.md`：落地台账（哪些已落地、哪些还是接口期、哪次实测推翻了什么）。
- `.claude/skills/`：项目内 Claude Code 技能；`skill-designer` 用于设计、创建新技能。