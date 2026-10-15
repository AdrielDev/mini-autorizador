package br.com.miniautorizador.cartao.model;

import java.math.BigDecimal;

public record Cartao (String numero, String senhaHash, BigDecimal saldo) {
  @Override
  public String toString() {
    return "Cartao[dados omitidos]";
  }
}
