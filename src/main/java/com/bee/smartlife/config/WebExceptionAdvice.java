package com.bee.smartlife.config;

import com.bee.smartlife.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.bee.smartlife.security.ApiException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result> handleSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(Result.fail("图片不能超过5MiB"));
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
