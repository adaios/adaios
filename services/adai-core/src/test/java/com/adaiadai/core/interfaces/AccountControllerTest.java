package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AccountController 单元测试。
 * 验证账号 CRUD + 内置管理员 adai 保护。
 */
class AccountControllerTest {

    private MockMvc mvcWith(AccountRepository repo) {
        return MockMvcBuilders.standaloneSetup(new AccountController(repo)).build();
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
}
