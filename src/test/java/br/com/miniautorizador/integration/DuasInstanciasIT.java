package br.com.miniautorizador.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DuasInstanciasIT {

  private static final HttpClient CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2))
      .build();

  private static final String BASIC = "Basic " + Base64.getEncoder()
      .encodeToString("username:password".getBytes(StandardCharsets.UTF_8));

  @Test
  @Timeout(value = 150, unit = TimeUnit.SECONDS)
  void doisProcessosJavaDisputamUmMesmoCartaoEPersistemAposReinicio()
      throws Exception {

    int portaA = portaLivre();
    int portaB = portaLivre();

    while (portaA == portaB) {
      portaB = portaLivre();
    }

    String numero = ApiIT.novoNumero();

    try (var b = iniciar(portaB, "b")) {
      try (var a = iniciar(portaA, "a")) {
        aguardar(a);
        aguardar(b);

        assertThat(a.processo().pid()).isNotEqualTo(b.processo().pid());

        assertThat(post(
            a.porta(),
            "/cartoes",
            "{\"numeroCartao\":\"" + numero + "\",\"senha\":\"1234\"}")
            .statusCode()).isEqualTo(201);

        assertThat(post(
            a.porta(), "/transacoes", compra(numero, "490"))
            .statusCode()).isEqualTo(201);

        disputarSaldo(a, b, numero);
      }

      try (var reinicio = iniciar(portaA, "a-reinicio")) {
        aguardar(reinicio);

        var saldo = get(reinicio.porta(), "/cartoes/" + numero);

        assertThat(saldo.statusCode()).isEqualTo(200);
        assertThat(saldo.body()).isEqualTo("0.00");
      }
    }
  }

  private static void disputarSaldo(
      Instancia a,
      Instancia b,
      String numero) throws Exception {

    var prontos = new CountDownLatch(2);
    var liberar = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    try {
      var primeiro = executor.submit(() -> {
        prontos.countDown();
        assertThat(liberar.await(10, TimeUnit.SECONDS))
            .as("Disparo nao liberado")
            .isTrue();

        return post(a.porta(), "/transacoes", compra(numero, "10"));
      });

      var segundo = executor.submit(() -> {
        prontos.countDown();
        assertThat(liberar.await(10, TimeUnit.SECONDS))
            .as("Disparo nao liberado")
            .isTrue();

        return post(b.porta(), "/transacoes", compra(numero, "10"));
      });

      assertThat(prontos.await(10, TimeUnit.SECONDS)).isTrue();
      liberar.countDown();

      var respostaA = primeiro.get(30, TimeUnit.SECONDS);
      var respostaB = segundo.get(30, TimeUnit.SECONDS);

      assertThat(new int[] {
          respostaA.statusCode(), respostaB.statusCode()
      }).containsExactlyInAnyOrder(201, 422);

      assertThat(new String[] {
          respostaA.body(), respostaB.body()
      }).containsExactlyInAnyOrder("OK", "SALDO_INSUFICIENTE");

      var saldoA = get(a.porta(), "/cartoes/" + numero);
      var saldoB = get(b.porta(), "/cartoes/" + numero);

      assertThat(saldoA.statusCode()).isEqualTo(200);
      assertThat(saldoB.statusCode()).isEqualTo(200);
      assertThat(saldoA.body()).isEqualTo("0.00");
      assertThat(saldoB.body()).isEqualTo("0.00");
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static String compra(String numero, String valor) {
    return "{\"numeroCartao\":\"" + numero
        + "\",\"senhaCartao\":\"1234\",\"valor\":" + valor + "}";
  }

  private static HttpResponse<String> post(
      int porta,
      String rota,
      String json) throws IOException, InterruptedException {

    var request = requisicao(porta, rota, Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(int porta, String rota)
      throws IOException, InterruptedException {

    var request = requisicao(porta, rota, Duration.ofSeconds(5))
        .GET()
        .build();

    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder requisicao(
      int porta,
      String rota,
      Duration timeout) {

    return HttpRequest.newBuilder(
        URI.create("http://localhost:" + porta + rota))
        .timeout(timeout)
        .header("Authorization", BASIC);
  }

  private static Instancia iniciar(int porta, String nome) throws IOException {
    var connection = MySqlDatabase.connection();

    String executavel = System.getProperty("os.name")
        .toLowerCase()
        .contains("win") ? "java.exe" : "java";

    var jar = Path.of("target", "mini-autorizador-0.0.1-SNAPSHOT.jar");

    var processo = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", executavel).toString(),
        "-Xmx192m",
        "-jar",
        jar.toString(),
        "--server.port=" + porta);

    processo.environment().put("SPRING_DATASOURCE_URL", connection.url());
    processo.environment().put("SPRING_DATASOURCE_USERNAME", connection.username());
    processo.environment().put("SPRING_DATASOURCE_PASSWORD", connection.password());

    processo.redirectErrorStream(true)
        .redirectOutput(Path.of("target", "instance-" + nome + ".log").toFile());

    return new Instancia(processo.start(), porta);
  }

  private static int portaLivre() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void aguardar(Instancia instancia) {
    await()
        .alias("Instancia na porta " + instancia.porta())
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(45))
        .failFast(
            "Instancia encerrou; consultar target/instance-*.log",
            () -> !instancia.processo().isAlive())
        .ignoreExceptionsInstanceOf(IOException.class)
        .untilAsserted(() -> assertThat(get(
            instancia.porta(), "/cartoes/0000000000000000")
            .statusCode()).isIn(200, 404));
  }

  private record Instancia(Process processo, int porta) implements AutoCloseable {

    @Override
    public void close() throws InterruptedException {
      processo.destroy();

      try {
        try {
          processo.onExit().get(5, TimeUnit.SECONDS);
        } catch (TimeoutException prazoEncerrado) {
          processo.destroyForcibly();
          processo.onExit().get(5, TimeUnit.SECONDS);
        }
      } catch (InterruptedException interrupted) {
        processo.destroyForcibly();
        Thread.currentThread().interrupt();
        throw interrupted;
      } catch (ExecutionException | TimeoutException failure) {
        processo.destroyForcibly();
        throw new IllegalStateException(
            "Falha ao encerrar instancia na porta " + porta,
            failure);
      }
    }
  }
}