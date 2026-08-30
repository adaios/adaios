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
        // 2026-08-30 建议 #4：标注响应带共识偏离度校验（防脏案例进库提示，不阻止）
        TradingCaseAppService.AnnotateResult result = caseAppService.annotateWithCheck(
                userId, body.symbol(), parseDate(body.buyDate()),
                body.buyType(), body.description(), body.labels(), body.name());
        // Map.of 不接受 null value（consensusCheck 可为 null）——用 HashMap
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("case", result.record());
        if (result.consensusCheck() != null) resp.put("consensusCheck", result.consensusCheck());
        return ResponseEntity.ok(resp);
    }

    /** 案例列表（buyDate 倒序摘要）。 */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(caseAppService.list(userId));
    }

    /** 案例详情；?kline=true 附 90 根窗口日 K（前端画图重放）；
     * ?indicators=true 附指标全序列（2026-08-30：前端图不重算指标，hover 值 = 特征同源）。 */
    @GetMapping("/{caseId}")
    public ResponseEntity<?> detail(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String caseId,
            @RequestParam(defaultValue = "false") boolean kline,
            @RequestParam(defaultValue = "false") boolean indicators) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(caseAppService.detail(userId, caseId, kline, indicators));
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

    /** 环 3：生成案例 AI 理解（LLM 读特征+K 线 → aiInsight 落盘）。 */
    @PostMapping("/{caseId}/insight")
    public ResponseEntity<?> insight(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String caseId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(caseAppService.generateInsight(userId, caseId));
    }

    /** 环 4：判定当下——当前标的形态 vs 案例库归一化相似度 Top N（核心价值）。 */
    @PostMapping("/match")
    public ResponseEntity<?> match(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody CaseMatchRequest body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(caseAppService.match(userId, body.symbol(),
                body.date() == null || body.date().isBlank() ? null : parseDate(body.date())));
    }

    /**
     * 日期宽容解析（2026-08-30 用户反馈 400）：接受 ISO（yyyy-MM-dd）与 BASIC（yyyyMMdd），
     * 都失败 → 业务异常（400 + 人话「日期格式不正确，请用 yyyy-MM-dd」）。
     */
    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new com.adaiadai.core.domain.trading.TradingException("日期不能为空");
        }
        String s = raw.strip();
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            // 用户习惯性输入 20260826（通达信/日期选择器常见格式）
            try {
                return LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            } catch (Exception e2) {
                throw new com.adaiadai.core.domain.trading.TradingException(
                        "日期格式不正确：" + raw + "，请用 yyyy-MM-dd（如 2026-08-26）");
            }
        }
    }

    private ResponseEntity<?> requireTradingPlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        return null;
    }

    /** 标注请求体（buyDate 字符串宽松格式：yyyy-MM-dd 或 yyyyMMdd，Controller 解析）。 */
    public record CaseAnnotateRequest(
            @NotBlank(message = "标的代码不能为空")
            @Pattern(regexp = "\\d{6}", message = "标的代码需为 6 位数字")
            String symbol,
            @NotBlank(message = "买点日期不能为空")
            String buyDate,
            @Size(max = 20, message = "买点类型过长")
            String buyType,
            @Size(max = 200, message = "描述过长")
            String description,
            @Size(max = 50, message = "名称过长")
            String name,
            List<String> labels) {}

    /** 匹配请求体（date 可空 = 最近交易日；字符串宽松格式同标注）。 */
    public record CaseMatchRequest(
            @NotBlank(message = "标的代码不能为空")
            @Pattern(regexp = "\\d{6}", message = "标的代码需为 6 位数字")
            String symbol,
            String date) {}
}
