---
name: 你的称呼
preferences:
  称呼: 直接叫名字，不要用系统默认称呼
  语气: 简洁直接
rules:
  AI称呼: 回答中不要提用户姓名
  AI格式: 列表用「-」，避免 markdown 表格
tags:
  - 个人
---

# 个人档案

**此文件为格式示例（git 提交），真实档案是 `data/identity/profile.md`（隐私，不提交）。**

在 frontmatter 中填写：
- `preferences`：静态偏好（缩进子键值对，如 `称呼: ...`）
- `rules`：AI 协作规则（缩进子键值对，如 `AI称呼: ...`）
- `tags`：标签列表（`- 标签`）

AI 每次对话都会读取此档案注入上下文。
