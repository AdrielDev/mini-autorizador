package br.com.miniautorizador;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.miniautorizador.cartao.controller.CartaoController;
import br.com.miniautorizador.cartao.service.CartaoService;
import br.com.miniautorizador.cartao.service.ResultadoCriacao;
import br.com.miniautorizador.config.SecurityConfig;
import br.com.miniautorizador.shared.ApiExceptionHandler;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(CartaoController.class)
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class ApiControllerTest {

  private static final String NUMERO = "0000000000000001";
  private static final String CADASTRO = "{\"numeroCartao\":\"" + NUMERO + "\",\"senha\":\"1234\"}";

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private CartaoService cartoes;

  @ParameterizedTest
  @EnumSource(ResultadoCriacao.class)
  void cadastroRetornaSomenteOsDadosDaTentativa(
      ResultadoCriacao resultado) throws Exception {

    when(cartoes.criar(NUMERO, "1234")).thenReturn(resultado);

    mvc.perform(cadastro(CADASTRO))
        .andExpect(status().is(
            resultado == ResultadoCriacao.CRIADO ? 201 : 422))
        .andExpect(content().contentTypeCompatibleWith(
            MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.numeroCartao").value(NUMERO))
        .andExpect(jsonPath("$.senha").value("1234"));
  }

  @Test
  void saldoEhNumeroPuro() throws Exception {
    when(cartoes.consultarSaldo(NUMERO))
        .thenReturn(Optional.of(new BigDecimal("500.00")));

    mvc.perform(get("/cartoes/{numero}", NUMERO)
        .with(httpBasic("username", "password")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(
            MediaType.APPLICATION_JSON))
        .andExpect(content().string("500.00"));
  }

  @Test
  void cartaoInexistenteRetorna404SemCorpo() throws Exception {
    when(cartoes.consultarSaldo(NUMERO)).thenReturn(Optional.empty());

    mvc.perform(get("/cartoes/{numero}", NUMERO)
        .with(httpBasic("username", "password")))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  void exigeAutenticacaoNaCriacaoENaConsulta() throws Exception {
    mvc.perform(post("/cartoes")
        .contentType(MediaType.APPLICATION_JSON)
        .content(CADASTRO))
        .andExpect(status().isUnauthorized());

    mvc.perform(get("/cartoes/{numero}", NUMERO))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(cartoes);
  }

  @ParameterizedTest
  @CsvSource({ "username,errada", "outro,password" })
  void rejeitaCredenciaisIncorretas(
      String usuario, String senha) throws Exception {

    mvc.perform(post("/cartoes")
        .with(httpBasic(usuario, senha))
        .contentType(MediaType.APPLICATION_JSON)
        .content(CADASTRO))
        .andExpect(status().isUnauthorized());

    mvc.perform(get("/cartoes/{numero}", NUMERO)
        .with(httpBasic(usuario, senha)))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(cartoes);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}",
      "{",
      "",
      "{\"numeroCartao\":null,\"senha\":\"1234\"}",
      "{\"numeroCartao\":\"123\",\"senha\":\"1234\"}",
      "{\"numeroCartao\":\"00000000000000001\",\"senha\":\"1234\"}",
      "{\"numeroCartao\":\"000000000000000 1\",\"senha\":\"1234\"}",
      "{\"numeroCartao\":\"0000000000000001\",\"senha\":null}",
      "{\"numeroCartao\":\"0000000000000001\",\"senha\":\"\"}",
      "{\"numeroCartao\":\"0000000000000001\",\"senha\":\"   \"}"
  })
  void entradaInvalidaNaoChegaAoServico(String json) throws Exception {
    mvc.perform(cadastro(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value(
            "REQUISICAO_INVALIDA"));

    verifyNoInteractions(cartoes);
  }

  @Test
  void rejeitaSenhaAcimaDoLimite() throws Exception {
    var json = "{\"numeroCartao\":\"" + NUMERO
        + "\",\"senha\":\"" + "x".repeat(129) + "\"}";

    mvc.perform(cadastro(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value(
            "REQUISICAO_INVALIDA"));

    verifyNoInteractions(cartoes);
  }

  @Test
  void autenticacaoPrecedeValidacaoDaEntrada() throws Exception {
    mvc.perform(post("/cartoes")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(cartoes);
  }

  @Test
  void identificadorMalformadoNaConsultaRetorna400() throws Exception {
    mvc.perform(get("/cartoes/123")
        .with(httpBasic("username", "password")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value(
            "REQUISICAO_INVALIDA"));

    verifyNoInteractions(cartoes);
  }

  @Test
  void ignoraCamposExtrasSemIncluiLosNaResposta() throws Exception {
    when(cartoes.criar(NUMERO, "1234"))
        .thenReturn(ResultadoCriacao.CRIADO);

    var json = "{\"numeroCartao\":\"" + NUMERO
        + "\",\"senha\":\"1234\",\"saldo\":9999}";

    mvc.perform(cadastro(json))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.numeroCartao").value(NUMERO))
        .andExpect(jsonPath("$.senha").value("1234"));
  }

  @Test
  void falhaTecnicaNaoViraDuplicidadeNemExpoeDetalhes()
      throws Exception {

    when(cartoes.criar(anyString(), anyString()))
        .thenThrow(new DataAccessResourceFailureException(
            "detalhe privado"));

    mvc.perform(cadastro(CADASTRO))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.codigo").value(
            "ERRO_PERSISTENCIA"))
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(content().string(
            not(containsString("detalhe privado"))));
  }

  private MockHttpServletRequestBuilder cadastro(String json) {
    return post("/cartoes")
        .with(httpBasic("username", "password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json);
  }
}