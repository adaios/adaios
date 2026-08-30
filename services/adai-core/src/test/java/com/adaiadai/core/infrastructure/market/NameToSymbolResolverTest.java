package com.adaiadai.core.infrastructure.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NameToSymbolResolver — 股票名称 → 6 位代码（2026-08-27，截图归集补代码）。
 * 覆盖响应解析：精确匹配优先 / 首 6 位兜底 / 空数据 / 非 JSON / 空名称。
 */
class NameToSymbolResolverTest {

    private final NameToSymbolResolver resolver = new NameToSymbolResolver("/tmp/nonexistent-names.json");

    @Test
    void parseResponse_exactNameMatch_returnsCode() {
        String body = "{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"600206\",\"Name\":\"有研新材\"},"
                + "{\"Code\":\"002428\",\"Name\":\"云南锗业\"}]}}";
        assertEquals("600206", resolver.parseResponse(body, "有研新材"));
    }

    @Test
    void parseResponse_noExactMatch_firstSixDigitCode() {
        String body = "{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"600206\",\"Name\":\"有研新材\"},"
                + "{\"Code\":\"US.AAPL\",\"Name\":\"苹果\"}]}}";
        assertEquals("600206", resolver.parseResponse(body, "苹果"), "无精确匹配取首个 6 位代码");
    }

    @Test
    void parseResponse_emptyData_returnsNull() {
        assertEquals(null, resolver.parseResponse("{\"QuotationCodeTable\":{\"Data\":[]}}", "有研新材"));
    }

    @Test
    void parseResponse_garbage_returnsNull() {
        assertNull(resolver.parseResponse("not json at all", "有研新材"));
        assertNull(resolver.parseResponse(null, "有研新材"));
    }

    @Test
    void resolve_blankName_returnsNull() {
        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve("  "));
    }

    @Test
    void parseCandidates_returnsAListWithAStockOnly() {
        String body = "{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"000831\",\"Name\":\"中国稀土\"},"
                + "{\"Code\":\"600831\",\"Name\":\"广电网络\"},"
                + "{\"Code\":\"US.AAPL\",\"Name\":\"苹果\"}]}}";
        var list = resolver.parseCandidates(body);
        assertEquals(2, list.size(), "非 A 股（US.AAPL）应被过滤");
        assertEquals("000831", list.get(0).code());
        assertEquals("中国稀土", list.get(0).name());
    }

    @Test
    void parseCandidates_garbage_returnsEmpty() {
        assertEquals(0, resolver.parseCandidates(null).size());
        assertEquals(0, resolver.parseCandidates("not json").size());
    }

    @Test
    void resolveExact_fromLocalNameTable() throws Exception {
        // 构造临时名称表 → 精确匹配（suggest 对部分名称空的兜底）
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("names", ".json");
        java.nio.file.Files.writeString(tmp,
                "[{\"symbol\":\"301080\",\"name\":\"百普赛斯\"},{\"symbol\":\"600519\",\"name\":\"贵州茅台\"}]");
        NameToSymbolResolver r = new NameToSymbolResolver(tmp.toString());
        assertEquals("301080", r.resolveExact("百普赛斯"));
        assertEquals("600519", r.resolveExact("贵州茅台"));
        assertEquals(null, r.resolveExact("不存在股票"));
        java.nio.file.Files.deleteIfExists(tmp);
    }
}
