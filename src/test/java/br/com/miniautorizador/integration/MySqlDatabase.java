package br.com.miniautorizador.integration;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;

final class MySqlDatabase {

  record Connection(String url, String username, String password) {
  }

  private static class Holder {
    static final Connection CONNECTION = iniciar();
  }

  private MySqlDatabase() {
  }

  static Connection connection() {
    return Holder.CONNECTION;
  }

  static void configurar(DynamicPropertyRegistry registry) {
    var connection = connection();
    registry.add("spring.datasource.url", connection::url);
    registry.add("spring.datasource.username", connection::username);
    registry.add("spring.datasource.password", connection::password);
  }

  private static Connection iniciar() {
    return Optional.ofNullable(System.getProperty("it.jdbc.url"))
        .map(MySqlDatabase::conexaoExterna)
        .orElseGet(MySqlDatabase::iniciarContainer);
  }

  private static Connection conexaoExterna(String url) {
    String urlValidada = Optional.of(url)
        .filter(value -> value.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?"))
        .orElseThrow(() -> new IllegalArgumentException(
            "Integração externa exige um banco dedicado cujo nome termine em _test"));

    return new Connection(
        urlValidada,
        System.getProperty("it.jdbc.username", "root"),
        System.getProperty("it.jdbc.password", ""));
  }

  private static Connection iniciarContainer() {
    var mysql = new MySQLContainer("mysql:5.7.44")
        .withDatabaseName("miniautorizador_test")
        .withUsername("test")
        .withPassword("test");

    mysql.start();

    String url = mysql.getJdbcUrl();
    String separator = url.contains("?") ? "&" : "?";

    return new Connection(
        url + separator + "sslMode=DISABLED&serverTimezone=UTC",
        mysql.getUsername(),
        mysql.getPassword());
  }
}