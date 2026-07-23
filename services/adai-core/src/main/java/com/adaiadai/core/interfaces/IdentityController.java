package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * IdentityController — 个人档案读写入口。
 * <p>
 * GET  /api/v1/identity → 读取
 * PUT  /api/v1/identity → 全量覆盖
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {

    private static final Logger log = LoggerFactory.getLogger(IdentityController.class);

    private final IdentityRepository identityRepository;

    public IdentityController(IdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    /**
     * 读取个人档案。文件不存在时返回默认档案，不会 404。
     */
    @GetMapping
    public ResponseEntity<IdentityProfile> getIdentity() {
        return ResponseEntity.ok(identityRepository.load().orElse(null));
    }

    /**
     * 更新（全量覆盖）个人档案。
     */
    @PutMapping
    public ResponseEntity<?> updateIdentity(@Valid @RequestBody IdentityRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 不能为空"));
        }
        if (request.tags() == null || request.tags().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tags 不能为空"));
        }

        IdentityProfile profile = new IdentityProfile(
                request.name(),
                request.preferences() != null ? request.preferences() : Map.of(),
                request.rules() != null ? request.rules() : Map.of(),
                request.tags() != null ? request.tags() : List.of()
        );

        try {
            IdentityProfile saved = identityRepository.save(profile);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("身份保存失败", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "身份保存失败: " + e.getMessage()));
        }
    }

    // ── Request DTO ──

    public record IdentityRequest(
            @NotBlank String name,
            Map<String, String> preferences,
            Map<String, String> rules,
            @NotEmpty List<String> tags
    ) {}
}
