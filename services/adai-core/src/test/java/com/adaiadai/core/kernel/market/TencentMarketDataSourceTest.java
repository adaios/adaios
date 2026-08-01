package com.adaiadai.core.kernel.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TencentMarketDataSource 单元测试。
 * 覆盖：toApiCode 前缀映射、parseResponse 解析、缓存键一致性（回归：带前缀/6位混用导致永久 miss）。
 */
class TencentMarketDataSourceTest {

    private TencentMarketDataSource newMockedSource(String body) throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(body);
        HttpClient http = mock(HttpClient.class);
        doReturn(resp).when(http).send(any(), any());
        return new TencentMarketDataSource(http);
    }

    private static final String INDEX_LINE = "v_sh000001=\"1~上证指数~000001~3804.69~3804.39~3810.12~12345~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~-0.62~-23.72~3804.69~0~0~0~0~0~0~0~0~0~3788.21~3821.22\";\n";

    @Test
    void toApiCode_mapsPrefixes() {
        assertEquals("sh600123", TencentMarketDataSource.toApiCode("600123"));
        assertEquals("sz000001", TencentMarketDataSource.toApiCode("000001"));
        assertEquals("sz002415", TencentMarketDataSource.toApiCode("002415"));
        assertEquals("sz300750", TencentMarketDataSource.toApiCode("300750"));
        assertEquals("bj830799", TencentMarketDataSource.toApiCode("830799"));
        assertEquals("sh600123", TencentMarketDataSource.toApiCode("sh600123"), "已带前缀不重复加");
    }

    @Test
    void parseResponse_parsesTencentFormat() {
        TencentMarketDataSource source = new TencentMarketDataSource(mock(HttpClient.class));
        Map<String, MarketData> result = source.parseResponse(INDEX_LINE);

        assertEquals(1, result.size());
        MarketData md = result.get("sh000001");
        assertNotNull(md, "返回键应带交易所前缀（来自行前缀 v_sh000001）");
        assertEquals("sh000001", md.code());
        assertEquals("上证指数", md.name());
        assertEquals(new BigDecimal("3804.69"), md.price());
        assertEquals(new BigDecimal("-0.62"), md.changePercent());
        assertEquals(new BigDecimal("3788.21"), md.low());
        assertEquals(new BigDecimal("3821.22"), md.high());
    }

    @Test
    void quote_cachesByApiCodeKey_secondCallHitsCache() throws Exception {
        String calls = INDEX_LINE;
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(calls);
        doReturn(resp).when(http).send(any(), any());
        TencentMarketDataSource source = new TencentMarketDataSource(http);

        // 第一次：请求带前缀键 sh000001
        Map<String, MarketData> first = source.quote(List.of("sh000001"));
        assertEquals(1, first.size());
        assertNotNull(first.get("sh000001"), "返回键应与请求键一致");
        verify(http, times(1)).send(any(), any());

        // 第二次：缓存应命中（缓存键已规范化为 apiCode），不再发请求
        Map<String, MarketData> second = source.quote(List.of("sh000001"));
        assertEquals(1, second.size());
        assertNotNull(second.get("sh000001"));
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void indices_returnsRequestKeys_consistent() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(INDEX_LINE);
        doReturn(resp).when(http).send(any(), any());
        TencentMarketDataSource source = new TencentMarketDataSource(http);

        Map<String, MarketData> indices = source.indices();
        // 返回键应含请求的带前缀键
        assertTrue(indices.containsKey("sh000001") || indices.containsKey("000001"),
                "indices 返回键应与请求一致: " + indices.keySet());
    }
}
