package com.adaiadai.core.kernel.account;

import java.time.LocalDate;

/**
 * Account — 账号实体（系统级，不属于用户数据层）。
 * <p>
 * 多账号功能层（v1.0.0）：账号由 adai-admin 后台管理创建（不做注册），
 * adai-app 首屏从账号列表选择进入。seed 管理员 {@code adai} 由 AccountFileRepository 预置。
 *
 * @param userId    账号 ID（唯一，[a-zA-Z0-9_-]+）
 * @param role      admin / user（admin 为管理端账号）
 * @param enabled   是否可用（禁用的账号不可选号进入）
 * @param createdAt 创建日期
 */
public record Account(String userId, String role, boolean enabled, LocalDate createdAt) {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    /** 内置管理员账号：不可删除 / 不可禁用（防锁死系统）。 */
    public static final String SEED_ADMIN_ID = "adai";
}
