package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.push.PushDevice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PushDeviceFileRepository — 推送设备登记存储测试（RFC 20260913 APNs 批）。
 * <p>
 * 覆盖：登记/幂等刷新/区分大小写去重/注销幂等/用户隔离；
 * 以及本批的 fail-visible 取舍——**损坏文件在写路径必须拒绝写回**（否则一个坏文件被空列表覆盖，
 * 静默丢掉其余设备）。
 */
class PushDeviceFileRepositoryTest {

    private static final String TOKEN_A = "a".repeat(64);
    private static final String TOKEN_B = "b".repeat(64);
    private static final String PATH = "push/devices.json";

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final PushDeviceFileRepository repo = new PushDeviceFileRepository(storage);

    private PushDevice device(String token, String env) {
        return new PushDevice(token, PushDevice.PLATFORM_IOS, env, "com.adaiadai.adaiApp",
                "iPhone", "2026-09-13T05:00:00Z", "2026-09-13T05:00:00Z");
    }

    @Test
    void save_thenFind_roundtrip() {
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));

        List<PushDevice> devices = repo.findByUser("adai");
        assertEquals(1, devices.size());
        assertEquals(TOKEN_A, devices.get(0).token());
        assertEquals(PushDevice.ENV_SANDBOX, devices.get(0).environment());
        assertEquals("com.adaiadai.adaiApp", devices.get(0).bundleId());
        assertEquals("iPhone", devices.get(0).label());
    }

    @Test
    void noFile_returnsEmpty() {
        assertTrue(repo.findByUser("adai").isEmpty());
    }

    @Test
    void sameTokenTwice_refreshesInsteadOfDuplicating() {
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));
        // 第二次上报：环境变了（例如以后换成 TestFlight 的 production token），label 更新
        repo.save("adai", new PushDevice(TOKEN_A, PushDevice.PLATFORM_IOS, PushDevice.ENV_PRODUCTION,
                "com.adaiadai.adaiApp", "Adai 的 iPhone",
                "2026-09-14T05:00:00Z", "2026-09-14T05:00:00Z"));

        List<PushDevice> devices = repo.findByUser("adai");
        assertEquals(1, devices.size(), "同 token 重复上报不应产生第二条");
        assertEquals(PushDevice.ENV_PRODUCTION, devices.get(0).environment(), "环境应刷新");
        assertEquals("Adai 的 iPhone", devices.get(0).label(), "备注应刷新");
        assertEquals("2026-09-13T05:00:00Z", devices.get(0).registeredAt(), "首次注册时间应保留");
        assertEquals("2026-09-14T05:00:00Z", devices.get(0).lastSeenAt(), "最近上报时间应刷新");
    }

    @Test
    void sameToken_differentCase_deduped() {
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));
        repo.save("adai", device(TOKEN_A.toUpperCase(), PushDevice.ENV_SANDBOX));
        assertEquals(1, repo.findByUser("adai").size(), "十六进制大小写差异不应算两台设备");
    }

    @Test
    void differentTokens_bothKept() {
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));
        repo.save("adai", device(TOKEN_B, PushDevice.ENV_SANDBOX));
        assertEquals(2, repo.findByUser("adai").size());
    }

    @Test
    void remove_isIdempotent() {
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));
        assertTrue(repo.remove("adai", TOKEN_A), "首次注销应返回 true");
        assertFalse(repo.remove("adai", TOKEN_A), "重复注销返回 false（幂等，不报错）");
        assertTrue(repo.findByUser("adai").isEmpty());
    }

    @Test
    void remove_keepsOtherDevices() {
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));
        repo.save("adai", device(TOKEN_B, PushDevice.ENV_SANDBOX));
        repo.remove("adai", TOKEN_A);

        List<PushDevice> devices = repo.findByUser("adai");
        assertEquals(1, devices.size());
        assertEquals(TOKEN_B, devices.get(0).token());
    }

    @Test
    void usersAreIsolated() {
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));
        repo.save("bob", device(TOKEN_B, PushDevice.ENV_SANDBOX));

        assertEquals(TOKEN_A, repo.findByUser("adai").get(0).token());
        assertEquals(TOKEN_B, repo.findByUser("bob").get(0).token());
        assertTrue(repo.findByUser("carol").isEmpty());
    }

    // ── fail-visible 取舍：读降级 vs 写拒绝 ──

    @Test
    void corruptFile_readDegradesToEmpty() {
        storage.write("adai", PATH, "{ 这不是 JSON");
        assertTrue(repo.findByUser("adai").isEmpty(), "读路径（推送投递）应降级为空，不阻断推送");
    }

    @Test
    void corruptFile_writeRefusesInsteadOfOverwriting() {
        storage.write("adai", PATH, "{ 这不是 JSON");
        StorageException e = assertThrows(StorageException.class,
                () -> repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX)),
                "写路径遇到损坏文件必须拒绝，防用空列表覆盖掉可能还在的其它设备");
        assertTrue(e.getMessage().contains("损坏"), "异常信息应说明原因: " + e.getMessage());
        assertEquals("{ 这不是 JSON", storage.read("adai", PATH), "损坏文件内容不得被改写");
    }

    @Test
    void corruptFile_removeAlsoRefuses() {
        storage.write("adai", PATH, "[]]");
        assertThrows(StorageException.class, () -> repo.remove("adai", TOKEN_A));
    }

    @Test
    void emptyArrayFile_treatedAsNoDevice() {
        storage.write("adai", PATH, "[]");
        assertTrue(repo.findByUser("adai").isEmpty());
        // 空数组是合法状态，可以正常登记
        repo.save("adai", device(TOKEN_A, PushDevice.ENV_SANDBOX));
        assertEquals(1, repo.findByUser("adai").size());
    }
}
