package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * AccountController — 账号管理端点（v1.0.0 多账号功能层）。
 * <p>
 * GET    /api/v1/accounts              → 账号列表（adai-admin 管理，需登录 + role=admin）
 * GET    /api/v1/accounts/available    → 可用账号列表（仅 enabled，登录即可，产品端遗留选号）
 * POST   /api/v1/accounts              → 建号（无注册，adai-admin 后台建，可带初始密码）
 * PATCH  /api/v1/accounts/{userId}     → 更新（启用/禁用、角色、插件、密码重置）
 * DELETE /api/v1/accounts/{userId}     → 删除
 * <p>
 * 内置管理员 {@link Account#SEED_ADMIN_ID}（admin）不可删除 / 不可禁用 / 不可降级（防锁死系统）。
 * <p>
 * REVIEW #178：鉴权并入统一登录（AuthFilter role=admin 门禁，X-Admin-Token 退役）；
 * 所有响应经 {@link AccountView} 过滤 <b>passwordHash</b>（bcrypt 哈希不落 API 响应）；
 * PATCH 保留既有 passwordHash（老实现 5 参构造把哈希清空 = 改 enabled 即清密码的 bug）；
 * PATCH 携带 password 时为「重置密码」（≥8 位，重置后踢除该账号全部会话——被重置者需重新登录）。
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private static final String USER_ID_PATTERN = "[a-zA-Z0-9_-]+";

    /**
     * 保留字 userId（task-log #149）：{@code default} 是历史遗留测试数据目录名
     * （{@code data/default/}，测试夹具语义），真实账号同名会与测试数据混淆——禁建。
     * seed 管理员 {@code admin} 无需列入：init() 预置存在，createAccount 走「账号已存在」400。
     */
    private static final Set<String> RESERVED_USER_IDS = Set.of("default");

    /** 响应 DTO：与 Account 同字段但剔除 passwordHash（#178：bcrypt 哈希不下发）。 */
    public record AccountView(String userId, String role, boolean enabled,
                              LocalDate createdAt, List<String> plugins) {
        static AccountView of(Account a) {
            return new AccountView(a.userId(), a.role(), a.enabled(), a.createdAt(), a.plugins());
        }
    }

    private final AccountRepository accountRepository;
    private final PluginRegistry pluginRegistry;
    private final PluginService pluginService;
    private final AuthService authService;
    private final com.adaiadai.core.kernel.storage.FileStorage fileStorage;

    public AccountController(AccountRepository accountRepository, PluginRegistry pluginRegistry,
                             PluginService pluginService, AuthService authService,
                             com.adaiadai.core.kernel.storage.FileStorage fileStorage) {
        this.accountRepository = accountRepository;
        this.pluginRegistry = pluginRegistry;
        this.pluginService = pluginService;
        this.authService = authService;
        this.fileStorage = fileStorage;
    }

    /** 账号列表（返回全部，前端按 enabled 过滤选号）。#178：passwordHash 不外泄。 */
    @GetMapping
    public List<AccountView> listAccounts() {
        return accountRepository.findAll().stream().map(AccountView::of).toList();
    }

    /**
     * 可用账号列表（产品端遗留选号端点，登录即可——非 admin 范围例外，AuthFilter 门禁外）。
     * <p>仅返回 {@code enabled=true} 账号的 **userId 最小集**（#215：不暴露 role/enabled/createdAt，
     * 避免 admin 标记等枚举面）；账号由 adai-admin 后台创建，产品端不做注册。
     * RFC 20260901-auth-login 决策 4 后产品端改手输账号登录，本端点仅兼容遗留客户端。
     */
    @GetMapping("/available")
    public List<String> listAvailableAccounts() {
        return accountRepository.findAll().stream()
                .filter(Account::enabled)
                .map(Account::userId)
                .toList();
    }

    /** 建号（无注册，adai-admin 后台建）。 */
    @PostMapping
    public ResponseEntity<?> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        String userId = request.userId().trim();
        if (!userId.matches(USER_ID_PATTERN)) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId 仅允许 [a-zA-Z0-9_-]+"));
        }
        if (RESERVED_USER_IDS.contains(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId 为系统保留字，不可创建: " + userId));
        }
        if (accountRepository.findById(userId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "账号已存在: " + userId));
        }
        String role = request.role() == null ? Account.ROLE_USER : request.role();
        if (!isValidRole(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "role 仅允许 admin/user"));
        }
        List<String> plugins = request.plugins() != null ? request.plugins() : List.of();
        if (!isValidPlugins(plugins)) {
            return ResponseEntity.badRequest().body(Map.of("error", "plugins 仅允许 " + pluginRegistry.all()));
        }
        // RFC 20260901-auth-login：建号可带初始密码（bcrypt），不设密码则无法登录（fail-closed）
        String passwordHash = null;
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("error", "初始密码长度至少 8 位"));
            }
            passwordHash = authService.encodePassword(request.password());
        }
        Account account = accountRepository.save(
                new Account(userId, role, true, LocalDate.now(), plugins, passwordHash));
        pluginService.invalidate(userId);
        log.info("创建账号: {} role={} plugins={} hasPassword={}", userId, role, plugins, passwordHash != null);
        return ResponseEntity.ok(AccountView.of(account));
    }

    /**
     * 更新账号（启用/禁用、角色、插件、密码重置）。内置管理员保护。
     * <p>
     * #178：① passwordHash 必须保留（老实现 5 参构造清空 = 改 enabled 即清密码的 bug）；
     * ② 携带 password（≥8 位）视为重置密码——编码落盘 + 踢除该账号全部会话（被重置者需重新登录）。
     * P1-1：③ 显式禁用（enabled true→false）同为安全敏感操作——同步踢除该账号全部会话
     *（被禁用者立即失效）；搭配 AuthService.validateAndTouch 的账号存在性/enabled 复查双保险。
     */
    @PatchMapping("/{userId}")
    public ResponseEntity<?> updateAccount(@PathVariable String userId,
                                           @RequestBody UpdateAccountRequest request) {
        Optional<Account> existing = accountRepository.findById(userId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean enabled = request.enabled() == null ? existing.get().enabled() : request.enabled();
        String role = request.role() == null ? existing.get().role() : request.role();
        List<String> plugins = request.plugins() != null ? request.plugins() : existing.get().plugins();
        if (!isValidRole(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "role 仅允许 admin/user"));
        }
        if (!isValidPlugins(plugins)) {
            return ResponseEntity.badRequest().body(Map.of("error", "plugins 仅允许 " + pluginRegistry.all()));
        }
        if (isSeedAdmin(userId)) {
            if (!enabled) {
                return ResponseEntity.badRequest().body(Map.of("error", "内置管理员 " + Account.SEED_ADMIN_ID + " 不可禁用"));
            }
            if (!Account.ROLE_ADMIN.equals(role)) {
                return ResponseEntity.badRequest().body(Map.of("error", "内置管理员 " + Account.SEED_ADMIN_ID + " 角色不可变更"));
            }
        }
        // 重置密码（可选）：不携带 → 保留原哈希（#178 修复 PATCH 清密码 bug）
        String passwordHash = existing.get().passwordHash();
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("error", "新密码长度至少 8 位"));
            }
            passwordHash = authService.encodePassword(request.password());
        }
        // P1-1：禁用（enabled true→false）与重置密码均为安全敏感操作——踢除该账号全部会话
        //（被禁用者/被重置者需重新登录，遗留旧会话立即失效；两条件各自判定，可同时命中）
        boolean passwordReset = request.password() != null && !request.password().isBlank();
        boolean disabling = existing.get().enabled() && Boolean.FALSE.equals(request.enabled());
        Account updated = accountRepository.save(
                new Account(userId, role, enabled, existing.get().createdAt(), plugins, passwordHash));
        if (passwordReset) {
            // 重置密码是安全敏感操作：踢除该账号全部会话（被重置者需重新登录）
            int kicked = authService.kickSessions(userId);
            log.info("重置密码: {} (由 admin 操作，踢除会话 {} 个)", userId, kicked);
        }
        if (disabling) {
            // P1-1：禁用账号是安全敏感操作：踢除该账号全部会话（被禁用者立即失效，不再能读写产品端点）
            int kicked = authService.kickSessions(userId);
            log.info("禁用账号: {} (由 admin 操作，踢除会话 {} 个)", userId, kicked);
        }
        pluginService.invalidate(userId);
        return ResponseEntity.ok(AccountView.of(updated));
    }

    /**
     * 合并插件（REVIEW S-R2）：服务端原子 add/remove——根治前端全量 PATCH read-modify-write
     * 并发互覆（快速连点两个开关不再丢）。内置管理员插件受保护（与前端 isProtected 同口径）。
     */
    @PatchMapping("/{userId}/plugins")
    public ResponseEntity<?> mergePlugins(@PathVariable String userId,
                                          @RequestBody MergePluginsRequest request) {
        if (isSeedAdmin(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "内置管理员 " + Account.SEED_ADMIN_ID + " 插件受保护，不可修改"));
        }
        List<String> add = request.add() != null ? request.add() : List.of();
        List<String> remove = request.remove() != null ? request.remove() : List.of();
        if (!isValidPlugins(Stream.concat(add.stream(), remove.stream()).toList())) {
            return ResponseEntity.badRequest().body(Map.of("error", "plugins 仅允许 " + pluginRegistry.all()));
        }
        if (accountRepository.findById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Account updated = accountRepository.mergePlugins(userId, add, remove);
        pluginService.invalidate(userId);
        return ResponseEntity.ok(AccountView.of(updated));
    }

    /**
     * 删除账号。内置管理员不可删。P1-1：删除前先踢除该账号全部会话。
     * <p>
     * <b>task-log #149（2026-09-16 用户拍板）</b>：{@code data/{userId}/} 是不可逆的个人资产，
     * 所以**默认只移除账号 + 踢会话，数据留着**；确实要连数据一起清，必须显式 {@code ?purge=true}
     * （adai-admin 那边还要过一次「输入账号名确认」）。宁可留一份没人用的目录，也不做一次回不去的误删。
     *
     * @param purge true = 连同 {@code data/{userId}/} 一起清理（不可逆）
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deleteAccount(@PathVariable String userId,
                                          @RequestParam(name = "purge", defaultValue = "false") boolean purge) {
        if (isSeedAdmin(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "内置管理员 " + Account.SEED_ADMIN_ID + " 不可删除"));
        }
        // P1-1：删除前先踢除该账号全部会话——账号删除后 findById 已空（AuthService 无法再按会话复查），
        // 先踢后删确保被删账号的旧会话立即失效，不留 30 天滑动有效期的「幽灵会话」
        //（踢会话幂等：账号本就无会话或删除失败时无副作用）。
        authService.kickSessions(userId);
        boolean removed = accountRepository.delete(userId);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        if (!purge) {
            return ResponseEntity.noContent().build();
        }
        PurgeResult result = purgeUserData(userId);
        log.info("账号已删除并清理数据 | userId={} | 文件 {} 个 | 空目录 {} 个 | 失败 {} 项",
                userId, result.files(), result.dirs(), result.failures().size());
        // P2-审查1（2026-09-17 deep 审 + B2 批修复）：失败不再被吞——`purged` 如实反映「是否真清干净」，
        // 失败清单原样回给调用方（admin 据此提示人工处理，而不是看到 purged:true 以为已经好了）
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "purged", result.failures().isEmpty(),
                "purgedFiles", result.files(),
                "purgedDirs", result.dirs(),
                "failures", result.failures()));
    }

    /**
     * 递归清理 {@code data/{userId}/} 下的文件与随之变空的目录（task-log #149 的破坏性那一半，
     * 只在显式 purge 时走）。
     * <p>
     * 逐个删文件而不是删目录：{@code FileStorage} 只承诺「文件」这一层的删除语义，不猜底层实现
     * （本机是 LocalFileStorage、将来可能是别的）。**删不掉的如实进 failures**，不假装清干净
     * ——2026-09-17 deep 审（P2-审查1）：原实现只 `log.warn` 却**无条件**回 `purged:true`，
     * 且只删文件不删目录（整棵空目录树残留）。
     */
    private PurgeResult purgeUserData(String userId) {
        int deleted = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (String path : fileStorage.listFiles(userId, "")) {
                try {
                    fileStorage.delete(userId, path);
                    deleted++;
                } catch (Exception e) {
                    failures.add(path + "（" + message(e) + "）");
                    log.warn("账号数据清理：单个文件删除失败 | userId={} | path={} | {}", userId, path, e.getMessage());
                }
            }
        } catch (Exception e) {
            failures.add("目录列举失败（" + message(e) + "）");
            log.warn("账号数据清理：目录列举失败 | userId={} | {}", userId, e.getMessage());
        }
        // 文件删完再收空目录（端口默认实现返回 0 = 该实现不支持；异常计入 failures，不静默）
        int dirs = 0;
        try {
            dirs = fileStorage.deleteEmptyDirectories(userId);
        } catch (Exception e) {
            failures.add("空目录清理失败（" + message(e) + "）");
            log.warn("账号数据清理：空目录清理失败 | userId={} | {}", userId, e.getMessage());
        }
        return new PurgeResult(deleted, dirs, failures);
    }

    /** 异常可能没有 message（NPE 等）——兜底成类名，避免失败清单里出现 "null"。 */
    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** purge 结果：文件数 / 空目录数 / 失败清单（failures 非空即「没清干净」，见 P2-审查1）。 */
    private record PurgeResult(int files, int dirs, List<String> failures) {}

    private boolean isValidRole(String role) {
        return Account.ROLE_ADMIN.equals(role) || Account.ROLE_USER.equals(role);
    }

    private boolean isValidPlugins(List<String> plugins) {
        // P3（2026-08-17）：查重——["trading","trading"] 此前合法落盘（消费端 Set 去重故行为正确，但数据脏）
        if (plugins.stream().distinct().count() != plugins.size()) return false;
        return plugins.stream().allMatch(pluginRegistry::isValid);
    }

    private boolean isSeedAdmin(String userId) {
        return Account.SEED_ADMIN_ID.equals(userId);
    }

    // ── Request DTOs ──

    public record CreateAccountRequest(@NotBlank String userId, String role, List<String> plugins,
                                       String password) {}

    public record UpdateAccountRequest(Boolean enabled, String role, List<String> plugins,
                                       String password) {}

    public record MergePluginsRequest(List<String> add, List<String> remove) {}
}
