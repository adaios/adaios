package com.adaiadai.core.infrastructure.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * StrictJson — 读路径 JSON 严格性统一入口（P2-工程4，2026-09-14）。
 * <p>
 * <b>问题</b>：Jackson 默认<b>忽略尾部多余内容</b>（{@code FAIL_ON_TRAILING_TOKENS} 关闭）——
 * 一个被截断/写坏的文件（{@code [...]} 后面半行垃圾、写了两遍、进程被杀在写一半）会被
 * <b>解析成功并读出前半段</b>。对「读 → 改 → 写回」型仓储（流水/持仓/账户/自选/令牌…）这是
 * <b>静默丢数据</b>的入口：读到半截 → 上层以为拿到了全量 → 任一写入把半截列表整体回写 → 后半段永久消失。
 * <p>
 * 2026-09-13 在 {@code PushDeviceFileRepository} 首次打开该开关（当时带反例单测），
 * 2026-09-14 在 {@code ApiTokenFileRepository} 复现同类风险（P2-令牌3/T3）。
 * 本类把「到底怎么配」收敛成一处，避免下一个仓储又用默认宽容的 {@code new ObjectMapper()}。
 * <p>
 * <b>用法</b>：{@code private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());}
 * （已带 JavaTimeModule/INDENT 等配置的，包一层即可，链式继续写）。
 * <p>
 * <b>边界（如实说明）</b>：本类只解决「<b>文件被写坏却读成合法</b>」；
 * 「读失败之后上层把它当『文件不存在』继续写回覆盖」是另一半问题（需要各仓储区分
 * 「不存在」与「存在但读不出」，涉及接口契约），已在 REVIEW P2-工程4 登记为后续项，未在本批擅改。
 */
public final class StrictJson {

    private StrictJson() {}

    /** 开启「尾部多余内容即解析失败」——损坏/截断的文件不得被当成合法 JSON 读出前半段。 */
    public static ObjectMapper strict(ObjectMapper mapper) {
        return mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
