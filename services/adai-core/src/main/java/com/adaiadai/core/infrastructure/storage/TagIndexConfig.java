package com.adaiadai.core.infrastructure.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * TagIndexConfig — 启动时关联 TagIndexService 到 RecordFileRepository。
 * <p>
 * 绕过循环依赖：RecordFileRepository 和 TagIndexService 互相依赖，
 * 通过 setter 注入在启动时解耦。
 */
@Configuration
public class TagIndexConfig {

    private static final Logger log = LoggerFactory.getLogger(TagIndexConfig.class);

    private final RecordFileRepository recordFileRepository;
    private final TagIndexService tagIndexService;

    public TagIndexConfig(RecordFileRepository recordFileRepository,
                          TagIndexService tagIndexService) {
        this.recordFileRepository = recordFileRepository;
        this.tagIndexService = tagIndexService;
    }

    @PostConstruct
    public void init() {
        recordFileRepository.setTagIndexService(tagIndexService);
        log.info("TagIndexService 已关联到 RecordFileRepository");
    }
}
