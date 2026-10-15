package br.com.miniautorizador.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import br.com.miniautorizador.cartao.model.Cartao;
import br.com.miniautorizador.cartao.repository.CartaoRepository;
import br.com.miniautorizador.cartao.repository.JdbcCartaoRepository;
import br.com.miniautorizador.cartao.service.CartaoService;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RollbackIT.FalhaAposDebito.class)
public class RollbackIT {
  @Autowired
  MockMvc mvc;
  @Autowired
  CartaoService cartoes;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    MySqlDatabase.configurar(registry);
  }

  @Test
  void falhaAposSqlRealProvocacaoRollbackENaoRespondeAprovacao() throws Exception {
    String numero = ApiIT.novoNumero();
    cartoes.criar(numero, "1234");
    mvc.perform(post("/transacoes").with(httpBasic("username", "password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"numeroCartao\":\"" + numero + "\",\"senhaCartao\":\"1234\",\"valor\":10}"))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("ERRO_PERSISTENCIA"));
    assertThat(cartoes.consultarSaldo(numero).orElseThrow()).isEqualByComparingTo("500.00");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FalhaAposDebito {
    @Bean
    @Primary
    CartaoRepository repositoryComFalha(JdbcCartaoRepository real) {
      return new CartaoRepository() {
        public boolean inserir(Cartao cartao) {
          return real.inserir(cartao);
        }

        public Optional<Cartao> buscar(String numero) {
          return real.buscar(numero);
        }

        @Override
        public int debitarSeHouverSaldo(String numero, BigDecimal valor) {
          int linhas = real.debitarSeHouverSaldo(numero, valor);

          assertThat(linhas).isEqualTo(1);

          throw new DataAccessResourceFailureException(
              "Falha provocada após o UPDATE e antes do commit");
        }
      };
    }
  }
}
