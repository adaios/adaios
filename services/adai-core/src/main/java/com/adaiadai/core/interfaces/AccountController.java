package com.adaiadai.core.interfaces;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Optional;

/**
 * AccountController — 账号管理端点（v1.0.0 多账号功能层）。
 * <p>
 * GET    /api/v1/accounts              → 账号列表（adai-admin 管理，需 X-Admin-Token）
 * GET    /api/v1/accounts/available    → 可用账号列表（仅 enabled，**无鉴权**，产品端选号）
 * POST   /api/v1/accounts              → 建号（无注册，adai-admin 后台建）
 * PATCH  /api/v1/accounts/{userId}     → 更新（启用/禁用、角色）
 * DELETE /api/v1/accounts/{userId}     → 删除
 * <p>
 * 内置管理员 {@code adai} 不可删除 / 不可禁用 / 不可降级（防锁死系统）。
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private static final String USER_ID_PATTERN = "[a-zA-Z0-9_-]+";

    private final AccountRepository accountRepository;
    private final PluginRegistry pluginRegistry;
    private final PluginService pluginService;

    public AccountController(AccountRepository accountRepository, PluginRegistry pluginRegistry,
                             PluginService pluginService) {
        this.accountRepository = accountRepository;
        this.pluginRegistry = pluginRegistry;
        this.pluginService = pluginService;
    }

    /** 账号列表（返回全部，前端按 enabled 过滤选号）。 */
    @GetMapping
    public List<Account> listAccounts() {
        return accountRepository.findAll();
    }

    /**
     * 可用账号列表（产品端选号，**无鉴权**——v1.0.0 多账号前端选号提前）。
     * <p>仅返回 {@code enabled=true} 账号的 **userId 最小集**（#215：无鉴权端点不暴露
     * role/enabled/createdAt，避免 admin 标记等枚举面）；账号由 adai-admin 后台创建，
     * 产品端不做注册。由 WebConfig 将该路径从 AdminAuthInterceptor 拦截范围 exclude。
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
        Account account = accountRepository.save(new Account(userId, role, true, LocalDate.now(), plugins));
        pluginService.invalidate(userId);
        log.info("创建账号: {} role={} plugins={}", userId, role, plugins);
        return ResponseEntity.ok(account);
    }

    /** 更新账号（启用/禁用、角色）。内置管理员保护。 */
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
        Account updated = accountRepository.save(
                new Account(userId, role, enabled, existing.get().createdAt(), plugins));
        pluginService.invalidate(userId);
        return ResponseEntity.ok(updated);
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
        return ResponseEntity.ok(updated);
    }

    /** 删除账号。内置管理员不可删。 */
    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deleteAccount(@PathVariable String userId) {
        if (isSeedAdmin(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "内置管理员 " + Account.SEED_ADMIN_ID + " 不可删除"));
        }
        boolean removed = accountRepository.delete(userId);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

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

    public record CreateAccountRequest(@NotBlank String userId, String role, List<String> plugins) {}

    public record UpdateAccountRequest(Boolean enabled, String role, List<String> plugins) {}

    public record MergePluginsRequest(List<String> add, List<String> remove) {}
}
