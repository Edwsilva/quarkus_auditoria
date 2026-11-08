package br.com.auditoria.parcelamento.http;

import br.com.auditoria.parcelamento.model.ApiErrorLog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AuditoriaHttpFilterTest {

    @Test
    void deveRetornarTraceIdERegistrarErroNoPostgres() {
        long errosAntes = ApiErrorLog.count();

        // 1) Sucesso: traceId ecoado na resposta e NENHUM erro persistido
        Map<String, Object> valido = new HashMap<>();
        valido.put("cpf", "12345678900");
        valido.put("nome", "Auditor HTTP");
        valido.put("tipo", "PROMOCIONAL");
        valido.put("valorTotal", 100.00);

        String traceEsperado = "trace-teste-001";
        given()
                .contentType(ContentType.JSON)
                .header("X-Trace-Id", traceEsperado)
                .body(valido)
                .when().post("/api/pedidos-parcelamento")
                .then().statusCode(201)
                .header("X-Trace-Id", equalTo(traceEsperado));

        assertEquals(errosAntes, ApiErrorLog.count(), "sucesso não deve gerar linha em api_error_log");

        // 2) Erro 400 (limiar de teste = 400): persiste com traceId e corpo da requisição
        Map<String, Object> invalido = new HashMap<>();
        invalido.put("cpf", "123");
        invalido.put("nome", "");
        invalido.put("tipo", "PROMOCIONAL");

        given()
                .contentType(ContentType.JSON)
                .body(invalido)
                .when().post("/api/pedidos-parcelamento")
                .then().statusCode(400)
                .header("X-Trace-Id", notNullValue());

        assertEquals(errosAntes + 1, ApiErrorLog.count(), "erro 400 deve ser persistido no limiar de teste");

        ApiErrorLog erro = ApiErrorLog.find("ORDER BY criadoEm DESC, id DESC").firstResult();
        assertNotNull(erro);
        assertNotNull(erro.traceId);
        assertEquals("POST", erro.metodo);
        assertTrue(erro.path.contains("pedidos-parcelamento"));
        assertEquals(400, erro.status);
        assertNotNull(erro.corpoRequest, "corpo da requisição deve ser capturado");
        assertTrue(erro.corpoRequest.contains("123"), "corpo deve conter o payload enviado");
        assertNotNull(erro.criadoEm);
    }

    @Test
    void deveGerarTraceIdQuandoNaoEnviadoPeloCliente() {
        Map<String, Object> valido = new HashMap<>();
        valido.put("cpf", "52998224725");
        valido.put("nome", "Sem Trace");
        valido.put("tipo", "PORTABILIDADE");

        given()
                .contentType(ContentType.JSON)
                .body(valido)
                .when().post("/api/pedidos-parcelamento")
                .then().statusCode(201)
                .header("X-Trace-Id", notNullValue());
    }
}
