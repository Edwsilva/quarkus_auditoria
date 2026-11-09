package br.com.auditoria.parcelamento.service;

import br.com.auditoria.parcelamento.dto.PedidoParcelamentoRequest;
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
    static final String EVENTO_ERRO_CRIACAO  = "PEDIDO_PARCELAMENTO_ERRO";

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

        long nanosEpoch = System.currentTimeMillis() * 1_000_000L;

        JsonObject payload = new JsonObject()
                .put("streams", new JsonArray().add(new JsonObject()
                        .put("stream", new JsonObject()
                                .put("job", job)
                                .put("app", app)
                                .put("evento", EVENTO_PEDIDO_CRIADO))
                        .put("values", new JsonArray()
                                .add(new JsonArray().add(Long.toString(nanosEpoch)).add(linha.encode())))));

        client.postAbs(url + "/loki/api/v1/push")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .onSuccess(res -> {
                    if (res.statusCode() >= 300) {
                        LOG.warnf("Loki respondeu %d ao push de auditoria do pedido %d", res.statusCode(), pedido.id);
                    }
                })
                .onFailure(t -> LOG.warnf(t, "Falha ao enviar auditoria do pedido %d ao Loki (%s)", pedido.id, url));
    }

    /**
     * Envia ao Loki o registro de um erro (4xx ou 5xx) ocorrido durante a tentativa de criação
     * de um pedido de parcelamento. Inclui o request recebido, o HTTP status e a mensagem de erro.
     *
     * @param request  DTO de entrada recebido (pode ser parcialmente inválido)
     * @param usuario  valor do header X-Usuario
     * @param status   código HTTP do erro (ex: 400, 422, 500)
     * @param motivo   mensagem descritiva do erro
     */
    public void erroNaCriacao(PedidoParcelamentoRequest request, String usuario, int status, String motivo) {
        if (!enabled) {
            return;
        }

        JsonObject linha = new JsonObject()
                .put("ts", Instant.now().toString())
                .put("evento", EVENTO_ERRO_CRIACAO)
                .put("httpStatus", status)
                .put("motivo", motivo != null ? motivo : "sem detalhe")
                .put("usuario", usuario != null && !usuario.isBlank() ? usuario.trim() : "desconhecido");

        if (request != null) {
            linha.put("req_cpf",        request.cpf()  != null ? request.cpf()  : "")
                 .put("req_nome",       request.nome() != null ? request.nome() : "")
                 .put("req_tipo",       request.tipo() != null ? request.tipo().name() : "")
                 .put("req_valorTotal", request.valorTotal() != null ? request.valorTotal() : "")
                 .put("req_dataPedido", request.dataPedido() != null ? request.dataPedido().toString() : "");
        }

        long nanosEpoch = System.currentTimeMillis() * 1_000_000L;

        JsonObject payload = new JsonObject()
                .put("streams", new JsonArray().add(new JsonObject()
                        .put("stream", new JsonObject()
                                .put("job", job)
                                .put("app", app)
                                .put("evento", EVENTO_ERRO_CRIACAO)
                                .put("httpStatus", String.valueOf(status)))
                        .put("values", new JsonArray()
                                .add(new JsonArray().add(Long.toString(nanosEpoch)).add(linha.encode())))));

        client.postAbs(url + "/loki/api/v1/push")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .onSuccess(res -> {
                    if (res.statusCode() >= 300) {
                        LOG.warnf("Loki respondeu %d ao push de erro de criacao (status %d)", res.statusCode(), status);
                    }
                })
                .onFailure(t -> LOG.warnf(t, "Falha ao enviar log de erro de criacao ao Loki (%s)", url));
    }
}
