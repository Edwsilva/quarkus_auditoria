package br.com.auditoria.parcelamento;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class PedidoParcelamentoResourceTest {

    private static final String CPF = "98765432100";

    @Test
    void deveCriarConsultarEListarPedido() {
        Map<String, Object> request = new HashMap<>();
        request.put("cpf", "987.654.321-00");
        request.put("nome", "João Oliveira");
        request.put("tipo", "RENEGOCIACAO");
        request.put("valorTotal", 1200.50);

        Integer id = given()
                .contentType(ContentType.JSON)
                .header("X-Usuario", "operador.teste")
                .body(request)
                .when().post("/api/pedidos-parcelamento")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("cpf", equalTo(CPF))
                .body("usuario", equalTo("operador.teste"))
                .body("dataPedido", notNullValue())
                .extract().path("id");

        given()
                .when().get("/api/pedidos-parcelamento/{id}", id)
                .then().statusCode(200)
                .body("nome", equalTo("João Oliveira"))
                .body("tipo", equalTo("RENEGOCIACAO"));

        given()
                .queryParam("cpf", CPF)
                .when().get("/api/pedidos-parcelamento")
                .then().statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("items.id", hasItems(id));

        given()
                .queryParam("tipo", "renegociacao")
                .queryParam("de", "2000-01-01")
                .queryParam("ate", "2099-12-31")
                .when().get("/api/pedidos-parcelamento")
                .then().statusCode(200)
                .body("items", hasSize(greaterThanOrEqualTo(1)))
                .body("items.tipo", hasItems("RENEGOCIACAO"));
    }

    @Test
    void deveRejeitarTipoDesconhecidoComoFiltro() {
        given()
                .queryParam("tipo", "QUALQUER")
                .when().get("/api/pedidos-parcelamento")
                .then().statusCode(400);
    }

    @Test
    void deveRetornar404ParaPedidoInexistente() {
        given()
                .when().get("/api/pedidos-parcelamento/999999999")
                .then().statusCode(404);
    }

    @Test
    void deveRejeitarCpfInvalido() {
        Map<String, Object> request = new HashMap<>();
        request.put("cpf", "123");
        request.put("nome", "Maria");
        request.put("tipo", "PORTABILIDADE");

        given()
                .contentType(ContentType.JSON)
                .body(request)
                .when().post("/api/pedidos-parcelamento")
                .then().statusCode(400);
    }

    @Test
    void deveRejeitarRequestSemCamposObrigatorios() {
        Map<String, Object> request = new HashMap<>();
        request.put("nome", "Maria");
        request.put("tipo", "PORTABILIDADE");

        given()
                .contentType(ContentType.JSON)
                .body(request)
                .when().post("/api/pedidos-parcelamento")
                .then().statusCode(400);
    }

    @Test
    void deveRejeitarTipoDesconhecido() {
        Map<String, Object> request = new HashMap<>();
        request.put("cpf", "12345678900");
        request.put("nome", "Maria");
        request.put("tipo", "TIPO_INEXISTENTE");

        given()
                .contentType(ContentType.JSON)
                .body(request)
                .when().post("/api/pedidos-parcelamento")
                .then().statusCode(400);
    }
}
