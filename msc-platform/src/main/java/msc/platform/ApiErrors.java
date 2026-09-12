package msc.platform;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public final class ApiErrors {
  public record Error(String code, String message) {}

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Error> handle(ApiException e) {
    return ResponseEntity.status(e.status()).body(new Error(e.code(), e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<Error> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(new Error("INVALID_INPUT", "Invalid or incomplete request"));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<Error> duplicate(Exception e) {
    return ResponseEntity.status(409).body(new Error("DUPLICATE", "Identity already exists"));
  }
}
