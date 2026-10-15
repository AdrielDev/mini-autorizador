package br.com.miniautorizador.cartao.repository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.miniautorizador.cartao.model.Cartao;

@Repository 
public class JdbcCartaoRepository implements CartaoRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcCartaoRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbc = jdbcTemplate;
  }

  @Override
  public boolean inserir(Cartao cartao) {
    try {
      jdbc.update("""
          INSERT INTO cartoes (numero_cartao, senha_hash, saldo)
          VALUES (:numero_cartao, :senhaHash, :saldo)
          """, Map.of(
              "numero_cartao", cartao.numero(),
              "senhaHash", cartao.senhaHash(),
              "saldo", cartao.saldo()
          ));
          return true;
    } catch (DuplicateKeyException duplicate) {
      // A única chave única da tabela é numero_cartao; falhas distintas continuam como erros técnicos.
      return false;
    }
  }

  @Override
  public Optional<Cartao> buscar(String numero) {
    var cartoes = jdbc.query("""
          SELECT numero_cartao, senha_hash, saldo
          FROM cartoes WHERE numero_cartao = :numero_cartao
          """, Map.of("numero_cartao", numero), (rs, rowNum) -> new Cartao(
              rs.getString("numero_cartao"),
              rs.getString("senha_hash"),
              rs.getBigDecimal("saldo")
          ));
    return cartoes.stream().findFirst();
  }

  @Override
  public int debitarSeHouverSaldo(String numero, BigDecimal valor) {
    return jdbc.update("""
        UPDATE cartoes SET saldo = saldo - :valor
        WHERE numero_cartao = :numero AND saldo >= :valor
        """, Map.of(
            "numero", numero,
            "valor", valor
        ));
  }

}
