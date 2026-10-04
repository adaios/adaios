package com.adaiadai.core.infrastructure.market;

import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SinaKlineDataSourceTest — 新浪 K 线（RFC 20260923 B 批：第三层兜底）。
 *
 * <p>钉住三件事：① 新浪返回格式能正确解析（旧→新、字段映射）；② **volume 股→手**（与腾讯/东财同口径）；
 * ③ 单行坏数据/非数组不炸、不丢整段（安全约定：异常返回空而不是抛）。
 */
class SinaKlineDataSourceTest {

    private static final String SAMPLE = """
            [{"day":"2026-09-21","open":"1259.000","high":"1259.950","low":"1250.800","close":"1252.570","volume":"2501706"},
             {"day":"2026-09-18","open":"1262.990","high":"1265.880","low":"1256.100","close":"1257.120","volume":"2489087"}]""";

    @Test
    void parse_mapsFieldsAndConvertsVolumeToLots() throws Exception {
        List<Candle> candles = SinaKlineDataSource.parse(SAMPLE);

        assertEquals(2, candles.size());
        // 新浪给的是**旧→新以外**的顺序也可能不同——parse 末尾按日期排序兜底
        assertEquals("2026-09-18", candles.get(0).date().toString(), "必须旧→新（其它源同口径）");
        assertEquals("2026-09-21", candles.get(1).date().toString());
        Candle last = candles.get(1);
        assertEquals(1259.000, last.open(), 1e-6);
        assertEquals(1259.950, last.high(), 1e-6);
        assertEquals(1250.800, last.low(), 1e-6);
        assertEquals(1252.570, last.close(), 1e-6);
        assertEquals(25017.06, last.volume(), 1e-3, "新浪 volume 是「股」，要 /100 成「手」与腾讯/东财一致");
    }

    @Test
    void parse_minuteStyleDayWithTime_isTrimmedToDate() throws Exception {
        List<Candle> candles = SinaKlineDataSource.parse(
                "[{\"day\":\"2026-09-21 15:00:00\",\"open\":\"1\",\"high\":\"2\",\"low\":\"0.5\",\"close\":\"1.5\",\"volume\":\"100\"}]");
        assertEquals(1, candles.size());
        assertEquals("2026-09-21", candles.get(0).date().toString());
    }

    @Test
    void parse_brokenRowSkipped_notWholeSegmentLost() throws Exception {
        List<Candle> candles = SinaKlineDataSource.parse(
                "[{\"day\":\"2026-09-21\",\"open\":\"1\",\"high\":\"2\",\"low\":\"0.5\",\"close\":\"1.5\",\"volume\":\"100\"},"
                        + "{\"day\":\"这不是日期\",\"open\":\"1\",\"high\":\"2\",\"low\":\"0.5\",\"close\":\"1.5\",\"volume\":\"100\"},"
                        + "{\"day\":\"2026-09-18\",\"open\":\"1\",\"high\":\"2\",\"low\":\"0.5\",\"close\":\"1.5\",\"volume\":\"100\"}]");
        assertEquals(2, candles.size(), "坏行跳过，好行留下");
    }

    @Test
    void parse_nonArrayOrEmpty_isEmptyNotThrow() throws Exception {
        assertTrue(SinaKlineDataSource.parse("").isEmpty());
        assertTrue(SinaKlineDataSource.parse("null").isEmpty());
        assertTrue(SinaKlineDataSource.parse("{\"code\":0}").isEmpty(), "非数组 = 认不出（不抛）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kline_overHttp_returnsCandlesAndUsesSinaUrl() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(SAMPLE);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);
        SinaKlineDataSource source = new SinaKlineDataSource(http);

        List<Candle> candles = source.kline("600519", 120);

        assertEquals(2, candles.size());
        org.mockito.ArgumentCaptor<HttpRequest> captor =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        String url = captor.getValue().uri().toString();
        assertTrue(url.startsWith("https://money.finance.sina.com.cn/"), url);
        assertTrue(url.contains("symbol=sh600519"), url);
        assertTrue(url.contains("scale=240"), url);
    }

    @Test
    @SuppressWarnings("unchecked")
    void kline_httpFailure_returnsEmptyNotThrow() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("network down"));
        assertTrue(new SinaKlineDataSource(http).kline("600519", 120).isEmpty(),
                "安全约定：异常返回空列表，不抛给调用方");
    }

    // ── REVIEW P2-交易58 收口：主动探测（2026-10-04）──

    /** 造 n 行合法 K 线 JSON（够触发 kline 的「缓存够用」判断，用于验证 probe 没写缓存）。 */
    private static String manyRows(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"day\":\"2026-08-%02d\",\"open\":\"1\",\"high\":\"2\",\"low\":\"0.5\",\"close\":\"1.5\",\"volume\":\"100\"}"
                    .formatted(10 + i));
        }
        return sb.append(']').toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void probe_realRequest_true_andBypassesCache() throws Exception {
        // 探测必须**真发请求**：若复用按日缓存，则「当天早些时候成功过、现在挂了」会被探成健康
        // （缓存只在成功时写入）——那正是本次要消灭的假健康。
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(manyRows(10));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);
        SinaKlineDataSource source = new SinaKlineDataSource(http);

        assertTrue(source.probe("600519"), "拿到数据 = 探测通过");
        assertTrue(source.probe("600519"), "第二次仍要真发请求（不读自己的缓存）");
        // 关键：探测**不写缓存** —— 否则紧接着的 kline 会命中缓存、不再发请求（times(2) 会变成 2 而非 3）
        assertEquals(10, source.kline("600519", 10).size());
        verify(http, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void probe_emptyBody_false_notThrow() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn("null"); // 新浪风控/异常时可能给非数组
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        assertFalse(new SinaKlineDataSource(http).probe("600519"), "认不出数据 = 探测不通过");
    }

    @Test
    @SuppressWarnings("unchecked")
    void probe_httpFailure_false_notThrow() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("network down"));

        assertFalse(new SinaKlineDataSource(http).probe("600519"),
                "安全约定：探测失败返回 false，绝不抛给调度线程");

        HttpClient untouched = mock(HttpClient.class);
        assertFalse(new SinaKlineDataSource(untouched).probe(" "), "空标的 → 不通过");
        org.mockito.Mockito.verifyNoInteractions(untouched);
    }
}
