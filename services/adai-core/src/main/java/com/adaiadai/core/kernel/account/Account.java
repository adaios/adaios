package com.adaiadai.core.kernel.account;

import java.time.LocalDate;
import java.util.List;

/**
 * Account — 账号实体（系统级，不属于用户数据层）。
 * <p>
 * 多账号功能层（v1.0.0）：账号由 adai-admin 后台管理创建（不做注册），
 * adai-app 首屏从账号列表选择进入。seed 管理员 {@code adai} 由 AccountFileRepository 预置。
 * <p>
 * plugins（RFC 20260814 Domain=插件模型）：启用的插件名列表（trading/project）。
 * 新账号默认空 = 只有 Kernel 基础服务；seed adai = [trading, project]（owner 受控插件）。
 * 老账号文件无该字段 → 紧凑构造器归一为空列表。
 *
 * @param userId    账号 ID（唯一，[a-zA-Z0-9_-]+）
 * @param role      admin / user（admin 为管理端账号）
 * @param enabled   是否可用（禁用的账号不可选号进入）
 * @param createdAt 创建日期
 * @param plugins   启用的插件名列表（RFC 20260814，默认空）
 */
public record Account(String userId, String role, boolean enabled, LocalDate createdAt, List<String> plugins) {

    /** 紧凑构造器：归一 null/可变列表（老 JSON 无 plugins 字段时 Jackson 传 null）。 */
    public Account {
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /** 旧签名兼容（无插件 = 只有基础服务）。 */
    public Account(String userId, String role, boolean enabled, LocalDate createdAt) {
        this(userId, role, enabled, createdAt, List.of());
    }

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    /** 内置管理员账号：不可删除 / 不可禁用（防锁死系统）。 */
    public static final String SEED_ADMIN_ID = "adai";
}
