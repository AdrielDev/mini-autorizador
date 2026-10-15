package br.com.miniautorizador.transacao.service;

import br.com.miniautorizador.cartao.repository.CartaoRepository;

import java.math.BigDecimal;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutorizacaoService {

  private final CartaoRepository repository;
  private final PasswordEncoder encoder;

  public AutorizacaoService(
      CartaoRepository repository,
      PasswordEncoder encoder) {
    this.repository = repository;
    this.encoder = encoder;
  }

  @Transactional
  public ResultadoAutorizacao autorizar(
      String numero,
      String senha,
      BigDecimal valor) {

    var valorNormalizado = ValorTransacao.normalizar(valor);
    var cartao = repository.buscar(numero);

    if (cartao.isEmpty()) {
      return ResultadoAutorizacao.CARTAO_INEXISTENTE;
    }

    if (!encoder.matches(senha, cartao.get().senhaHash())) {
      return ResultadoAutorizacao.SENHA_INVALIDA;
    }

    int linhas = repository.debitarSeHouverSaldo(
        numero, valorNormalizado);

    if (linhas == 1) {
      return ResultadoAutorizacao.APROVADA;
    }

    if (linhas == 0) {
      return ResultadoAutorizacao.SALDO_INSUFICIENTE;
    }

    throw new IllegalStateException(
        "Débito atingiu quantidade inesperada de cartões");
  }
}