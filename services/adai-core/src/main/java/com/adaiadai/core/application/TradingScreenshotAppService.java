package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.infrastructure.ai.vision.ImageRequest;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * TradingScreenshotAppService — 截图入账用例编排（2026-08-26，交易闭环第一环）。
 * <p>
 * 券商「当日委托/历史成交」截图 → VLM 识别为表格文字 → 交易日志归集器逐笔提取为当日候选。
 * 与 {@link MediaRecordAppService#recordImage} 的关键差异：**不落原图、不建记录、不沉淀记忆**
 * ——截图入账是交易动作不是记录动作，候选确认落库后即权威数据，不污染 Feed/时间线/记忆。
 * <p>
 * 插件门禁由 TradingController 的 {@code requireTradingPlugin} 拦截，本服务不重复判定。
 */
@Service
public class TradingScreenshotAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingScreenshotAppService.class);
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    /** 一次截图入账上限（2026-08-26 用户拍板：与 Phase 1 带图 3 张对齐）。 */
    private static final int MAX_BATCH_IMAGES = 3;

    private final VisualAiClient visualAiClient;
    private final TradeLogCollectService tradeLogCollectService;

    public TradingScreenshotAppService(VisualAiClient visualAiClient,
                                       TradeLogCollectService tradeLogCollectService) {
        this.visualAiClient = visualAiClient;
        this.tradeLogCollectService = tradeLogCollectService;
    }

    /**
     * 截图批量归集为当日候选：逐张 VLM 识别 → 归集器提取（跨图 sameTrade 自动去重）。
     *
     * @param userId       用户
     * @param images       图片字节列表（1-3 张，每张 ≤ 5MB）
     * @param contentTypes 对应 MIME 类型（缺失按 image/png 兜底）
     * @return 处理统计 + 当日全部候选（含本次新增，去重后）
     * @throws IllegalArgumentException 张数越界/空列表（前端人话提示，controller 400）
     */
    public ScreenshotCollectResult collect(String userId, List<byte[]> images, List<String> contentTypes) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("请选择截图");
        }
        if (images.size() > MAX_BATCH_IMAGES) {
            throw new IllegalArgumentException("一次最多 " + MAX_BATCH_IMAGES + " 张截图");
        }
        List<String> errors = new ArrayList<>();
        int processed = 0;
        for (int i = 0; i < images.size(); i++) {
            byte[] bytes = images.get(i);
            String contentType = (contentTypes != null && i < contentTypes.size())
                    ? contentTypes.get(i) : "image/png";
            if (contentType == null || !contentType.startsWith("image/")) {
                errors.add("第 " + (i + 1) + " 张不是图片");
                continue;
            }
            if (bytes == null || bytes.length == 0) {
                errors.add("第 " + (i + 1) + " 张图片为空");
                continue;
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                errors.add("第 " + (i + 1) + " 张超过 5MB");
                continue;
            }
            try {
                String base64 = Base64.getEncoder().encodeToString(bytes);
                // R1 AI 交互日志：截图入账锚点（VLM 识别可溯源；source 不影响 DeepSeek 模型路由——
                // 识别走 GLM-VLM，非 DeepSeek 文本模型）
                AiTraceContext.set(userId, null, null, "trading_screenshot");
                ImageUnderstanding u = visualAiClient.understand(new ImageRequest(base64, contentType, ""));
                // 2026-08-27 归集原料修正：表格 OCR 优先用 extractedText（VLM 输出 JSON 的 OCR 全文），
                // summary 只是「一句话概括」（flash 模型甚至只给 6 字概括、extractedText 空 → 归集 0 条）。
                // 两个模型都受益：thinking 的 extractedText 才是完整表格文字，summary 可能截断。
                String ocr = (u.extractedText() != null && !u.extractedText().isBlank())
                        ? u.extractedText() : (u.summary() != null ? u.summary() : "");
                tradeLogCollectService.collect(userId, ocr, "image");
                processed++;
            } catch (Exception e) {
                log.warn("截图识别失败 | 第 {} 张 | userId={} | {}", i + 1, userId, e.getMessage());
                errors.add("第 " + (i + 1) + " 张识别失败");
            }
        }
        List<TradeLogCandidate> candidates = tradeLogCollectService.todayCandidates(userId);
        if (processed > 0) {
            log.info("截图入账完成 | userId={} | 处理 {} 张 | 当日候选 {} 条 | 失败 {} 张",
                    userId, processed, candidates.size(), errors.size());
        }
        return new ScreenshotCollectResult(images.size(), processed, candidates, errors);
    }

    /**
     * 截图入账结果：total 提交张数 / processed 成功识别张数 / candidates 当日候选（去重后）/
     * errors 逐张失败原因（空 = 全部成功）。
     */
    public record ScreenshotCollectResult(int total, int processed,
                                          List<TradeLogCandidate> candidates,
                                          List<String> errors) {
    }
}
