package br.com.miniautorizador.transacao.service;

public enum ResultadoAutorizacao {
  APROVADA("OK"),
  CARTAO_INEXISTENTE("CARTAO_INEXISTENTE"),
  SENHA_INVALIDA("SENHA_INVALIDA"),
  SALDO_INSUFICIENTE("SALDO_INSUFICIENTE");

  private final String resposta;

  ResultadoAutorizacao(String resposta) {
    this.resposta = resposta;
  }

  public String resposta() {
    return resposta;
  }
}
