package vip.mate.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import vip.mate.common.result.R;

/**
 * 全局异常处理器
 *
 * @author MateClaw Team
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public R<Void> handleAsyncTimeout(AsyncRequestTimeoutException e,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        if (isSseRequest(request) || response.isCommitted()) {
            log.debug("SSE async timeout (normal lifecycle): {} {}", request.getMethod(), request.getRequestURI());
            // 不返回任何 body，避免 text/event-stream 无法序列化 R 的问题
            // 返回 null 让框架自然结束异步请求
            return null;
        }
        log.warn("Async request timeout: {} {}", request.getMethod(), request.getRequestURI());
        return R.fail(503, "Request timeout, please try again");
    }

    @ExceptionHandler(MateClawException.class)
    public R<Void> handleMateClawException(MateClawException e) {
        log.warn("Business exception: [{}] {}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation failed: {}", msg);
        return R.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        // response 已提交或 SSE 请求，不再尝试写 JSON body
        if (response.isCommitted() || isSseRequest(request)) {
            log.warn("Exception after response committed or during SSE (suppressed): {} {} - {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
            return null;
        }
        log.error("Unexpected error", e);
        return R.fail("Internal error: " + e.getMessage());
    }

    /**
     * 判断是否为 SSE 请求：检查 Accept 头 或 已设置的 Content-Type
     */
    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        // 备选：路径匹配
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/chat/stream");
    }
}
