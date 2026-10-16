package br.com.miniautorizador.transacao.service;

import br.com.miniautorizador.cartao.repository.CartaoRepository;

import java.math.BigDecimal;
import java.util.Optional;

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

    return repository.buscar(numero)
        .map(cartao -> Optional.of(cartao)
            .filter(cadastrado -> encoder.matches(senha, cadastrado.senhaHash()))
            .map(cadastrado -> debitar(numero, valorNormalizado))
            .orElse(ResultadoAutorizacao.SENHA_INVALIDA))
        .orElse(ResultadoAutorizacao.CARTAO_INEXISTENTE);
  }

  private ResultadoAutorizacao debitar(String numero, BigDecimal valor) {
    int linhas = repository.debitarSeHouverSaldo(numero, valor);

    return switch (linhas) {
      case 1 -> ResultadoAutorizacao.APROVADA;
      case 0 -> ResultadoAutorizacao.SALDO_INSUFICIENTE;
      default -> throw new IllegalStateException(
          "Débito atingiu quantidade inesperada de cartões");
    };
  }
}