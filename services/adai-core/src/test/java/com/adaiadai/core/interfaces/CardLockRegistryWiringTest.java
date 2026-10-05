package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.ActionReviewService;
import com.adaiadai.core.application.MediaRecordAppService;
import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.application.TradeLogCollectService;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.CardLockRegistry;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.StreamingAiClient;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * REVIEW P2-工程13 接线验证：三处卡片写路径（end / 问答 append / 图片 append）必须拿到**同一个**
 * {@link CardLockRegistry} 单例——否则「共锁」只在源码上成立、运行期仍各锁各的（等于没修）。
 *
 * <p>同时守住「每个类只有一个 {@code @Autowired} 构造器」：本批为兼容测试保留了无锁池的旧构造器，
 * 若 Spring 选错构造器，生产会拿到私有锁池（回归原 bug）或直接启动失败。
 */
class CardLockRegistryWiringTest {

    @Configuration
    static class Wiring {
        @Bean
        CardLockRegistry cardLockRegistry() {
            return new CardLockRegistry();
        }

        /** REVIEW P2-交易73：ConversationController 新增的动作搬运器（端侧接线测试只需一个桩）。 */
        @Bean
        com.adaiadai.core.application.ActionReviewService actionReviewService() {
            return mock(com.adaiadai.core.application.ActionReviewService.class);
        }

        @Bean
        AiClient aiClient() {
            return mock(AiClient.class);
        }

        @Bean
        StreamingAiClient streamingAiClient() {
            return mock(StreamingAiClient.class);
        }

        @Bean
        VisualAiClient visualAiClient() {
            return mock(VisualAiClient.class);
        }

        @Bean
        ContextEngine contextEngine() {
            return mock(ContextEngine.class);
        }

        @Bean
        PluginService pluginService() {
            return mock(PluginService.class);
        }

        @Bean
        FileStorage fileStorage() {
            return mock(FileStorage.class);
        }

        @Bean
        RecordRepository recordRepository() {
            return mock(RecordRepository.class);
        }

        @Bean
        RecordFileRepository recordFileRepository() {
            return mock(RecordFileRepository.class);
        }

        @Bean
        CardFileRepository cardFileRepository() {
            return mock(CardFileRepository.class);
        }

        @Bean
        MemoryService memoryService() {
            return mock(MemoryService.class);
        }

        @Bean
        TradeLogCollectService tradeLogCollectService() {
            return mock(TradeLogCollectService.class);
        }
    }

    /** 让 Spring **自己**解析构造器（而非测试手工 new）：若 @Autowired 选错/缺失，本用例会失败。 */
    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(Wiring.class, CardLockRegistry.class,
                ConversationController.class, QuestionAppService.class, MediaRecordAppService.class,
                com.adaiadai.core.infrastructure.storage.CardMigrationService.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void allCardWritePathsShareOneRegistryInstance() throws Exception {
        try (AnnotationConfigApplicationContext ctx = context()) {
            CardLockRegistry shared = ctx.getBean(CardLockRegistry.class);

            Object fromEnd = registryOf(ctx.getBean(ConversationController.class));
            Object fromQuestion = registryOf(ctx.getBean(QuestionAppService.class));
            Object fromMedia = registryOf(ctx.getBean(MediaRecordAppService.class));
            Object fromMigration = registryOf(
                    ctx.getBean(com.adaiadai.core.infrastructure.storage.CardMigrationService.class));

            assertNotNull(shared);
            assertSame(shared, fromEnd, "ConversationController.end 必须用共享锁池");
            assertSame(shared, fromQuestion, "QuestionAppService 的两处 append 必须用共享锁池");
            assertSame(shared, fromMedia, "MediaRecordAppService 的图片 append 必须用共享锁池");
            assertSame(shared, fromMigration,
                    "CardMigrationService 的迁移写盘/删除必须用共享锁池（P2-工程13 发现3）");
        }
    }

    @Test
    void productionConstructors_areUnambiguousForSpring() {
        for (Class<?> type : new Class<?>[]{ConversationController.class, QuestionAppService.class,
                MediaRecordAppService.class,
                com.adaiadai.core.infrastructure.storage.CardMigrationService.class}) {
            int autowired = 0;
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                if (c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) autowired++;
            }
            assertTrue(autowired <= 1, type.getSimpleName() + " 只能有一个 @Autowired 构造器（多构造器歧义）");
        }
    }

    /** 反射读私有字段：验证注入的是哪个锁池实例。 */
    private static Object registryOf(Object bean) throws Exception {
        Field f = bean.getClass().getDeclaredField("cardLockRegistry");
        f.setAccessible(true);
        return f.get(bean);
    }
}
