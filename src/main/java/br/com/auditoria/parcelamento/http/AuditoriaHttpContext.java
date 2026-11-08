package br.com.auditoria.parcelamento.http;

import jakarta.enterprise.context.RequestScoped;

/**
 * Dados da requisição em andamento, compartilhados entre o request filter
 * e o response filter (escopo de request do CDI).
 */
@RequestScoped
public class AuditoriaHttpContext {

    public String traceId;
    public long inicioNanos;
    public String corpoRequest;
    public boolean ativo;
}
