package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdentityController 测试。
 * 验证读取、更新、默认降级行为。
 */
class IdentityControllerTest {

    private final IdentityRepository repo = new IdentityRepository() {
        @Override
        public Optional<IdentityProfile> load(String userId) {
            return Optional.of(new IdentityProfile("阿呆", Map.of(), Map.of(), List.of("投资")));
        }

        @Override
        public IdentityProfile save(String userId, IdentityProfile profile) {
            return profile;
        }
    };

    private final IdentityRepository emptyRepo = new IdentityRepository() {
        @Override
        public Optional<IdentityProfile> load(String userId) {
            return Optional.empty();
        }

        @Override
        public IdentityProfile save(String userId, IdentityProfile profile) {
            return profile;
        }
    };

    @Test
    void getIdentity_returns200() {
        var controller = new IdentityController(repo);
        ResponseEntity<IdentityProfile> resp = controller.getIdentity("default");
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("阿呆", resp.getBody().name());
    }

    @Test
    void getIdentity_whenMissing_returns200WithNullBody() {
        // 文件不存在时返回 200 + 空响应体（前端自行处理）
        var controller = new IdentityController(emptyRepo);
        ResponseEntity<IdentityProfile> resp = controller.getIdentity("default");
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void updateIdentity() {
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest("新名字", Map.of(), Map.of(), List.of("A", "B"));
        ResponseEntity<?> resp = controller.updateIdentity("default", request);
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void updateIdentity_emptyName_isAllowed() {
        // 2026-09-16「第一次见面」批：新用户还没填昵称也能保存档案（此前 400 挡在门外）
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest("", Map.of(), Map.of(), List.of("A"));
        ResponseEntity<?> resp = controller.updateIdentity("default", request);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("", ((IdentityProfile) resp.getBody()).name());
    }

    @Test
    void updateIdentity_emptyTags_isAllowed() {
        // 零画像用户没有标签，不该保存失败
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest("名字", Map.of(), Map.of(), List.of());
        ResponseEntity<?> resp = controller.updateIdentity("default", request);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(((IdentityProfile) resp.getBody()).tags().isEmpty());
    }

    @Test
    void updateIdentity_allNull_isAllowed() {
        // 全空请求（前端新用户首次保存可能只带 name）不 NPE、不 400
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest(null, null, null, null);
        ResponseEntity<?> resp = controller.updateIdentity("default", request);
        assertEquals(200, resp.getStatusCode().value());
        IdentityProfile saved = (IdentityProfile) resp.getBody();
        assertEquals("", saved.name());
        assertTrue(saved.preferences().isEmpty());
        assertTrue(saved.rules().isEmpty());
        assertTrue(saved.tags().isEmpty());
    }

    @Test
    void updateIdentity_nameIsTrimmed() {
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest("  小明  ", Map.of(), Map.of(), List.of());
        ResponseEntity<?> resp = controller.updateIdentity("default", request);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("小明", ((IdentityProfile) resp.getBody()).name());
    }
}
