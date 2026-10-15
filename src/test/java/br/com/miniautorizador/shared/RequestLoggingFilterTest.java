package br.com.miniautorizador.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

  private final RequestLoggingFilter filter = new RequestLoggingFilter();

  @AfterEach
  void limparMdc() {
    MDC.remove("requestId");
  }

  @Test
  void geraIdentificadorNoServidorELimpaContexto() throws Exception {
    var request = new MockHttpServletRequest("GET", "/cartoes");
    var response = new MockHttpServletResponse();
    request.addHeader("X-Request-ID", "identificador-do-cliente");

    filter.doFilter(request, response, (req, res) -> {
      assertThat(MDC.get("requestId"))
          .isEqualTo(response.getHeader("X-Request-ID"));
    });

    String requestId = response.getHeader("X-Request-ID");

    assertThat(requestId).isNotBlank();
    assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
    assertThat(requestId).isNotEqualTo("identificador-do-cliente");
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void preservaContextoAnteriorEPropagaFalha(CapturedOutput output) {
    var request = new MockHttpServletRequest("GET", "/cartoes");
    var response = new MockHttpServletResponse();
    var failure = new ServletException("conteudo-sensivel");

    MDC.put("requestId", "contexto-anterior");

    assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
      throw failure;
    }))
        .isSameAs(failure);

    assertThat(MDC.get("requestId")).isEqualTo("contexto-anterior");
    assertThat(output.getAll())
        .contains("http_request_failed")
        .doesNotContain("status=200", "conteudo-sensivel");
  }

  @Test
  void registraRotaEStatusSemDadosSensiveis(CapturedOutput output)
      throws Exception {

    String numero = "6549873025634501";
    var request = new MockHttpServletRequest("POST", "/cartoes/" + numero);
    var response = new MockHttpServletResponse();

    request.setAttribute(
        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
        "/cartoes/{numeroCartao}");
    request.setQueryString("senha=senha-sensivel");
    request.addHeader("Authorization", "Basic credencial-sensivel");
    request.setContent(
        "{\"senha\":\"senha-sensivel\",\"senhaHash\":\"hash-sensivel\"}"
            .getBytes(StandardCharsets.UTF_8));

    filter.doFilter(request, response, (req, res) -> response.setStatus(422));

    assertThat(output.getAll())
        .contains(
            "http_request_completed",
            "method=POST",
            "route=/cartoes/{numeroCartao}",
            "status=422",
            "durationMs=")
        .doesNotContain(
            numero,
            "senha-sensivel",
            "hash-sensivel",
            "credencial-sensivel");
  }
}