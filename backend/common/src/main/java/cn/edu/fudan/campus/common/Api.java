package cn.edu.fudan.campus.common;

import java.util.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

public final class Api {
  private Api() {}

  public static Map<String, Object> ok(Object data) {
    return Map.of("data", publicFields(data), "requestId", Trace.id());
  }

  // JDBC row names stay local to persistence; wire fields follow the shared camelCase contract.
  private static Object publicFields(Object value) {
    if (value instanceof Map<?, ?> row) {
      Map<String, Object> converted = new LinkedHashMap<>();
      row.forEach(
          (key, item) -> {
            String name = key.toString();
            var matcher = java.util.regex.Pattern.compile("_([a-z])").matcher(name);
            name = matcher.replaceAll(match -> match.group(1).toUpperCase(Locale.ROOT));
            converted.put(name, publicFields(item));
          });
      return converted;
    }
    if (value instanceof Collection<?> values)
      return values.stream().map(Api::publicFields).toList();
    return value;
  }

  public static Failure fail(int status, String code, String message) {
    return new Failure(status, code, message);
  }

  public static class Failure extends RuntimeException {
    public final int status;
    public final String code;

    public Failure(int s, String c, String m) {
      super(m);
      status = s;
      code = c;
    }
  }

  @RestControllerAdvice
  public static class Errors {
    @ExceptionHandler(Failure.class)
    ResponseEntity<?> known(Failure e) {
      return response(e.status, e.code, e.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
      Map<String, String> fields = new LinkedHashMap<>();
      e.getBindingResult()
          .getFieldErrors()
          .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
      return response(400, "VALIDATION", "请检查输入", fields);
    }

    @ExceptionHandler({
      HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class,
      IllegalArgumentException.class
    })
    ResponseEntity<?> bad(Exception e) {
      return response(400, "BAD_REQUEST", "请求格式不正确", Map.of());
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<?> duplicate(Exception e) {
      return response(409, "CONCURRENT_COMMAND", "并发操作冲突，请使用原幂等键重试", Map.of());
    }

    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)
    ResponseEntity<?> locked(Exception e) {
      return response(409, "RESOURCE_BUSY", "资源正在更新，请稍后重试", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception e) {
      org.slf4j.LoggerFactory.getLogger(Errors.class)
          .error("Request failed requestId={}", Trace.id(), e);
      return response(500, "INTERNAL_ERROR", "服务内部错误", Map.of());
    }

    private ResponseEntity<?> response(int s, String c, String m, Object f) {
      return ResponseEntity.status(s)
          .body(Map.of("code", c, "message", m, "fieldErrors", f, "requestId", Trace.id()));
    }
  }
}
