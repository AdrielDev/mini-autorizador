package br.com.miniautorizador.cartao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CriarCartaoRequest(
    @NotBlank @Size(min = 16, max = 16) @Pattern(regexp = "[0-9]{16}") String numeroCartao,
    @NotBlank @Size(max = 128) String senha) {

  @Override
  public String toString() {
    return "CriarCartaoRequest[dados omitidos]";
  }
}
