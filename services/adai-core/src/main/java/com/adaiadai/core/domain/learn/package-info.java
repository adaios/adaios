/**
 * learn — 学习沉淀领域（learn 插件，RFC 20260829）。
 * <p>
 * 把「外部内容（视频/文章/字幕）→ 个人知识卡片」变成标准流水线：喂入 → AI 结构化卡片
 * （File First md，按用户落 {@code data/{userId}/learn/}）→ 三通道呈现（对话流/资产页/问答）。
 * <p>
 * V1 只做后端流水线：独立端点喂入（2026-09-06 用户拍板，仿截图入账先例，不污染记录/记忆/Feed）、
 * 卡片落盘、trade_related 仅标记不联动规则库。资产页/问答注入为 L2。
 */
package com.adaiadai.core.domain.learn;
