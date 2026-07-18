package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * MemoryController — 记忆查询 API。
 * <p>
 * App 调用此接口获取 AI 对记录的理解结果（摘要/标签/情感）。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
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
}
