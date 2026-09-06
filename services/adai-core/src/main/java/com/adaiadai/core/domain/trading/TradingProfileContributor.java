package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.TradingProfileService;
import com.adaiadai.core.kernel.context.engine.ContextContributor;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TradingProfileContributor — 个人交易画像上下文贡献者（RFC 20260905 A 层）。
 * <p>
 * 在 trading / decision 场景注入「你的画像」（客观统计 + 主观签名），让阿呆的复盘/建议
 * 用你的历史说话——「你上次在 X 就是这样追高」而非只背规则书。
 * <p>
 * 与 {@link TradingContextContributor}（当前持仓/状态）、{@link MarketContextContributor}（行情）
 * 互补：本类注入「你是谁」，它们注入「现在怎样」。
 * <p>
 * 红线①③（RFC 20260902）：全部数字由 {@link TradingProfileService} 系统推导（不靠 LLM 编造），
 * 输出主语是「你」（合规——自我认知工具，非荐股）。
 */
@Component
public class TradingProfileContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(TradingProfileContributor.class);

    private final TradingProfileService profileService;

    public TradingProfileContributor(TradingProfileService profileService) {
        this.profileService = profileService;
    }

    @Override
    public boolean supports(String scene) {
        return "trading".equals(scene) || "decision".equals(scene);
    }

    @Override
    public String enrich(String userId, String identityRef, ContentRecord record) {
        return profileService.profileText(userId);
    }

    /** 全局摘要：非交易场景只给一句话（让 AI 知道存在画像，不占体积）。
     *  💥4（2026-09-05 对抗审）：只有真实存在画像数据时才声称「了解你」——
     *  原实现无画像也对所有对话声称「了解你的交易史」，但数据只在 trading/decision 场景注入，
     *  非交易对话被引导去编「你上次在 X 怎样」（幻觉入口）。 */
    @Override
    public String globalContext(String userId) {
        try {
            if (profileService.profileText(userId).isBlank()) {
                return ""; // 无画像数据 → 不声称了解（宁缺毋滥，防 AI 编造用户历史）
            }
        } catch (Exception e) {
            return "";
        }
        return "> 阿呆了解这个用户的交易风格与历史（个人画像）。仅在涉及交易话题且用户主动问起时可参考其历史对照，不要主动断言「你上次…」。";
    }
}
