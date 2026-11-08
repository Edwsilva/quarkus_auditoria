package br.com.auditoria.parcelamento.service;

import br.com.auditoria.parcelamento.http.HttpAuditEvent;
import br.com.auditoria.parcelamento.model.ApiErrorLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;

@ApplicationScoped
public class ApiErrorAuditService {

    @Transactional
    public void registrar(HttpAuditEvent evento) {
        ApiErrorLog log = new ApiErrorLog();
        log.traceId = evento.traceId();
        log.metodo = evento.metodo();
        log.path = evento.path();
        log.status = evento.status();
        log.duracaoMs = evento.duracaoMs();
        log.corpoRequest = evento.corpoRequest();
        log.corpoResponse = evento.corpoResponse();
        log.criadoEm = Instant.now();
        log.persist();
    }
}
