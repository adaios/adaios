package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.storage.CardMigrationService;
import com.adaiadai.core.infrastructure.storage.CardMigrationService.MigrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CardController — 卡片管理 API（清理维护操作已迁至 AdminController，REVIEW P-be-01）。
 */
@RestController
@RequestMapping("/api/v1/cards")
public class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    private final CardMigrationService migrationService;

    public CardController(CardMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /**
     * 迁移历史卡片文件（纯数字 ID）到 records/cards/ 子目录。
     * <p>
     * POST /api/v1/cards/migrate
     */
    @PostMapping("/migrate")
    public ResponseEntity<Map<String, Object>> migrateCards(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        log.info("卡片迁移开始...");
        MigrationResult result = migrationService.migrate(userId);

        log.info("卡片迁移完成 | 扫描={} | 迁移={} | 失败={}",
                result.totalScanned(), result.migrated(), result.failed());

        return ResponseEntity.ok(Map.of(
                "totalScanned", result.totalScanned(),
                "migrated", result.migrated(),
                "failed", result.failed(),
                "migratedFiles", result.migratedFiles(),
                "failedFiles", result.failedFiles()
        ));
    }
}
