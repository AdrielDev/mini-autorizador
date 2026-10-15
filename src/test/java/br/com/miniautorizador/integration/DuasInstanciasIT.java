package br.com.miniautorizador.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DuasInstanciasIT {
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private static final String BASIC = "Basic " + Base64.getEncoder()
      .encodeToString("username:password".getBytes(StandardCharsets.UTF_8));

  @Test
  @Timeout(value = 150, unit = TimeUnit.SECONDS)
  void doisProcessosJavaDisputamUmMesmoCartaoEPersistemAposReinicio() throws Exception {
    int portaA = portaLivre(), portaB = portaLivre();
    while (portaA == portaB)
      portaB = portaLivre();
    Process a = null, b = null;
    var executor = Executors.newFixedThreadPool(2);
    try {
      a = iniciar(portaA, "a");
      b = iniciar(portaB, "b");
      aguardar(a, portaA);
      aguardar(b, portaB);
      String numero = ApiIT.novoNumero();
      assertThat(post(portaA, "/cartoes", "{\"numeroCartao\":\"" + numero + "\",\"senha\":\"1234\"}").statusCode())
          .isEqualTo(201);
      assertThat(post(portaA, "/transacoes", compra(numero, "490")).statusCode()).isEqualTo(201);

      var prontos = new CountDownLatch(2);
      var liberar = new CountDownLatch(1);
      int destinoB = portaB;
      var primeiro = executor.submit(() -> {
        prontos.countDown();
        if (!liberar.await(10, TimeUnit.SECONDS))
          throw new IllegalStateException("Disparo não liberado");
        return post(portaA, "/transacoes", compra(numero, "10"));
      });
      var segundo = executor.submit(() -> {
        prontos.countDown();
        if (!liberar.await(10, TimeUnit.SECONDS))
          throw new IllegalStateException("Disparo não liberado");
        return post(destinoB, "/transacoes", compra(numero, "10"));
      });
      assertThat(prontos.await(10, TimeUnit.SECONDS)).isTrue();
      liberar.countDown();
      var respostaA = primeiro.get(30, TimeUnit.SECONDS);
      var respostaB = segundo.get(30, TimeUnit.SECONDS);
      assertThat(new int[] { respostaA.statusCode(), respostaB.statusCode() }).containsExactlyInAnyOrder(201, 422);
      assertThat(new String[] { respostaA.body(), respostaB.body() }).containsExactlyInAnyOrder("OK",
          "SALDO_INSUFICIENTE");
      var saldoA = get(portaA, "/cartoes/" + numero);
      var saldoB = get(portaB, "/cartoes/" + numero);
      assertThat(saldoA.statusCode()).isEqualTo(200);
      assertThat(saldoB.statusCode()).isEqualTo(200);
      assertThat(saldoA.body()).isEqualTo("0.00");
      assertThat(saldoB.body()).isEqualTo("0.00");

      encerrar(a);
      a = iniciar(portaA, "a-reinicio");
      aguardar(a, portaA);
      assertThat(get(portaA, "/cartoes/" + numero).body()).isEqualTo("0.00");
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
      encerrar(a);
      encerrar(b);
    }
  }

  private static String compra(String numero, String valor) {
    return "{\"numeroCartao\":\"" + numero + "\",\"senhaCartao\":\"1234\",\"valor\":" + valor + "}";
  }

  private static HttpResponse<String> post(int porta, String rota, String json)
      throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + rota))
        .timeout(Duration.ofSeconds(20)).header("Authorization", BASIC)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(int porta, String rota) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + rota))
        .timeout(Duration.ofSeconds(5)).header("Authorization", BASIC).GET().build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static Process iniciar(int porta, String nome) throws IOException {
    var connection = MySqlDatabase.connection();

    String executavel = System.getProperty("os.name")
        .toLowerCase()
        .contains("win") ? "java.exe" : "java";

    var jar = Path.of(
        "target",
        "mini-autorizador-0.0.1-SNAPSHOT.jar");

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
        .redirectOutput(
            Path.of("target", "instance-" + nome + ".log").toFile());

    return processo.start();
  }

  private static int portaLivre() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void aguardar(Process processo, int porta) throws Exception {
    long limite = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (System.nanoTime() < limite) {
      if (!processo.isAlive())
        throw new IllegalStateException("Instância encerrou; consultar target/instance-*.log");
      try {
        int status = get(porta, "/cartoes/0000000000000000").statusCode();
        if (status == 200 || status == 404)
          return;
      } catch (IOException aindaIniciando) {
        // Poll de readiness; não é utilizado para coordenar as compras concorrentes.
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("Instância não iniciou dentro do prazo");
  }

  private static void encerrar(Process processo) throws InterruptedException {
    if (processo != null && processo.isAlive()) {
      processo.destroy();
      if (!processo.waitFor(5, TimeUnit.SECONDS)) {
        processo.destroyForcibly();
        processo.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }
}
