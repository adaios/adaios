package com.adaiadai.core.domain.learn;

import java.util.List;

/**
 * LearnCardPatch — 学习卡片编辑补丁（V2 2026-09-07，learn V2 审查 P2-learn6/7 修复）。
 * <p>
 * 字段 null = 保留原值，非 null = 覆盖（含清空传空列表/空串）。编辑在仓储锁内
 * 读-改-写原子完成（P2-learn6：并发两 PATCH 不再丢更新），且写盘基于原文件做
 * 受管键/段手术替换——手工未知 frontmatter 键与未知正文段原样保留（P2-learn7：
 * File First md 即真相源，编辑不得抹用户手工内容）。
 *
 * @param coreView     核心观点（一句话；null=保留）
 * @param keyPoints    关键要点列表（null=保留）
 * @param questions    我的疑问列表（null=保留）
 * @param retell       复述段（null=保留）
 * @param tradeRelated 交易相关（null=保留；非 trading 卡恒 false 由 LearnCard 收敛）
 * @param tradeNote    交易备注（null=保留）
 * @param tags         标签列表（null=保留）
 */
public record LearnCardPatch(
        String coreView,
        List<String> keyPoints,
        List<String> questions,
        String retell,
        Boolean tradeRelated,
        String tradeNote,
        List<String> tags) {}
