package br.com.miniautorizador.integration;

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
    String external = System.getProperty("it.jdbc.url");
    if (external != null) {
      if (!external.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")) {
        throw new IllegalArgumentException("Integração externa exige um banco dedicado cujo nome termine em _test");
      }
      return new Connection(external, System.getProperty("it.jdbc.username", "root"),
          System.getProperty("it.jdbc.password", ""));
    }
    var mysql = new MySQLContainer("mysql:5.7.44")
        .withDatabaseName("miniautorizador_test")
        .withUsername("test")
        .withPassword("test");
    mysql.start();
    String separator = mysql.getJdbcUrl().contains("?") ? "&" : "?";
    return new Connection(mysql.getJdbcUrl() + separator + "sslMode=DISABLED&serverTimezone=UTC",
        mysql.getUsername(), mysql.getPassword());
  }
}
