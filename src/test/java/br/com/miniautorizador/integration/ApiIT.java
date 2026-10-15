package br.com.miniautorizador.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.miniautorizador.cartao.repository.CartaoRepository;
import br.com.miniautorizador.cartao.service.CartaoService;
import br.com.miniautorizador.cartao.service.ResultadoCriacao;
import br.com.miniautorizador.transacao.service.AutorizacaoService;
import br.com.miniautorizador.transacao.service.ResultadoAutorizacao;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
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
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis() * 100);
    @Autowired MockMvc mvc;
    @Autowired CartaoRepository repository;
    @Autowired CartaoService cartoes;
    @Autowired AutorizacaoService autorizacao;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlDatabase.configurar(registry);
    }

    static String novoNumero() {
        return String.format("%016d", SEQUENCE.incrementAndGet());
    }

    private ResultActions cadastrar(String numero, String senha) throws Exception {
        return mvc.perform(post("/cartoes").with(httpBasic("username", "password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + numero + "\",\"senha\":\"" + senha + "\"}"));
    }

    private ResultActions comprar(String numero, String senha, String valor) throws Exception {
        return mvc.perform(post("/transacoes").with(httpBasic("username", "password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + numero + "\",\"senhaCartao\":\"" + senha + "\",\"valor\":" + valor + "}"));
    }

    private ResultActions consultar(String numero) throws Exception {
        return mvc.perform(get("/cartoes/" + numero).with(httpBasic("username", "password")));
    }

    @Test
    void migraNoMySql57SemDefaultNoSaldo() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("5.7.");
        var coluna = jdbc.queryForMap("""
                SELECT CHARACTER_MAXIMUM_LENGTH AS tamanho FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'cartoes' AND column_name = 'numero_cartao'
                """);
        assertThat(((Number) coluna.get("tamanho")).intValue()).isEqualTo(16);
        assertThat(jdbc.queryForObject("""
                SELECT COLUMN_DEFAULT FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'cartoes' AND column_name = 'saldo'
                """, String.class)).isNull();
        var falha = catchThrowable(() -> jdbc.update(
                "INSERT INTO cartoes (numero_cartao, senha_hash) VALUES (?, ?)", novoNumero(), "hash"));
        assertThat(falha).isInstanceOf(DataAccessException.class);
        var causa = ((DataAccessException) falha).getMostSpecificCause();
        assertThat(causa).isInstanceOf(SQLException.class);
        assertThat(((SQLException) causa).getErrorCode()).isEqualTo(1364);
    }

    @Test
    void executaSequenciaDeAvaliacaoDaVr() throws Exception {
        String numero = novoNumero();
        cadastrar(numero, "1234").andExpect(status().isCreated()).andExpect(jsonPath("$.senha").value("1234"));
        consultar(numero).andExpect(status().isOk()).andExpect(content().string("500.00"));
        comprar(numero, "1234", "4.85").andExpect(status().isCreated()).andExpect(content().string("OK"));
        consultar(numero).andExpect(content().string("495.15"));
        comprar(numero, "1234", "495.15").andExpect(status().isCreated());
        consultar(numero).andExpect(content().string("0.00"));
        comprar(numero, "1234", "0.01").andExpect(status().is(422)).andExpect(content().string("SALDO_INSUFICIENTE"));
        comprar(numero, "errada", "0.01").andExpect(status().is(422)).andExpect(content().string("SENHA_INVALIDA"));
        comprar(novoNumero(), "1234", "0.01").andExpect(status().is(422)).andExpect(content().string("CARTAO_INEXISTENTE"));
        consultar(numero).andExpect(content().string("0.00"));
    }

    @Test
    void inexistenteRetorna404SemCorpo() throws Exception {
        consultar(novoNumero()).andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test
    void duplicidadePreservaHashESaldoMesmoAposCompra() throws Exception {
        String numero = novoNumero();
        cadastrar(numero, "original").andExpect(status().isCreated());
        comprar(numero, "original", "4.85").andExpect(status().isCreated());
        String hash = repository.buscar(numero).orElseThrow().senhaHash();
        cadastrar(numero, "nova").andExpect(status().is(422))
                .andExpect(jsonPath("$.senha").value("nova"));
        var persistido = repository.buscar(numero).orElseThrow();
        assertThat(persistido.saldo()).isEqualByComparingTo("495.15");
        assertThat(persistido.senhaHash()).isEqualTo(hash);
        assertThat(encoder.matches("original", persistido.senhaHash())).isTrue();
        assertThat(encoder.matches("nova", persistido.senhaHash())).isFalse();
        comprar(numero, "nova", "1").andExpect(status().is(422)).andExpect(content().string("SENHA_INVALIDA"));
        comprar(numero, "original", "1").andExpect(status().isCreated());
    }

    @Test
    void senhaRealTemSaltENaoEhPersistidaEmTextoPuro() throws Exception {
        String a = novoNumero(), b = novoNumero();
        cadastrar(a, " mesma senha ").andExpect(status().isCreated());
        cadastrar(b, " mesma senha ").andExpect(status().isCreated());
        var primeiro = repository.buscar(a).orElseThrow();
        var segundo = repository.buscar(b).orElseThrow();
        assertThat(primeiro.senhaHash()).isNotEqualTo(" mesma senha ").isNotEqualTo(segundo.senhaHash());
        assertThat(encoder.matches(" mesma senha ", primeiro.senhaHash())).isTrue();
        assertThat(encoder.matches("mesma senha", primeiro.senhaHash())).isFalse();
    }

    @Test
    void saldoInformadoNaEntradaEhIgnorado() throws Exception {
        String numero = novoNumero();
        mvc.perform(post("/cartoes").with(httpBasic("username", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numeroCartao\":\"" + numero + "\",\"senha\":\"1234\",\"saldo\":9999}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.saldo").doesNotExist());
        consultar(numero).andExpect(content().string("500.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "10.001", "100000000000000000"})
    void dinheiroInvalidoNaoAlteraSaldo(String valor) throws Exception {
        String numero = novoNumero();
        cadastrar(numero, "1234").andExpect(status().isCreated());
        comprar(numero, "1234", valor).andExpect(status().isBadRequest());
        consultar(numero).andExpect(content().string("500.00"));
    }

    @Test
    void aceitaZerosDecimaisSemPerderCentavos() throws Exception {
        String numero = novoNumero();
        cadastrar(numero, "1234").andExpect(status().isCreated());
        comprar(numero, "1234", "10.000").andExpect(status().isCreated());
        consultar(numero).andExpect(content().string("490.00"));
    }

    @Test
    void postRepetidoDebitaNovamenteConformeContratoSemIdempotencia() throws Exception {
        String numero = novoNumero();
        cadastrar(numero, "1234").andExpect(status().isCreated());
        comprar(numero, "1234", "10").andExpect(status().isCreated());
        comprar(numero, "1234", "10").andExpect(status().isCreated());
        consultar(numero).andExpect(content().string("480.00"));
    }

    @Test
    void duasComprasDisputamMesmoSaldoComTransacoesIndependentes() throws Exception {
        String numero = novoNumero();
        cartoes.criar(numero, "1234");
        assertThat(autorizacao.autorizar(numero, "1234", new BigDecimal("490"))).isEqualTo(ResultadoAutorizacao.APROVADA);
        var resultados = concorrer(2, () -> autorizacao.autorizar(numero, "1234", BigDecimal.TEN));
        assertThat(resultados).containsExactlyInAnyOrder(ResultadoAutorizacao.APROVADA, ResultadoAutorizacao.SALDO_INSUFICIENTE);
        assertThat(cartoes.consultarSaldo(numero).orElseThrow()).isEqualByComparingTo("0.00");
    }

    @Test
    void vinteComprasNaoGastamAlemDoDisponivel() throws Exception {
        String numero = novoNumero();
        cartoes.criar(numero, "1234");
        var resultados = concorrer(20, () -> autorizacao.autorizar(numero, "1234", new BigDecimal("50.00")));
        assertThat(resultados.stream().filter(r -> r == ResultadoAutorizacao.APROVADA).count()).isEqualTo(10);
        assertThat(resultados.stream().filter(r -> r == ResultadoAutorizacao.SALDO_INSUFICIENTE).count()).isEqualTo(10);
        assertThat(cartoes.consultarSaldo(numero).orElseThrow()).isEqualByComparingTo("0.00");
    }

    @Test
    void cadastrosConcorrentesGeramUmCriadoEUmDuplicado() throws Exception {
        String numero = novoNumero();
        var resultados = concorrer(2, () -> cartoes.criar(numero, "1234"));
        assertThat(resultados).containsExactlyInAnyOrder(ResultadoCriacao.CRIADO, ResultadoCriacao.DUPLICADO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cartoes WHERE numero_cartao = ?", Integer.class, numero)).isEqualTo(1);
    }

    private static <T> List<T> concorrer(int quantidade, Callable<T> trabalho) throws Exception {
        var executor = Executors.newFixedThreadPool(quantidade);
        var prontos = new CountDownLatch(quantidade);
        var iniciar = new CountDownLatch(1);
        var futures = new ArrayList<Future<T>>();
        try {
            for (int i = 0; i < quantidade; i++) {
                futures.add(executor.submit(() -> {
                    prontos.countDown();
                    if (!iniciar.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Disparo não liberado");
                    return trabalho.call();
                }));
            }
            assertThat(prontos.await(10, TimeUnit.SECONDS)).isTrue();
            iniciar.countDown();
            var resultados = new ArrayList<T>();
            for (var future : futures) resultados.add(future.get(45, TimeUnit.SECONDS));
            return resultados;
        } finally {
            iniciar.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
