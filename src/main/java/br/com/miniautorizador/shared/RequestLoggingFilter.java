package br.com.miniautorizador.shared;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {

    String previousId = MDC.get("requestId");
    String requestId = UUID.randomUUID().toString();
    long started = System.nanoTime();
    Finalizacao finalizacao = Finalizacao.FALHOU;

    MDC.put("requestId", requestId);

    try {
      response.setHeader("X-Request-ID", requestId);
      filterChain.doFilter(request, response);
      finalizacao = Finalizacao.CONCLUIDA;
    } finally {
      try {
        Object matchedRoute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = matchedRoute == null ? "unmapped" : matchedRoute.toString();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        finalizacao.registrar(request.getMethod(), route, response, durationMs);
      } finally {
        Optional.ofNullable(previousId)
            .ifPresentOrElse(
                id -> MDC.put("requestId", id),
                () -> MDC.remove("requestId"));
      }
    }
  }

  private enum Finalizacao {
    CONCLUIDA {
      @Override
      void registrar(
          String method,
          String route,
          HttpServletResponse response,
          long durationMs) {

        LOGGER.info(
            "http_request_completed method={} route={} status={} durationMs={}",
            method, route, response.getStatus(), durationMs);
      }
    },
    FALHOU {
      @Override
      void registrar(
          String method,
          String route,
          HttpServletResponse response,
          long durationMs) {

        LOGGER.error(
            "http_request_failed method={} route={} durationMs={}",
            method, route, durationMs);
      }
    };

    abstract void registrar(
        String method,
        String route,
        HttpServletResponse response,
        long durationMs);
  }
}