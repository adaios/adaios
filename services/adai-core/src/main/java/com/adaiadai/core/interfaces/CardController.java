package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.storage.CardMigrationService;
import com.adaiadai.core.infrastructure.storage.CardMigrationService.CleanupResult;
import com.adaiadai.core.infrastructure.storage.CardMigrationService.MigrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CardController — 卡片管理 API。
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
    public ResponseEntity<Map<String, Object>> migrateCards() {
        log.info("卡片迁移开始...");
        MigrationResult result = migrationService.migrate();

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

    /**
     * 清理重复记录：删除卡片对话对应的冗余 ContentRecord。
     * <p>
     * POST /api/v1/cards/cleanup
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupRecords() {
        log.info("清理重复记录开始...");
        CleanupResult result = migrationService.cleanupDuplicateRecords();

        log.info("清理完成 | 删除={}条", result.deleted());

        return ResponseEntity.ok(Map.of(
                "deleted", result.deleted(),
                "deletedFiles", result.deletedFiles(),
                "skippedFiles", result.skippedFiles()
        ));
    }
}
