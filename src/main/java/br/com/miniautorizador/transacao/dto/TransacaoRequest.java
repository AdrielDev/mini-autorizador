package br.com.miniautorizador.transacao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransacaoRequest(
    @NotBlank @Size(min = 16, max = 16) @Pattern(regexp = "(?U)\\S+") String numeroCartao,

    @NotBlank @Size(max = 128) String senhaCartao,

    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99999999999999999.99") BigDecimal valor) {

  @Override
  public String toString() {
    return "AutorizacaoRequest[dados omitidos]";
  }
}