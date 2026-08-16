package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.storage.CardMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CardController — 卡片迁移接口测试（清理维护端点已迁至 AdminController，REVIEW P-be-01）。
 */
class CardControllerTest {

    private MockMvc buildMvc(CardMigrationService migrationService) {
        return MockMvcBuilders.standaloneSetup(new CardController(migrationService)).build();
    }

    @Test
    void migrateCards_returnsCounts() throws Exception {
        CardMigrationService migration = mock(CardMigrationService.class);
        when(migration.migrate(any())).thenReturn(new CardMigrationService.MigrationResult(
                10, 7, 3, List.of("card_1", "card_2"), List.of("card_bad")));
        MockMvc mvc = buildMvc(migration);

        mvc.perform(post("/api/v1/cards/migrate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalScanned").value(10))
                .andExpect(jsonPath("$.migrated").value(7))
                .andExpect(jsonPath("$.failed").value(3))
                .andExpect(jsonPath("$.migratedFiles[0]").value("card_1"));
    }

    @Test
    void migrateCards_forwardsUserId() throws Exception {
        CardMigrationService migration = mock(CardMigrationService.class);
        when(migration.migrate(any())).thenReturn(new CardMigrationService.MigrationResult(
                0, 0, 0, List.of(), List.of()));
        MockMvc mvc = buildMvc(migration);

        mvc.perform(post("/api/v1/cards/migrate").header("X-User-Id", "alice"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(migration).migrate("alice");
    }
}
