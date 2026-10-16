package br.com.miniautorizador.transacao.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import br.com.miniautorizador.shared.EntradaInvalidaException;

final class ValorTransacao {
  private static final BigDecimal MAXIMO = new BigDecimal("99999999999999999.99");

  private ValorTransacao() {
  }

  static BigDecimal normalizar(BigDecimal valor) {
    var valorValido = Optional.ofNullable(valor)
        .filter(v -> v.signum() > 0 && v.compareTo(MAXIMO) <= 0)
        .orElseThrow(() -> new EntradaInvalidaException("Valor fora dos limites permitidos"));

    try {
      return valorValido.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException fracaoDeCentavo) {
      throw new EntradaInvalidaException(
          "Valor deve ser representável em centavos, sem arredondamento");
    }
  }
}