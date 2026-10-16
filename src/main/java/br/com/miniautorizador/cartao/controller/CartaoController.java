package br.com.miniautorizador.cartao.controller;

import br.com.miniautorizador.cartao.dto.CartaoResponse;
import br.com.miniautorizador.cartao.dto.CriarCartaoRequest;
import br.com.miniautorizador.cartao.service.CartaoService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@RestController
@RequestMapping(path = "/cartoes", produces = MediaType.APPLICATION_JSON_VALUE)
public class CartaoController {
  private final CartaoService service;

  public CartaoController(CartaoService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<CartaoResponse> criar(
      @Valid @RequestBody CriarCartaoRequest request) {

    var resultado = service.criar(request.numeroCartao(), request.senha());

    HttpStatus status = switch (resultado) {
      case CRIADO -> HttpStatus.CREATED;
      case DUPLICADO -> HttpStatus.UNPROCESSABLE_CONTENT;
    };

    return ResponseEntity.status(status)
        .body(new CartaoResponse(request.numeroCartao(), request.senha()));
  }

  @GetMapping(value = "/{numeroCartao}")
  public ResponseEntity<BigDecimal> consultarSaldo(
      @PathVariable @Size(min = 16, max = 16) @Pattern(regexp = "[0-9]{16}") String numeroCartao) {

    return service.consultarSaldo(numeroCartao)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

}
