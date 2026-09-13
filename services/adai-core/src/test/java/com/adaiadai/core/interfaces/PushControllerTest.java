package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.PushDeviceAppService;
import com.adaiadai.core.kernel.push.PushDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PushControllerTest — 推送设备端点（RFC 20260913 APNs 批）。
 * <p>
 * 覆盖：登记成功/缺 token 400/非法 token 400（人话）/环境归一化透传/列表/注销幂等/状态自检；
 * 以及 X-User-Id 隔离（userId 必须原样透传到 application 层）。
 */
class PushControllerTest {

    private static final String TOKEN = "e".repeat(64);

    private final PushDeviceAppService service = mock(PushDeviceAppService.class);
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new PushController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private PushDevice sample(String env) {
        return new PushDevice(TOKEN, PushDevice.PLATFORM_IOS, env, "com.adaiadai.adaiApp",
                "iPhone", "2026-09-13T05:00:00Z", "2026-09-13T05:00:00Z");
    }

    @Test
    void register_success_returnsDevice() throws Exception {
        when(service.register(eq("adai"), eq(TOKEN), any(), any(), any(), any()))
                .thenReturn(sample(PushDevice.ENV_SANDBOX));

        mvc().perform(post("/api/v1/push/devices")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"platform\":\"ios\","
                                + "\"environment\":\"development\",\"bundleId\":\"com.adaiadai.adaiApp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(TOKEN))
                .andExpect(jsonPath("$.environment").value("sandbox"));
    }

    @Test
    void register_userIdIsPassedThrough() throws Exception {
        when(service.register(anyString(), any(), any(), any(), any(), any())).thenReturn(sample("sandbox"));

        mvc().perform(post("/api/v1/push/devices")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk());

        verify(service).register(eq("bob"), eq(TOKEN), any(), any(), any(), any());
    }

    @Test
    void register_missingToken_returns400() throws Exception {
        mvc().perform(post("/api/v1/push/devices")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ios\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少设备推送标识"));
        verify(service, never()).register(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void register_illegalToken_returns400WithHumanMessage() throws Exception {
        mvc().perform(post("/api/v1/push/devices")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"../../etc/passwd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("设备推送标识不合法（应为十六进制 token）"));
        verify(service, never()).register(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void register_emptyBody_returns400() throws Exception {
        mvc().perform(post("/api/v1/push/devices")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsDevices() throws Exception {
        when(service.devices("adai")).thenReturn(List.of(sample("sandbox")));

        mvc().perform(get("/api/v1/push/devices").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].token").value(TOKEN));
    }

    @Test
    void list_noDevice_returnsEmptyArray() throws Exception {
        when(service.devices("adai")).thenReturn(List.of());
        mvc().perform(get("/api/v1/push/devices").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unregister_returnsRemovedFlag() throws Exception {
        when(service.unregister("adai", TOKEN)).thenReturn(true);

        mvc().perform(delete("/api/v1/push/devices/" + TOKEN).header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));
    }

    @Test
    void unregister_unknownToken_returnsFalseNotError() throws Exception {
        when(service.unregister("adai", TOKEN)).thenReturn(false);

        mvc().perform(delete("/api/v1/push/devices/" + TOKEN).header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(false));
    }

    @Test
    void status_returnsChannelsAndDeviceCount() throws Exception {
        when(service.status("adai")).thenReturn(Map.of(
                "channels", List.of(Map.of("name", "apns", "enabled", true)),
                "deviceCount", 1,
                "devices", List.of(sample("sandbox"))));

        mvc().perform(get("/api/v1/push/status").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].name").value("apns"))
                .andExpect(jsonPath("$.channels[0].enabled").value(true))
                .andExpect(jsonPath("$.deviceCount").value(1));
    }

    @Test
    void storageFailure_returns500WithMessage() throws Exception {
        when(service.register(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new com.adaiadai.core.infrastructure.storage.StorageException("推送设备文件已损坏，本次写入已取消"));

        mvc().perform(post("/api/v1/push/devices")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("推送设备文件已损坏，本次写入已取消"));
    }
}
