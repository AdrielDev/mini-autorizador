package br.com.miniautorizador.cartao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.miniautorizador.cartao.model.Cartao;
import br.com.miniautorizador.cartao.repository.CartaoRepository;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;



@ExtendWith(MockitoExtension.class)
public class CartaoServiceTest {

  private static final String NUMERO = "0000000000000001";

  @Mock
  private CartaoRepository repository;

  private final PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  private CartaoService service;

  @BeforeEach
  void configurar() {
    service = new CartaoService(repository, encoder);
  }

  @Test
  void criaComSaldoInicialEHashDaSenhaPreservada() {
    when(repository.inserir(any(Cartao.class))).thenReturn(true);

    var resultado = service.criar(NUMERO, " senha preservada ");

    assertThat(resultado).isEqualTo(ResultadoCriacao.CRIADO);

    var captor = ArgumentCaptor.forClass(Cartao.class);
    verify(repository).inserir(captor.capture());

    var cartao = captor.getValue();
    assertThat(cartao.numero()).isEqualTo(NUMERO);
    assertThat(cartao.saldo()).isEqualByComparingTo("500.00");
    assertThat(cartao.saldo().scale()).isEqualTo(2);
    assertThat(cartao.senhaHash()).isNotEqualTo(" senha preservada ");
    assertThat(encoder.matches(
        " senha preservada ", cartao.senhaHash())).isTrue();
    assertThat(encoder.matches(
        "senha preservada", cartao.senhaHash())).isFalse();
  }

  @Test
  void informaDuplicidadeQuandoRepositoryNaoInsere() {
    when(repository.inserir(any(Cartao.class))).thenReturn(false);

    var resultado = service.criar(NUMERO, "1234");

    assertThat(resultado).isEqualTo(ResultadoCriacao.DUPLICADO);
  }

  @Test
  void consultaSaldoPersistido() {
    var cartao = new Cartao(
        NUMERO, "hash", new BigDecimal("495.15"));

    when(repository.buscar(NUMERO)).thenReturn(Optional.of(cartao));

    assertThat(service.consultarSaldo(NUMERO))
        .contains(new BigDecimal("495.15"));
  }

  @Test
  void consultaDeCartaoAusenteRetornaVazio() {
    when(repository.buscar(NUMERO)).thenReturn(Optional.empty());

    assertThat(service.consultarSaldo(NUMERO)).isEmpty();
  }

  @Test
  void falhaNaCriacaoNaoViraDuplicidade() {
    var falha = new DataAccessResourceFailureException(
        "banco indisponivel");

    when(repository.inserir(any(Cartao.class))).thenThrow(falha);

    assertThatThrownBy(() -> service.criar(NUMERO, "1234"))
        .isSameAs(falha);
  }

  @Test
  void falhaNaConsultaNaoViraCartaoInexistente() {
    var falha = new DataAccessResourceFailureException(
        "banco indisponivel");

    when(repository.buscar(NUMERO)).thenThrow(falha);

    assertThatThrownBy(() -> service.consultarSaldo(NUMERO))
        .isSameAs(falha);
  }
}
