package br.com.miniautorizador.transacao.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.miniautorizador.transacao.dto.TransacaoRequest;
import br.com.miniautorizador.transacao.service.AutorizacaoService;
import br.com.miniautorizador.transacao.service.ResultadoAutorizacao;

@RestController
@RequestMapping("/transacoes")
public class TransacaoController {

  private final AutorizacaoService service;

  public TransacaoController(AutorizacaoService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> autorizar(
      @Valid @RequestBody TransacaoRequest request) {

    var resultado = service.autorizar(
        request.numeroCartao(),
        request.senhaCartao(),
        request.valor());

    var status = resultado == ResultadoAutorizacao.APROVADA
        ? HttpStatus.CREATED
        : HttpStatus.UNPROCESSABLE_CONTENT;

    return ResponseEntity.status(status)
        .body(resultado.resposta());
  }
}