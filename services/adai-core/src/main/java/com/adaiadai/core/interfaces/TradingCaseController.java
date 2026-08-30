package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingCaseAppService;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * TradingCaseController — 完美买点案例 REST API（2026-08-30 第四阶段环 1-2）。
 * <p>
 * 端点：POST/GET /trading/cases、GET/DELETE /trading/cases/{caseId}。
 * 全部需 trading 插件（403）；X-User-Id 隔离（data/{userId}/trading/cases/）。
 * 门控与 TradingController 同口径（requireTradingPlugin 复制）。
 */
@RestController
@RequestMapping("/api/v1/trading/cases")
public class TradingCaseController {

    private static final Logger log = LoggerFactory.getLogger(TradingCaseController.class);

    private final TradingCaseAppService caseAppService;
    private final PluginService pluginService;

    public TradingCaseController(TradingCaseAppService caseAppService, PluginService pluginService) {
        this.caseAppService = caseAppService;
        this.pluginService = pluginService;
    }

    /** 标注一个完美买点案例。 */
    @PostMapping
    public ResponseEntity<?> annotate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody CaseAnnotateRequest body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        CaseRecord record = caseAppService.annotate(userId, body.symbol(), body.buyDate(),
                body.buyType(), body.description(), body.labels(), body.name());
        return ResponseEntity.ok(record);
    }

    /** 案例列表（buyDate 倒序摘要）。 */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(caseAppService.list(userId));
    }

    /** 案例详情；?kline=true 附 90 根窗口日 K（前端画图重放）。 */
    @GetMapping("/{caseId}")
    public ResponseEntity<?> detail(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String caseId,
            @RequestParam(defaultValue = "false") boolean kline) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(caseAppService.detail(userId, caseId, kline));
    }

    /** 删除案例。 */
    @DeleteMapping("/{caseId}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String caseId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        caseAppService.delete(userId, caseId);
        return ResponseEntity.ok(Map.of("deleted", true, "caseId", caseId));
    }

    private ResponseEntity<?> requireTradingPlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        return null;
    }

    /** 标注请求体。 */
    public record CaseAnnotateRequest(
            @NotBlank(message = "标的代码不能为空")
            @Pattern(regexp = "\\d{6}", message = "标的代码需为 6 位数字")
            String symbol,
            @NotNull(message = "买点日期不能为空")
            LocalDate buyDate,
            @Size(max = 20, message = "买点类型过长")
            String buyType,
            @Size(max = 200, message = "描述过长")
            String description,
            @Size(max = 50, message = "名称过长")
            String name,
            List<String> labels) {}
}
