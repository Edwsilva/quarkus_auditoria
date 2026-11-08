package br.com.auditoria.parcelamento.http;

/**
 * Evento de auditoria de uma chamada HTTP, compartilhado entre o filtro,
 * o push ao Loki e a persistência de erros no Postgres.
 */
public record HttpAuditEvent(
        String traceId,
        String metodo,
        String path,
        int status,
        String statusClass,
        long duracaoMs,
        String corpoRequest,
        String corpoResponse,
        String usuario) {
}
