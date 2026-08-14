package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MeController — GET /api/v1/me/plugins（前端插件门控数据源，RFC 20260814 T2.5）。
 */
class MeControllerTest {

    private MockMvc mvcFor(String userId, List<String> plugins) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(userId)).thenReturn(Optional.of(
                new Account(userId, Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), plugins)));
        PluginService pluginService = new PluginService(accounts, new PluginRegistry());
        return MockMvcBuilders.standaloneSetup(new MeController(pluginService)).build();
    }

    @Test
    void plugins_ownerHasTradingAndProject() throws Exception {
        mvcFor("adai", List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_PROJECT))
                .perform(get("/api/v1/me/plugins").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("project"))
                .andExpect(jsonPath("$[1]").value("trading"));
    }

    @Test
    void plugins_newUser_empty() throws Exception {
        mvcFor("alice", List.of())
                .perform(get("/api/v1/me/plugins").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void plugins_unknownUser_empty() throws Exception {
        mvcFor("adai", List.of())
                .perform(get("/api/v1/me/plugins").header("X-User-Id", "ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
