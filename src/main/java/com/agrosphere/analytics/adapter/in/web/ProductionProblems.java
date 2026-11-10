package com.agrosphere.analytics.adapter.in.web;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ProductionProblems {
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException e){return problem(e.getStatusCode().value(),"Production history request could not be completed");}
 @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class}) ResponseEntity<?> invalid(Exception e){return problem(400,"Invalid production history request");}
 @ExceptionHandler({org.springframework.dao.DataAccessException.class,SecurityException.class}) ResponseEntity<?> unavailable(Exception e){return problem(503,"Production history unavailable");}
 private ResponseEntity<?> problem(int code,String detail){return ResponseEntity.status(code).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(Map.of("type","about:blank","title",HttpStatus.valueOf(code).getReasonPhrase(),"status",code,"detail",detail));}
}
