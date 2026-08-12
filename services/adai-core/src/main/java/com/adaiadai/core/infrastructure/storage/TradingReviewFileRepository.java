package com.adaiadai.core.infrastructure.storage;

import org.springframework.stereotype.Repository;
import com.adaiadai.core.kernel.storage.FileStorage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TradingReviewFileRepository — 交易复盘文件存储。
 * <p>
 * 存储路径：{@code data/trading/reviews/YYYY-MM-DD_review.md}
 */
@Repository
public class TradingReviewFileRepository {

    private static final String REVIEWS_DIR = "trading/reviews";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final FileStorage fileStorage;

    public TradingReviewFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 保存复盘笔记。
     */
    public void save(String userId, LocalDate date, String content) {
        String path = filePath(date);
        fileStorage.write(userId, path, content);
    }

    /**
     * 读取指定日期的复盘笔记。
     */
    public String read(String userId, LocalDate date) {
        String path = filePath(date);
        return fileStorage.read(userId, path);
    }

    /**
     * 列出该用户所有复盘文件。
     */
    public List<LocalDate> listAll(String userId) {
        return fileStorage.listFiles(userId, REVIEWS_DIR).stream()
                .map(p -> p.replaceFirst("^" + REVIEWS_DIR + "/?", ""))
                .filter(name -> name.endsWith("_review.md"))
                .map(name -> name.replace("_review.md", ""))
                .map(LocalDate::parse)
                .sorted(Collections.reverseOrder())
                .collect(Collectors.toList());
    }

    /**
     * 判断指定日期复盘是否存在。
     */
    public boolean exists(String userId, LocalDate date) {
        return fileStorage.exists(userId, filePath(date));
    }

    private String filePath(LocalDate date) {
        return REVIEWS_DIR + "/" + date.format(DATE_FMT) + "_review.md";
    }
}
