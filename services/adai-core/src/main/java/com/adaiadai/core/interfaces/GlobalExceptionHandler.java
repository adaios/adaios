package com.adaiadai.core.interfaces;

import com.adaiadai.core.domain.trading.TradingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 全局异常处理。
 * <p>
 * 业务异常（{@link TradingException} 等）映射为 4xx + 人类可读消息，
 * 避免静默 no-op 或 500 堆栈裸奔（REVIEW #147）。
 * 上传超限映射 413（REVIEW #166：multipart 超限原走 500，应 PAYLOAD_TOO_LARGE）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // REVIEW #250：上传上限从配置读（spring.servlet.multipart.max-file-size），
    // 避免 413 提示与 application.yml 漂移失真。
    @Value("${spring.servlet.multipart.max-file-size:5MB}")
    private String maxFileSize;

    @ExceptionHandler(TradingException.class)
    public ResponseEntity<Map<String, String>> handleTradingException(TradingException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * 参数校验失败 → 400 + 人话消息（RFC 20260816：BUY 缺止损/买点等必填项校验）。
     * 优先取字段错误（@NotBlank/@Positive…），再取类级错误（自定义 BuyFieldsRequired），
     * 两者皆无时给通用提示——不让裸的校验异常堆栈直出。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElseGet(() -> e.getBindingResult().getGlobalErrors().stream()
                        .findFirst()
                        .map(ObjectError::getDefaultMessage)
                        .orElse("请求参数校验失败"));
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "文件超过大小限制（图片最大 " + maxFileSize + "）"));
    }
}
