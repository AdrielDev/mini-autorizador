package br.com.miniautorizador.shared;

public class EntradaInvalidaException extends RuntimeException {

  public EntradaInvalidaException(String message) {
    super(message);
  }
}
