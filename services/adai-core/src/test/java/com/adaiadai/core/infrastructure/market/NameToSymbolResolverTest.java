package com.adaiadai.core.infrastructure.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NameToSymbolResolver — 股票名称 → 6 位代码（2026-08-27，截图归集补代码）。
 * 覆盖响应解析：精确匹配优先 / 首 6 位兜底 / 空数据 / 非 JSON / 空名称。
 */
class NameToSymbolResolverTest {

    private final NameToSymbolResolver resolver = new NameToSymbolResolver();

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
}
