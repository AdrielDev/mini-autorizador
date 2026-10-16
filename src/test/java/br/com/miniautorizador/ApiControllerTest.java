package br.com.miniautorizador;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
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
import br.com.miniautorizador.transacao.controller.TransacaoController;
import br.com.miniautorizador.transacao.service.AutorizacaoService;
import br.com.miniautorizador.transacao.service.ResultadoAutorizacao;

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
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest({ CartaoController.class, TransacaoController.class })

@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class ApiControllerTest {
  private static final String NUMERO = "0000000000000001";
  private static final String CADASTRO = "{\"numeroCartao\":\"" + NUMERO + "\",\"senha\":\"1234\"}";
  private static final String COMPRA = "{\"numeroCartao\":\"" + NUMERO + "\",\"senhaCartao\":\"1234\",\"valor\":10}";

  @Autowired
  private MockMvc mvc;
  @MockitoBean
  private CartaoService cartoes;
  @MockitoBean
  private AutorizacaoService autorizacao;

  private MockHttpServletRequestBuilder requisicao(String rota) {
    return switch (rota) {
      case "saldo" -> get("/cartoes/" + NUMERO);
      case "cartao" -> post("/cartoes").contentType(MediaType.APPLICATION_JSON).content(CADASTRO);
      default -> post("/transacoes").contentType(MediaType.APPLICATION_JSON).content(COMPRA);
    };
  }

  @ParameterizedTest
  @ValueSource(strings = { "saldo", "cartao", "transacao" })
  void exigeBasicNasTresRotas(String rota) throws Exception {
    mvc.perform(requisicao(rota)).andExpect(status().isUnauthorized());
    verifyNoInteractions(cartoes, autorizacao);
  }

  @ParameterizedTest
  @CsvSource({ "saldo,username,errada", "cartao,username,errada", "transacao,username,errada",
      "saldo,outro,password", "cartao,outro,password", "transacao,outro,password" })
  void rejeitaCredenciaisReaisIncorretas(String rota, String usuario, String senha) throws Exception {
    mvc.perform(requisicao(rota).with(httpBasic(usuario, senha))).andExpect(status().isUnauthorized());
    verifyNoInteractions(cartoes, autorizacao);
  }

  @ParameterizedTest
  @EnumSource(ResultadoCriacao.class)
  void cadastroRetornaSomenteCamposDaTentativa(ResultadoCriacao resultado) throws Exception {
    when(cartoes.criar(NUMERO, "1234")).thenReturn(resultado);
    mvc.perform(requisicao("cartao").with(httpBasic("username", "password")))
        .andExpect(status().is(resultado == ResultadoCriacao.CRIADO ? 201 : 422))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.numeroCartao").value(NUMERO))
        .andExpect(jsonPath("$.senha").value("1234"));
  }

  @Test
  void saldoEhNumeroPuro() throws Exception {
    when(cartoes.consultarSaldo(NUMERO)).thenReturn(Optional.of(new BigDecimal("500.00")));
    mvc.perform(requisicao("saldo").with(httpBasic("username", "password")))
        .andExpect(status().isOk()).andExpect(content().string("500.00"));
  }

  @Test
  void inexistenteTem404Vazio() throws Exception {
    when(cartoes.consultarSaldo(NUMERO)).thenReturn(Optional.empty());
    mvc.perform(requisicao("saldo").with(httpBasic("username", "password")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @ParameterizedTest
  @EnumSource(ResultadoAutorizacao.class)
  void transacaoRespeitaStatusETextoPuro(ResultadoAutorizacao resultado) throws Exception {
    when(autorizacao.autorizar(anyString(), anyString(), any())).thenReturn(resultado);
    mvc.perform(requisicao("transacao").with(httpBasic("username", "password")))
        .andExpect(status().is(resultado == ResultadoAutorizacao.APROVADA ? 201 : 422))
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string(resultado.resposta()));
  }

  @ParameterizedTest
  @ValueSource(strings = { "{}", "{", "{\"numeroCartao\":\"123\",\"senha\":\"1234\"}",
      "{\"numeroCartao\":\"0000000000000001\",\"senha\":\"   \"}",
      "{\"numeroCartao\":\"000000000000000 1\",\"senha\":\"1234\"}" })
  void cadastroInvalidoNaoChegaAoServico(String json) throws Exception {
    mvc.perform(post("/cartoes").with(httpBasic("username", "password"))
        .contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));
    verifyNoInteractions(cartoes);
  }

  @ParameterizedTest
  @ValueSource(strings = { "null", "0", "-1", "100000000000000000.00" })
  void valoresInvalidosNaoChegamAoServico(String valor) throws Exception {
    String json = "{\"numeroCartao\":\"" + NUMERO + "\",\"senhaCartao\":\"1234\",\"valor\":" + valor + "}";
    mvc.perform(post("/transacoes").with(httpBasic("username", "password"))
        .contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(autorizacao);
  }

  @Test
  void autenticacaoPrecedeValidacaoDoJson() throws Exception {
    mvc.perform(post("/cartoes").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(cartoes);
  }

  @Test
  void identificadorInvalidoNaConsultaRetorna400() throws Exception {
    mvc.perform(get("/cartoes/123").with(httpBasic("username", "password")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(cartoes);
  }

  @Test
  void erroTecnicoNaoRevelaDetalhesNemViraDuplicidade() throws Exception {
    when(cartoes.criar(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("detalhe privado"));
    mvc.perform(requisicao("cartao").with(httpBasic("username", "password")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.codigo").value("ERRO_PERSISTENCIA"))
        .andExpect(jsonPath("$.*", hasSize(2)));
  }

  @Test
  void falhaDeCommitNaoViraOk() throws Exception {
    when(autorizacao.autorizar(anyString(), anyString(), any()))
        .thenThrow(new TransactionSystemException("commit falhou"));
    mvc.perform(requisicao("transacao").with(httpBasic("username", "password")))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("ERRO_PERSISTENCIA"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "123456789012345A",
      "123456789012345/",
      "123456789012345;"
  })
  void rejeitaNumeroNaoNumericoNaCriacaoENaCompra(String numero) throws Exception {
    mvc.perform(post("/cartoes")
        .with(httpBasic("username", "password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"numeroCartao\":\"" + numero + "\",\"senha\":\"1234\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));

    mvc.perform(post("/transacoes")
        .with(httpBasic("username", "password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"numeroCartao\":\"" + numero
            + "\",\"senhaCartao\":\"1234\",\"valor\":10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));

    verifyNoInteractions(cartoes, autorizacao);
  }

  @Test
  void consultaRejeitaNumeroNaoNumerico() throws Exception {
    mvc.perform(get("/cartoes/123456789012345A")
        .with(httpBasic("username", "password")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));

    verifyNoInteractions(cartoes, autorizacao);
  }
}
