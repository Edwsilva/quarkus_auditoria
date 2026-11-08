package br.com.auditoria.parcelamento.service;

import br.com.auditoria.parcelamento.http.HttpAuditEvent;
import br.com.auditoria.parcelamento.model.PedidoParcelamento;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Envia eventos de auditoria estruturados ao Loki via push HTTP assíncrono.
 * O Postgres continua sendo a fonte da verdade; o Loki é para consulta/observabilidade dos eventos.
 */
@ApplicationScoped
@RegisterForReflection
public class AuditoriaLokiService {

    private static final Logger LOG = Logger.getLogger(AuditoriaLokiService.class);

    static final String EVENTO_PEDIDO_CRIADO = "PEDIDO_PARCELAMENTO_CRIADO";
    static final String EVENTO_HTTP_REQUEST = "HTTP_REQUEST";

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "audit.loki.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "audit.loki.url", defaultValue = "http://localhost:3100")
    String url;

    @ConfigProperty(name = "audit.loki.job", defaultValue = "auditoria")
    String job;

    @ConfigProperty(name = "audit.loki.app", defaultValue = "pedido-parcelamento")
    String app;

    private WebClient client;

    @PostConstruct
    void inicializar() {
        this.client = WebClient.create(vertx);
    }

    public void pedidoCriado(PedidoParcelamento pedido) {
        if (!enabled) {
            return;
        }

        JsonObject linha = new JsonObject()
                .put("ts", Instant.now().toString())
                .put("evento", EVENTO_PEDIDO_CRIADO)
                .put("id", pedido.id)
                .put("cpf", pedido.cpf)
                .put("nome", pedido.nome)
                .put("tipo", pedido.tipo.name())
                .put("dataPedido", pedido.dataPedido.toString())
                .put("usuario", pedido.usuario);

        if (pedido.valorTotal != null) {
            linha.put("valorTotal", pedido.valorTotal);
        }

        pushLoki(job, EVENTO_PEDIDO_CRIADO, null, linha,
                s -> LOG.warnf("Loki respondeu %d ao push de auditoria do pedido %d", s, pedido.id),
                t -> LOG.warnf(t, "Falha ao enviar auditoria do pedido %d ao Loki (%s)", pedido.id, url));
    }

    public void requisicaoHttp(String jobHttp, HttpAuditEvent evento) {
        if (!enabled) {
            return;
        }

        JsonObject linha = new JsonObject()
                .put("ts", Instant.now().toString())
                .put("evento", EVENTO_HTTP_REQUEST)
                .put("traceId", evento.traceId())
                .put("metodo", evento.metodo())
                .put("path", evento.path())
                .put("status", evento.status())
                .put("duracaoMs", evento.duracaoMs());

        if (evento.usuario() != null && !evento.usuario().isBlank()) {
            linha.put("usuario", evento.usuario());
        }
        if (evento.corpoRequest() != null) {
            linha.put("requestBody", evento.corpoRequest());
        }
        if (evento.corpoResponse() != null) {
            linha.put("responseBody", evento.corpoResponse());
        }

        pushLoki(jobHttp, EVENTO_HTTP_REQUEST, evento.statusClass(), linha,
                s -> LOG.warnf("Loki respondeu %d ao push de HTTP %s %s traceId=%s",
                        s, evento.metodo(), evento.path(), evento.traceId()),
                t -> LOG.warnf(t, "Falha ao enviar auditoria HTTP traceId=%s ao Loki (%s)",
                        evento.traceId(), url));
    }

    private void pushLoki(String jobLabel, String evento, String statusClass, JsonObject linha,
           java.util.function.IntConsumer aoResponderComErro,
            io.vertx.core.Handler<Throwable> aoFalhar) {

        JsonObject stream = new JsonObject()
                .put("job", jobLabel)
                .put("app", app)
                .put("evento", evento);
        if (statusClass != null) {
            stream.put("status_class", statusClass);
        }

        long nanosEpoch = System.currentTimeMillis() * 1_000_000L;

        JsonObject payload = new JsonObject()
                .put("streams", new JsonArray().add(new JsonObject()
                        .put("stream", stream)
                        .put("values", new JsonArray()
                                .add(new JsonArray().add(Long.toString(nanosEpoch)).add(linha.encode())))));

        client.postAbs(url + "/loki/api/v1/push")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .onSuccess(res -> {
                    if (res.statusCode() >= 300) {
                        aoResponderComErro.accept(res.statusCode());
                    }
                })
                .onFailure(aoFalhar);
    }
}
