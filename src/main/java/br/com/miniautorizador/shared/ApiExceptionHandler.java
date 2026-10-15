package br.com.miniautorizador.shared;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({
      MethodArgumentNotValidException.class,
      HttpMessageNotReadableException.class,
      HandlerMethodValidationException.class,
      ConstraintViolationException.class,
      EntradaInvalidaException.class
  })
  public ResponseEntity<ApiErro> entradaInvalida() {
    return ResponseEntity.badRequest()
        .body(new ApiErro(
            "REQUISICAO_INVALIDA",
            "Verifique os campos enviados."));
  }

  @ExceptionHandler({
      DataAccessException.class,
      TransactionException.class
  })
  public ResponseEntity<ApiErro> persistenciaIndisponivel(
      RuntimeException exception) {

    LOGGER.error(
        "Falha de persistência. Tipo: {}",
        exception.getClass().getSimpleName());

    return ResponseEntity.status(503)
        .body(new ApiErro(
            "ERRO_PERSISTENCIA",
            "Não foi possível concluir a operação."));
  }
}
