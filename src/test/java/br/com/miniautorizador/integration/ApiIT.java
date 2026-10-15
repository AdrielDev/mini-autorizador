package br.com.miniautorizador.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.miniautorizador.cartao.repository.CartaoRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIT {

  private static final AtomicLong SEQUENCE =
      new AtomicLong(System.currentTimeMillis() * 100);

  private final List<String> numerosDoTeste = new ArrayList<>();

  @Autowired
  private MockMvc mvc;

  @Autowired
  private CartaoRepository repository;

  @Autowired
  private PasswordEncoder encoder;

  @Autowired
  private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void configurarBanco(DynamicPropertyRegistry registry) {
    MySqlDatabase.configurar(registry);
  }

  @AfterEach
  void limparDadosDoTeste() {
    for (var numero : numerosDoTeste) {
      jdbc.update(
          "DELETE FROM cartoes WHERE numero_cartao = ?",
          numero
      );
    }
  }

  @Test
  void aplicaSchemaEsperadoNoMySql57() {
    assertThat(jdbc.queryForObject(
        "SELECT VERSION()", String.class))
        .startsWith("5.7.");

    assertThat(jdbc.queryForObject("""
        SELECT CHARACTER_MAXIMUM_LENGTH
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'cartoes'
          AND column_name = 'numero_cartao'
        """, Integer.class))
        .isEqualTo(16);

    assertThat(jdbc.queryForObject("""
        SELECT COLUMN_DEFAULT
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'cartoes'
          AND column_name = 'saldo'
        """, String.class))
        .isNull();
  }

  @Test
  void cadastraEConsultaSaldoInicial() throws Exception {
    var numero = novoNumero();

    cadastrar(numero, "1234")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.numeroCartao").value(numero))
        .andExpect(jsonPath("$.senha").value("1234"));

    consultar(numero)
        .andExpect(status().isOk())
        .andExpect(content().string("500.00"));

    var persistido = repository.buscar(numero).orElseThrow();
    assertThat(persistido.numero()).isEqualTo(numero);
    assertThat(persistido.saldo()).isEqualByComparingTo("500.00");
  }

  @Test
  void duplicidadePreservaHashESaldoDoCartao() throws Exception {
    var numero = novoNumero();

    cadastrar(numero, "1234").andExpect(status().isCreated());

    jdbc.update(
        "UPDATE cartoes SET saldo = ? WHERE numero_cartao = ?",
        new BigDecimal("123.45"),
        numero
    );

    var original = repository.buscar(numero).orElseThrow();

    cadastrar(numero, "outra senha")
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.numeroCartao").value(numero))
        .andExpect(jsonPath("$.senha").value("outra senha"));

    var depois = repository.buscar(numero).orElseThrow();

    assertThat(depois).isEqualTo(original);
    assertThat(encoder.matches("1234", depois.senhaHash())).isTrue();
    assertThat(encoder.matches(
        "outra senha", depois.senhaHash())).isFalse();

    consultar(numero)
        .andExpect(status().isOk())
        .andExpect(content().string("123.45"));
  }

  @Test
  void senhaEhPersistidaComoHashComSalt() throws Exception {
    var primeiroNumero = novoNumero();
    var segundoNumero = novoNumero();
    var senha = " mesma senha ";

    cadastrar(primeiroNumero, senha)
        .andExpect(status().isCreated());
    cadastrar(segundoNumero, senha)
        .andExpect(status().isCreated());

    var primeiro = repository.buscar(primeiroNumero).orElseThrow();
    var segundo = repository.buscar(segundoNumero).orElseThrow();

    assertThat(primeiro.senhaHash())
        .isNotEqualTo(senha)
        .isNotEqualTo(segundo.senhaHash());

    assertThat(encoder.matches(
        senha, primeiro.senhaHash())).isTrue();
    assertThat(encoder.matches(
        senha.trim(), primeiro.senhaHash())).isFalse();
  }

  @Test
  void cartaoInexistenteRetorna404SemCorpo() throws Exception {
    consultar(novoNumero())
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  void saldoRecebidoNaEntradaNaoAlteraSaldoInicial()
      throws Exception {

    var numero = novoNumero();

    mvc.perform(post("/cartoes")
            .with(httpBasic("username", "password"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"numeroCartao\":\"" + numero
                + "\",\"senha\":\"1234\",\"saldo\":9999}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.*", hasSize(2)));

    consultar(numero)
        .andExpect(status().isOk())
        .andExpect(content().string("500.00"));
  }

  @Test
  void requisicaoSemAutenticacaoNaoPersisteCartao()
      throws Exception {

    var numero = novoNumero();

    mvc.perform(post("/cartoes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"numeroCartao\":\"" + numero
                + "\",\"senha\":\"1234\"}"))
        .andExpect(status().isUnauthorized());

    assertThat(repository.buscar(numero)).isEmpty();
  }

  private String novoNumero() {
    var numero = String.format(
        Locale.ROOT,
        "%016d",
        SEQUENCE.incrementAndGet()
    );

    numerosDoTeste.add(numero);
    return numero;
  }

  private ResultActions cadastrar(String numero, String senha)
      throws Exception {

    return mvc.perform(post("/cartoes")
        .with(httpBasic("username", "password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"numeroCartao\":\"" + numero
            + "\",\"senha\":\"" + senha + "\"}"));
  }

  private ResultActions consultar(String numero) throws Exception {
    return mvc.perform(get("/cartoes/{numero}", numero)
        .with(httpBasic("username", "password")));
  }
}