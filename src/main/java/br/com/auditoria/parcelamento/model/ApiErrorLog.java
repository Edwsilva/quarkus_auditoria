package br.com.auditoria.parcelamento.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Erros de chamada da API (status >= audit.http.error-min-status),
 * persistidos para investigação estruturada (join com pedidos, filtros por período).
 */
@Entity
@Table(name = "api_error_log", indexes = {
        @Index(name = "idx_api_error_trace", columnList = "traceId"),
        @Index(name = "idx_api_error_criado", columnList = "criadoEm")
})
public class ApiErrorLog extends PanacheEntity {

    @Column(nullable = false, length = 36)
    public String traceId;

    @Column(nullable = false, length = 10)
    public String metodo;

    @Column(nullable = false, length = 255)
    public String path;

    @Column(nullable = false)
    public int status;

    @Column(nullable = false)
    public long duracaoMs;

    @Column(columnDefinition = "text")
    public String corpoRequest;

    @Column(columnDefinition = "text")
    public String corpoResponse;

    @Column(nullable = false, updatable = false)
    public Instant criadoEm;
}
