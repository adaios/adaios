package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * TagIndexBootstrap — 启动时全量重建标签索引（REVIEW P1-W15）。
 * <p>
 * 背景：增量索引（onRecordSaved）只覆盖「保存路径」触发的记录，历史记录/迁移/直接写文件
 * 的记录未索引——实测仅覆盖 17/201 记录，ContextEngine 标签关联注入失效。
 * 方案：每次启动对每个启用账号全量 rebuild（单用户 200+ 文件，秒级；多账号后可评估增量收敛）。
 * 运行中仍走 onRecordSaved 增量，启动重建只补历史缺口。
 */
@Component
public class TagIndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TagIndexBootstrap.class);

    private final AccountRepository accountRepository;
    private final RecordRepository recordRepository;
    private final TagIndexService tagIndexService;

    public TagIndexBootstrap(AccountRepository accountRepository,
                             RecordRepository recordRepository,
                             TagIndexService tagIndexService) {
        this.accountRepository = accountRepository;
        this.recordRepository = recordRepository;
        this.tagIndexService = tagIndexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Account account : accountRepository.findAll()) {
            if (!account.enabled()) continue;
            try {
                tagIndexService.rebuild(account.userId(), recordRepository.findAll(account.userId()));
                log.info("启动标签索引重建完成 | userId={}", account.userId());
            } catch (Exception e) {
                // 重建失败不阻塞启动（索引可后续重建，记录仍可用）
                log.warn("启动标签索引重建失败 | userId={} | {}", account.userId(), e.getMessage());
            }
        }
    }
}
