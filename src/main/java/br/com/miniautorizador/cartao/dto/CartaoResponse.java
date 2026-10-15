package br.com.miniautorizador.cartao.dto;

public record CartaoResponse(String numeroCartao, String senha) {
  @Override
  public String toString() {
    return "CartaoResponse[dados omitidos]";
  }
}
