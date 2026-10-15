package br.com.miniautorizador.transacao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import br.com.miniautorizador.cartao.model.Cartao;
import br.com.miniautorizador.cartao.repository.CartaoRepository;
import br.com.miniautorizador.shared.EntradaInvalidaException;

@ExtendWith(MockitoExtension.class)
class AutorizacaoServiceTest {
    private static final String NUMERO = "0000000000000001";
    @Mock CartaoRepository repository;
    @Mock PasswordEncoder encoder;
    private AutorizacaoService service;

    @BeforeEach
    void configurar() {
        service = new AutorizacaoService(repository, encoder);
    }

    private void cartaoComSenhaValida() {
        when(repository.buscar(NUMERO)).thenReturn(Optional.of(new Cartao(NUMERO, "hash", new BigDecimal("500.00"))));
        when(encoder.matches("1234", "hash")).thenReturn(true);
    }

    @Test
    void inexistenteNaoValidaSenhaNemDebita() {
        when(repository.buscar(NUMERO)).thenReturn(Optional.empty());
        assertThat(service.autorizar(NUMERO, "1234", BigDecimal.TEN)).isEqualTo(ResultadoAutorizacao.CARTAO_INEXISTENTE);
        verifyNoInteractions(encoder);
        verify(repository, never()).debitarSeHouverSaldo(anyString(), any());
    }

    @Test
    void senhaInvalidaTemPrioridadeSobreSaldoENaoTentaDebito() {
        when(repository.buscar(NUMERO)).thenReturn(Optional.of(new Cartao(NUMERO, "hash", BigDecimal.ZERO)));
        when(encoder.matches("errada", "hash")).thenReturn(false);
        assertThat(service.autorizar(NUMERO, "errada", BigDecimal.TEN)).isEqualTo(ResultadoAutorizacao.SENHA_INVALIDA);
        verify(repository, never()).debitarSeHouverSaldo(anyString(), any());
    }

    @Test
    void aprovaPelaContagemDoUpdateENormalizaZerosDecimais() {
        cartaoComSenhaValida();
        when(repository.debitarSeHouverSaldo(NUMERO, new BigDecimal("10.00"))).thenReturn(1);
        assertThat(service.autorizar(NUMERO, "1234", new BigDecimal("10.000"))).isEqualTo(ResultadoAutorizacao.APROVADA);
        verify(repository).debitarSeHouverSaldo(NUMERO, new BigDecimal("10.00"));
    }

    @Test
    void recusaPeloUpdateMesmoSeConsultaAnteriorMostravaSaldo() {
        cartaoComSenhaValida();
        when(repository.debitarSeHouverSaldo(NUMERO, new BigDecimal("10.00"))).thenReturn(0);
        assertThat(service.autorizar(NUMERO, "1234", BigDecimal.TEN)).isEqualTo(ResultadoAutorizacao.SALDO_INSUFICIENTE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-0.01", "10.001", "100000000000000000.00", "0.0000001"})
    void rejeitaDinheiroInvalidoAntesDoBanco(String valor) {
        BigDecimal entrada = valor == null ? null : new BigDecimal(valor);
        assertThatThrownBy(() -> service.autorizar(NUMERO, "1234", entrada)).isInstanceOf(EntradaInvalidaException.class);
        verifyNoInteractions(repository, encoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "10", "10.0", "10.00", "10.000", "99999999999999999.99"})
    void aceitaValoresExatamenteRepresentaveisEmCentavos(String valor) {
        var normalizado = ValorTransacao.normalizar(new BigDecimal(valor));
        assertThat(normalizado.scale()).isEqualTo(2);
        assertThat(normalizado).isEqualByComparingTo(valor);
    }

    @Test
    void erroDePersistenciaNaoViraSaldoInsuficiente() {
        cartaoComSenhaValida();
        when(repository.debitarSeHouverSaldo(anyString(), any())).thenThrow(new DataAccessResourceFailureException("falha"));
        assertThatThrownBy(() -> service.autorizar(NUMERO, "1234", BigDecimal.TEN))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void contagemImpossivelNaoViraAprovacao() {
        cartaoComSenhaValida();
        when(repository.debitarSeHouverSaldo(anyString(), any())).thenReturn(2);
        assertThatThrownBy(() -> service.autorizar(NUMERO, "1234", BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class);
    }
}
