package br.com.auditoria.parcelamento.http;

import br.com.auditoria.parcelamento.service.ApiErrorAuditService;
import br.com.auditoria.parcelamento.service.AuditoriaLokiService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Auditoria de request/response de cada chamada da API.
 *
 * - Request: gera (ou reaproveita) o X-Trace-Id e captura o corpo (com limite de tamanho).
 * - Response: mede duração, captura corpo quando houver erro, envia linha ao Loki
 *   (sempre) e persiste no Postgres (apenas status >= audit.http.error-min-status).
 *
 * O resource é anotado com @Blocking: a leitura síncrona do entityStream só é
 * segura fora do event loop (ver quarkusio/quarkus#17280).
 */
@Provider
public class AuditoriaHttpFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(AuditoriaHttpFilter.class);

    @ConfigProperty(name = "audit.http.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "audit.http.max-body-chars", defaultValue = "4096")
    int maxBodyChars;

    @ConfigProperty(name = "audit.http.log-success-response-body", defaultValue = "false")
    boolean logSuccessResponseBody;

    @ConfigProperty(name = "audit.http.persist-errors", defaultValue = "true")
    boolean persistErrors;

    @ConfigProperty(name = "audit.http.error-min-status", defaultValue = "500")
    int errorMinStatus;

    @ConfigProperty(name = "audit.http.job", defaultValue = "auditoria-http")
    String httpJob;

    @Inject
    AuditoriaHttpContext ctx;

    @Inject
    AuditoriaLokiService loki;

    @Inject
    ApiErrorAuditService errorAudit;

    @Inject
    ObjectMapper mapper;

    @Override
    public void filter(ContainerRequestContext request) {
        if (!enabled) {
            return;
        }

        String recebido = request.getHeaderString("X-Trace-Id");
        ctx.traceId = (recebido == null || recebido.isBlank())
                ? UUID.randomUUID().toString()
                : recebido.trim();
        ctx.inicioNanos = System.nanoTime();
        ctx.ativo = true;
        ctx.corpoRequest = null;

        if (request.hasEntity()) {
            try {
                InputStream stream = request.getEntityStream();
                byte[] bytes = stream.readAllBytes();
                request.setEntityStream(new ByteArrayInputStream(bytes));
                ctx.corpoRequest = truncar(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException e) {
                LOG.debugf(e, "Não foi possível ler o corpo de %s %s",
                        request.getMethod(), request.getUriInfo().getPath());
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!enabled || !ctx.ativo) {
            return;
        }

        long duracaoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ctx.inicioNanos);
        int status = response.getStatus();
        String statusClass = (status / 100) + "xx";
        String path = request.getUriInfo().getPath();

        String corpoResposta = null;
        if (status >= 400 || logSuccessResponseBody) {
            corpoResposta = truncar(serializar(response.getEntity()));
        }

        response.getHeaders().add("X-Trace-Id", ctx.traceId);

        HttpAuditEvent evento = new HttpAuditEvent(
                ctx.traceId,
                request.getMethod(),
                path,
                status,
                statusClass,
                duracaoMs,
                ctx.corpoRequest,
                corpoResposta,
                request.getHeaderString("X-Usuario"));

        loki.requisicaoHttp(httpJob, evento);

        if (persistErrors && status >= errorMinStatus) {
            try {
                errorAudit.registrar(evento);
            } catch (Exception e) {
                LOG.warnf(e, "Falha ao persistir erro de API traceId=%s status=%d", ctx.traceId, status);
            }
        }

        ctx.ativo = false;
    }

    private String serializar(Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            return String.valueOf(entity);
        }
    }

    private String truncar(String valor) {
        if (valor == null) {
            return null;
        }
        if (valor.length() <= maxBodyChars) {
            return valor;
        }
        return valor.substring(0, maxBodyChars) + "...[truncado]";
    }
}
