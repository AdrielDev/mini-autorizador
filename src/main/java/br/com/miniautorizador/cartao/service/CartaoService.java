package br.com.miniautorizador.cartao.service;

import br.com.miniautorizador.cartao.model.Cartao;
import br.com.miniautorizador.cartao.repository.CartaoRepository;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service 
public class CartaoService {
  private static final BigDecimal SALDO_INICIAL = new BigDecimal("500.00");

  private final CartaoRepository repository;
  private final PasswordEncoder encoder;

  public CartaoService(CartaoRepository repository, PasswordEncoder encoder) {
    this.repository = repository;
    this.encoder = encoder;
  }

  public ResultadoCriacao criar(String numero, String senha) {
    var cartao = new Cartao(numero, encoder.encode(senha), SALDO_INICIAL);
    return repository.inserir(cartao) ? ResultadoCriacao.CRIADO : ResultadoCriacao.DUPLICADO;
  }

  public Optional<BigDecimal> consultarSaldo(String numero) {
    return repository.buscar(numero).map(Cartao::saldo);
  }
}
