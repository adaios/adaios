package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AccountController 单元测试。
 * 验证账号 CRUD + 内置管理员 adai 保护。
 */
class AccountControllerTest {

    private MockMvc mvcWith(AccountRepository repo) {
        return MockMvcBuilders.standaloneSetup(new AccountController(repo, new PluginRegistry(),
                mock(PluginService.class), mock(com.adaiadai.core.application.AuthService.class))).build();
    }

    private Account seedAdmin() {
        return new Account(Account.SEED_ADMIN_ID, Account.ROLE_ADMIN, true, LocalDate.of(2026, 8, 2));
    }

    @Test
    void listAccounts_returnsList() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findAll()).thenReturn(List.of(seedAdmin()));

        mvcWith(repo).perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("adai"))
                .andExpect(jsonPath("$[0].role").value("admin"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void createAccount_valid() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"alice\",\"role\":\"user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void createAccount_defaultRoleIsUser() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("charlie")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"charlie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(Account.ROLE_USER));
    }

    @Test
    void createAccount_duplicate_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("adai")).thenReturn(Optional.of(seedAdmin()));

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"adai\",\"role\":\"user\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAccount_invalidUserId_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("a/b")).thenReturn(Optional.empty());

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"a/b\",\"role\":\"user\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAccount_invalidRole_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("x")).thenReturn(Optional.empty());

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"x\",\"role\":\"superuser\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchAccount_disableSeedAdmin_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("adai")).thenReturn(Optional.of(seedAdmin()));

        mvcWith(repo).perform(patch("/api/v1/accounts/adai")
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchAccount_demoteSeedAdmin_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("adai")).thenReturn(Optional.of(seedAdmin()));

        mvcWith(repo).perform(patch("/api/v1/accounts/adai")
                        .contentType("application/json")
                        .content("{\"role\":\"user\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchAccount_enableToggle() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("bob")).thenReturn(Optional.of(
                new Account("bob", Account.ROLE_USER, false, LocalDate.of(2026, 8, 2))));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvcWith(repo).perform(patch("/api/v1/accounts/bob")
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void patchAccount_notFound_404() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("ghost")).thenReturn(Optional.empty());

        mvcWith(repo).perform(patch("/api/v1/accounts/ghost")
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteAccount_seedAdmin_400() throws Exception {
        var repo = mock(AccountRepository.class);

        mvcWith(repo).perform(delete("/api/v1/accounts/adai"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteAccount_other_204() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.delete("bob")).thenReturn(true);

        mvcWith(repo).perform(delete("/api/v1/accounts/bob"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAccount_missing_404() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.delete("ghost")).thenReturn(false);

        mvcWith(repo).perform(delete("/api/v1/accounts/ghost"))
                .andExpect(status().isNotFound());
    }

    // ── 插件（RFC 20260814：Account.plugins 载体 + PATCH/CREATE 控制）──

    @Test
    void createAccount_defaultPluginsEmpty() throws Exception {
        // 新用户默认空 = 只有基础服务（无交易/项目插件）
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"alice\",\"role\":\"user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins.length()").value(0));
    }

    @Test
    void createAccount_withPlugins() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"alice\",\"plugins\":[\"trading\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[0]").value("trading"));
    }

    @Test
    void createAccount_invalidPlugin_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.empty());

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"alice\",\"plugins\":[\"hacking\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchAccount_plugins() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.of(
                new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2))));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvcWith(repo).perform(patch("/api/v1/accounts/alice")
                        .contentType("application/json")
                        .content("{\"plugins\":[\"trading\",\"project\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[0]").value("trading"))
                .andExpect(jsonPath("$.plugins[1]").value("project"));
    }

    @Test
    void patchAccount_pluginsUnchanged_whenNotProvided() throws Exception {
        // PATCH 只改 enabled 时 plugins 保留原值（不清空）
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.of(
                new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2),
                        List.of("trading"))));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvcWith(repo).perform(patch("/api/v1/accounts/alice")
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.plugins[0]").value("trading"));
    }

    @Test
    void patchAccount_invalidPlugin_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.of(
                new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2))));

        mvcWith(repo).perform(patch("/api/v1/accounts/alice")
                        .contentType("application/json")
                        .content("{\"plugins\":[\"nope\"]}"))
                .andExpect(status().isBadRequest());
    }

    // ── 可用账号列表（产品端选号，仅 enabled，最小集只返回 userId）──

    @Test
    void availableAccounts_returnsOnlyEnabled_Ids() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                seedAdmin(), // enabled admin
                new Account("bob", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2)),
                new Account("carol", Account.ROLE_USER, false, LocalDate.of(2026, 8, 2)) // disabled，应被过滤
        ));

        mvcWith(repo).perform(get("/api/v1/accounts/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("adai"))
                .andExpect(jsonPath("$[1]").value("bob"));
    }

    @Test
    void availableAccounts_minimalFields_noRoleExposure() throws Exception {
        // #215：无鉴权端点只返回 userId 字符串，不泄露 role/enabled/createdAt
        var repo = mock(AccountRepository.class);
        when(repo.findAll()).thenReturn(List.of(seedAdmin()));

        mvcWith(repo).perform(get("/api/v1/accounts/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("adai"))
                .andExpect(jsonPath("$[0].role").doesNotExist())
                .andExpect(jsonPath("$[0].enabled").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").doesNotExist());
    }

    @Test
    void availableAccounts_empty() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findAll()).thenReturn(List.of());

        mvcWith(repo).perform(get("/api/v1/accounts/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void mergePlugins_addAndRemove_returnsMerged() throws Exception {
        // REVIEW S-R2：服务端合并语义（快速连点两个开关不再互覆）
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.of(
                new Account("alice", "user", true, LocalDate.of(2026, 8, 2), List.of("trading"))));
        when(repo.mergePlugins(eq("alice"), eq(List.of("project")), eq(List.of("trading"))))
                .thenReturn(new Account("alice", "user", true, LocalDate.of(2026, 8, 2), List.of("project")));

        mvcWith(repo).perform(patch("/api/v1/accounts/alice/plugins")
                        .contentType("application/json")
                        .content("{\"add\":[\"project\"],\"remove\":[\"trading\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[0]").value("project"));
    }

    @Test
    void mergePlugins_invalidPlugin_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.of(
                new Account("alice", "user", true, LocalDate.of(2026, 8, 2))));

        mvcWith(repo).perform(patch("/api/v1/accounts/alice/plugins")
                        .contentType("application/json")
                        .content("{\"add\":[\"hack\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mergePlugins_seedAdmin_400() throws Exception {
        var repo = mock(AccountRepository.class);

        mvcWith(repo).perform(patch("/api/v1/accounts/adai/plugins")
                        .contentType("application/json")
                        .content("{\"add\":[\"trading\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mergePlugins_missingAccount_404() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("ghost")).thenReturn(Optional.empty());

        mvcWith(repo).perform(patch("/api/v1/accounts/ghost/plugins")
                        .contentType("application/json")
                        .content("{\"add\":[\"trading\"]}"))
                .andExpect(status().isNotFound());
    }

    // ── #178：passwordHash 不外泄 + PATCH 保留/重置密码 ──

    private MockMvc mvcWith(AccountRepository repo, AuthService auth) {
        return MockMvcBuilders.standaloneSetup(new AccountController(repo, new PluginRegistry(),
                mock(PluginService.class), auth)).build();
    }

    @Test
    void listAccounts_passwordHashNotExposed() throws Exception {
        // bcrypt 哈希绝不落 API 响应（#178）——账号文件里有哈希，列表响应必须无该字段
        var repo = mock(AccountRepository.class);
        when(repo.findAll()).thenReturn(List.of(new Account(
                "adai", Account.ROLE_ADMIN, true, LocalDate.of(2026, 8, 2),
                List.of("trading"), "$2a$10$abc")));

        mvcWith(repo).perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("adai"))
                .andExpect(jsonPath("$[0].role").value("admin"))
                .andExpect(jsonPath("$[0].plugins[0]").value("trading"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void createAccount_withInitialPassword_encodedAndNotExposed() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var auth = mock(AuthService.class);
        when(auth.encodePassword("secret123")).thenReturn("$2a$10$encoded");

        mvcWith(repo, auth).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"alice\",\"role\":\"user\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(repo).save(captor.capture());
        assertEquals("$2a$10$encoded", captor.getValue().passwordHash());
    }

    @Test
    void createAccount_shortPassword_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("alice")).thenReturn(Optional.empty());

        mvcWith(repo).perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"userId\":\"alice\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchAccount_preservesPasswordHash() throws Exception {
        // #178 bug 修复：PATCH（即使只改 enabled）不得清空既有密码哈希
        var repo = mock(AccountRepository.class);
        when(repo.findById("bob")).thenReturn(Optional.of(new Account(
                "bob", Account.ROLE_USER, false, LocalDate.of(2026, 8, 2),
                List.of(), "$2a$10$oldhash")));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var auth = mock(AuthService.class);

        mvcWith(repo, auth).perform(patch("/api/v1/accounts/bob")
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(repo).save(captor.capture());
        assertEquals("$2a$10$oldhash", captor.getValue().passwordHash(),
                "未带 password 的 PATCH 必须保留原哈希（防清密码 bug）");
        verify(auth, never()).kickSessions(anyString());
    }

    @Test
    void patchAccount_resetPassword_encodesAndKicksSessions() throws Exception {
        // admin 重置他人密码：编码落盘 + 踢除该账号全部会话（被重置者需重新登录）
        var repo = mock(AccountRepository.class);
        when(repo.findById("bob")).thenReturn(Optional.of(new Account(
                "bob", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2),
                List.of(), "$2a$10$oldhash")));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var auth = mock(AuthService.class);
        when(auth.encodePassword("newpass123")).thenReturn("$2a$10$newhash");

        mvcWith(repo, auth).perform(patch("/api/v1/accounts/bob")
                        .contentType("application/json")
                        .content("{\"password\":\"newpass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(repo).save(captor.capture());
        assertEquals("$2a$10$newhash", captor.getValue().passwordHash());
        verify(auth).kickSessions("bob");
    }

    @Test
    void patchAccount_resetShortPassword_400() throws Exception {
        var repo = mock(AccountRepository.class);
        when(repo.findById("bob")).thenReturn(Optional.of(new Account(
                "bob", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2))));

        mvcWith(repo).perform(patch("/api/v1/accounts/bob")
                        .contentType("application/json")
                        .content("{\"password\":\"12345\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchAccount_seedAdmin_passwordResetAllowed() throws Exception {
        // 内置管理员保护只限 禁用/降级/删/插件；重置密码允许（admin 可重置自己/他人）
        var repo = mock(AccountRepository.class);
        when(repo.findById("adai")).thenReturn(Optional.of(new Account(
                "adai", Account.ROLE_ADMIN, true, LocalDate.of(2026, 8, 2),
                List.of(), "$2a$10$old")));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var auth = mock(AuthService.class);
        when(auth.encodePassword("newpass123")).thenReturn("$2a$10$new");

        mvcWith(repo, auth).perform(patch("/api/v1/accounts/adai")
                        .contentType("application/json")
                        .content("{\"password\":\"newpass123\"}"))
                .andExpect(status().isOk());
    }
}
