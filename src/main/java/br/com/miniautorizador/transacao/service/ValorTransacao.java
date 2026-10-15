package br.com.miniautorizador.transacao.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import br.com.miniautorizador.shared.EntradaInvalidaException;

final class ValorTransacao {
    private static final BigDecimal MAXIMO = new BigDecimal("99999999999999999.99");

    private ValorTransacao() { }

    static BigDecimal normalizar(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0 || valor.compareTo(MAXIMO) > 0) {
            throw new EntradaInvalidaException("Valor fora dos limites permitidos");
        }
        try {
            return valor.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException fracaoDeCentavo) {
            throw new EntradaInvalidaException("Valor deve ser representável em centavos, sem arredondamento");
        }
    }
}