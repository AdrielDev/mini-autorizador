package br.com.miniautorizador.cartao.repository;

import br.com.miniautorizador.cartao.model.Cartao;

import java.math.BigDecimal;
import java.util.Optional;

public interface CartaoRepository {
  boolean inserir(Cartao cartao);
  Optional<Cartao> buscar(String numero);
  int debitarSeHouverSaldo(String numero, BigDecimal valor);
}
