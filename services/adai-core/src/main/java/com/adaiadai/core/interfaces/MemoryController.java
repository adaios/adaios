package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.RecordFlowAppService;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MemoryController — 记忆查询 + 重建 API。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;
    private final RecordRepository recordRepository;
    private final RecordFlowAppService recordFlowAppService;

    public MemoryController(MemoryService memoryService,
                            RecordRepository recordRepository,
                            RecordFlowAppService recordFlowAppService) {
        this.memoryService = memoryService;
        this.recordRepository = recordRepository;
        this.recordFlowAppService = recordFlowAppService;
    }

    /**
     * 按日期查询记忆。
     */
    @GetMapping
    public ResponseEntity<List<Memory>> getMemories(
            @RequestParam(required = false) String date) {
        LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return ResponseEntity.ok(memoryService.findByDate(queryDate));
    }

    /**
     * 根据记录 ID 查询 AI 理解。
     */
    @GetMapping("/record/{recordId}")
    public ResponseEntity<Memory> getByRecordId(@PathVariable String recordId) {
        return memoryService.findByRecordId(recordId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 重建记忆：遍历没有记忆的历史记录，逐个生成 AI 摘要+标签并沉淀为记忆。
     * <p>
     * POST /api/v1/memory/rebuild?date=2026-07-21
     * POST /api/v1/memory/rebuild（重建所有）
     */
    @PostMapping("/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildMemory(
            @RequestParam(required = false) String date) {
        List<ContentRecord> allRecords = recordRepository.findAll();

        // 过滤日期
        LocalDate filterDate = date != null ? LocalDate.parse(date) : null;
        List<ContentRecord> targetRecords = allRecords.stream()
                .filter(r -> filterDate == null || r.createdAt().toLocalDate().equals(filterDate))
                .filter(r -> r.intent() == null || "log".equals(r.intent()))
                .toList();

        log.info("记忆重建开始 | 目标日期={} | 待处理记录={}条", date != null ? date : "全部", targetRecords.size());

        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (ContentRecord record : targetRecords) {
            try {
                recordFlowAppService.process(record);
                success++;
                log.info("记忆重建成功 | recordId={} | ({}/{})", record.id(), success + failed, targetRecords.size());
            } catch (Exception e) {
                failed++;
                errors.add(record.id() + ": " + e.getMessage());
                log.warn("记忆重建失败 | recordId={} | {}", record.id(), e.getMessage());
            }
        }

        log.info("记忆重建完成 | 成功={} | 失败={}", success, failed);

        return ResponseEntity.ok(Map.of(
                "success", success,
                "failed", failed,
                "total", targetRecords.size(),
                "errors", errors
        ));
    }
}
