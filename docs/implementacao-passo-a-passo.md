# Implementação passo a passo — Projeto de Auditoria de Parcelamento

Histórico de cada etapa executada, **na ordem real**, com os arquivos tocados, os motivos das
decisões e as verificações realizadas.

---

## Passo 1 — Levantamento do escopo e decisões

**Objetivo declarado:** backend Quarkus simples que audite **quem** fez um pedido de parcelamento
de dívida, armazenando CPF, nome, data do pedido e tipo do parcelamento para consultas futuras.

Três decisões foram tomadas antes de escrever qualquer código:

| Decisão | Escolha | Motivo |
|---|---|---|
| Banco de dados | PostgreSQL em Docker | Persistência real e consultável; mesmo compose da stack |
| Integração com Loki | Push HTTP de eventos estruturados | Só o que interessa ao audit vai ao Loki, sem ruído de log |
| Infraestrutura | `docker-compose` completo (Postgres + Loki + Grafana) | Stack reprodutível com um comando |

**Arquivos criados depois desta decisão:** nenhum ainda — decisão que moldou tudo o que veio.

---

## Passo 2 — Verificação do ambiente e escolha de versões

Ambiente inspecionado:

- Java 21 (Temurin), Maven 3.10.0, Docker 29 com `docker-compose` 1.29 (v1), sem Gradle → **Maven**
- Acesso ao Maven Central e ao Docker Hub OK

Versões pinadas (nunca `latest`):

| Componente | Versão | Como foi escolhida |
|---|---|---|
| Quarkus (BOM) | `3.40.1` | última estável em `maven-metadata.xml` (evitando `CR1`/`Beta`) |
| Loki | `3.7.9` | maior tag estável no Docker Hub |
| Grafana | `13.3.0` | maior tag estável no Docker Hub |
| Postgres | `16-alpine` | estável e leve |

---

## Passo 3 — Contrato antes da implementação

O contrato foi definido **antes** do código, na coleção Postman:

**Arquivo:** `postman/collection.json` (schema v2.1)

- `POST /api/pedidos-parcelamento` → 201, salva `pedidoId` em variável de coleção
- `GET /api/pedidos-parcelamento/{id}` → 200/404
- `GET` com filtros (`cpf`, `tipo`, `de`, `ate`, paginação) → 200
- Casos negativos: CPF inválido → 400, tipo desconhecido → 400
- Requests do Loki: `/ready` e query de auditoria

As asserções da coleção viraram a referência de "comportamento pretendido" para os testes
JUnit e para a validação final com newman.

---

## Passo 4 — Projeto Maven e configuração

**Arquivos:**

- `pom.xml` — Quarkus 3.40.1, Java 21, dependências: `quarkus-rest`, `quarkus-rest-jackson`,
  `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-hibernate-validator`,
  `quarkus-arc`, `vertx-web-client`; testes com `quarkus-junit5` e `rest-assured`.
- `src/main/resources/application.properties` — decisões importantes:

```properties
# Perfis: dev/prod usam o Postgres do docker-compose;
# o %test NÃO define url → Dev Services (Testcontainers) sobe um Postgres isolado nos testes
%dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/auditoria
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/auditoria

audit.loki.enabled=true
audit.loki.url=http://localhost:3100
audit.loki.job=auditoria
audit.loki.app=pedido-parcelamento
%test.audit.loki.enabled=false   # nos testes não há Loki
```

Isso separou os três mundos: dev (compose), test (container descartável) e prod (compose).

---

## Passo 5 — Código de domínio

**Arquivos criados** (pacote `br.com.auditoria.parcelamento`):

| Arquivo | Papel |
|---|---|
| `model/TipoParcelamento.java` | enum de tipos + `@JsonCreator` (entrada case-insensitive) e `@JsonValue` (saída = `name()`) |
| `model/PedidoParcelamento.java` | `@Entity` + Panache, índices em `cpf` e `dataPedido` |
| `dto/PedidoParcelamentoRequest.java` | record com `@NotBlank`/`@NotNull`/`@DecimalMin` |
| `dto/PedidoParcelamentoResponse.java` | record de saída (não expõe a entidade) |
| `dto/PedidoParcelamentoPage.java` | paginação `{total, page, size, items}` |

Decisão: **DTOs records** separados da entidade — a entidade nunca vira JSON direto, evitando
vazar campos acidentalmente.

---

## Passo 6 — Serviços

**Arquivos:**

1. `service/PedidoParcelamentoService.java`
   - `criar`: normaliza CPF (só dígitos, exige 11), define `dataPedido` (padrão = agora),
     grava `usuario` (header `X-Usuario`, fallback `desconhecido`), persiste e **dispara o push ao Loki**.
   - `listar`: filtro dinâmico JPQL (`cpf`, `tipo`, `de`/`ate` inclusivos), ordenação por
     `dataPedido` desc, paginação com limites (size 1..100).
   - `obterPorId`: `404` quando não existe.
   - Anotado com `@Transactional` (classe inteira).

2. `service/AuditoriaLokiService.java`
   - Push **assíncrono** com `Vert.x WebClient` para `POST {url}/loki/api/v1/push`.
   - Labels: `job=auditoria`, `app=pedido-parcelamento`, `evento=PEDIDO_PARCELAMENTO_CRIADO`.
   - Log line em JSON: `ts, evento, id, cpf, nome, tipo, dataPedido, usuario, valorTotal`.
   - Falha no Loki gera apenas `WARN` — o registro no Postgres não é afetado
     (**dual-write**: banco = fonte da verdade, Loki = observabilidade).
   - Config `audit.loki.enabled` permite desligar (usado nos testes).

---

## Passo 7 — Endpoint REST

**Arquivo:** `resource/PedidoParcelamentoResource.java`

```
POST /api/pedidos-parcelamento        → 201 + Location + body
GET  /api/pedidos-parcelamento        → paginação + filtros
GET  /api/pedidos-parcelamento/{id}   → 200 / 404
```

`@Valid` no body entrega 400 automático de Bean Validation.

---

## Passo 8 — Testes de integração

**Arquivo:** `src/test/java/.../PedidoParcelamentoResourceTest.java`

`@QuarkusTest` + RestAssured cobrindo o contrato do Passo 3:

- criar → consultar → listar por CPF → listar por tipo/período
- 404 para id inexistente
- 400: CPF curto, campos obrigatórios faltando, tipo desconhecido

Os testes rodam com **Dev Services** (Postgres em container isolado) e `audit.loki.enabled=false`.

---

## Passo 9 — Infraestrutura (compose + Loki + Grafana)

**Arquivos:**

| Arquivo | Conteúdo |
|---|---|
| `docker-compose.yml` | `postgres:16-alpine` (com healthcheck), `grafana/loki:3.7.9`, `grafana/grafana:13.3.0` (admin/admin) |
| `deploy/loki/loki-config.yaml` | config single-process: `path_prefix: /loki`, TSDB + filesystem |
| `deploy/grafana/provisioning/datasources/datasources.yaml` | datasource Loki com `uid: loki` fixo |
| `deploy/grafana/provisioning/dashboards/dashboards.yaml` | pasta `Auditoria`, carrega JSON de `/var/lib/grafana/dashboards` |
| `deploy/grafana/dashboards/auditoria.json` | 5 painéis: eventos 24h (stat), pedidos/h (stat), por tipo (timeseries), donut por tipo, fluxo de logs |

O `uid: loki` fixo no datasource permite referenciá-lo nos painéis sem depender de nome.

---

## Passo 10 — Primeira verificação: `mvn package` e correções

A compilação falhou 3 vezes e cada falha corrigiu algo real:

1. **Pacote da Panache mudou no Quarkus 3.40**
   `io.quarkus.panache.common.PanacheEntity` → `io.quarkus.hibernate.orm.panache.PanacheEntity`
   (mesma coisa para `PanacheQuery`; `Page` e `Sort` continuaram em `io.quarkus.panache.common`).

2. **`@ConfigProperty` não existe em `jakarta.inject`**
   Import correto: `org.eclipse.microprofile.config.inject.ConfigProperty`.

3. **`TransactionRequiredException` → 500 no POST**
   Faltava `@Transactional` em `PedidoParcelamentoService`.

Resultado: `mvn test` → **5/5 testes passando**.

---

## Passo 11 — Validação E2E (stack real)

1. `docker-compose up -d` → aguardar Loki `/ready` = 200 e Postgres `healthy`.
2. `java -jar target/quarkus-app/quarkus-run.jar` (perfil `%prod`).
3. `POST` com header `X-Usuario` → **201** com `id`, CPF persistido e `usuario` gravado.
4. `GET ?cpf=111.444.777-35` (com pontuação) → normalizado e encontrado.
5. Query no Loki → evento presente com labels `job/app/evento` e JSON completo na log line.
6. Grafana API → datasource `loki` provisionado; dashboard `auditoria-parcelamento` com 5 painéis
   na pasta **Auditoria**.
7. LogQL `sum by (tipo) (count_over_time({job="auditoria"} | json [5m]))` → métrica correta.
8. **Coleção Postman via newman**: 10 requisições, 18 asserções, **0 falhas**.

---

## Passo 12 — Ajustes de acabamento

- **Propriedade deprecada**: `quarkus.hibernate-orm.database.generation` →
  `quarkus.hibernate-orm.schema-management.strategy=update` (aviso sumiu do boot).
- **Diagnóstico de um "id 51"** aparentemente estranho: não havia vazamento de dados — o Hibernate
  usa *pooled optimizer* (lote de 50); ao reiniciar o app o sequence pulou para o próximo lote.
  Confirmado que os testes usavam container próprio (`jdbc:postgresql://localhost:32773/quarkus`).

---

## Passo 13 — Logs no enum e bug real encontrado na demonstração

Com a demanda de "ver como o componente funciona":

1. `TipoParcelamento` ganhou `Logger` (DEBUG no `from()`/`toJson()`, WARN em valor inválido) e
   `application.properties` ganhou
   `quarkus.log.category."br.com.auditoria.parcelamento.model".level=DEBUG`.
2. A demonstração **revelou um bug**: `GET ?tipo=renegociacao` (minúsculo) retornava **404**.
   Causa: `@JsonCreator` só vale para **corpo JSON**; query params são convertidos pelo JAX-RS com
   `valueOf` (sensível a maiúsculas).
3. **Correção**: query param `tipo` passou a ser `String` e é convertido por
   `TipoParcelamento.from()` dentro de `PedidoParcelamentoResource.converterTipo()`, devolvendo
   **400** (não 404) para valor desconhecido.
4. Novo teste cobrindo o filtro minúsculo e o filtro inválido → `mvn test` → **6/6**.

Demonstração final:

```
POST  {"tipo":"portabilidade"}          → 201 + DEBUG from("portabilidade") -> PORTABILIDADE
GET   ?tipo=portabilidade               → 200
GET   ?tipo=QUALQUER                    → 400 + WARN from("QUALQUER") -> ERRO
```

---

## Passo 14 — Documentação

- `docs/integracao-outra-api.md` — como reaproveitar o mesmo Loki/Grafana em outra API.
- `docs/implementacao-passo-a-passo.md` — este documento.

---

## Estrutura final do projeto

```
loki/
├── pom.xml
├── docker-compose.yml
├── postman/collection.json              # contrato + asserções
├── docs/
│   ├── implementacao-passo-a-passo.md
│   └── integracao-outra-api.md
├── deploy/
│   ├── loki/loki-config.yaml
│   └── grafana/
│       ├── provisioning/datasources/datasources.yaml
│       ├── provisioning/dashboards/dashboards.yaml
│       └── dashboards/auditoria.json
└── src/
    ├── main/java/br/com/auditoria/parcelamento/
    │   ├── model/{PedidoParcelamento,TipoParcelamento}.java
    │   ├── dto/{PedidoParcelamentoRequest,Response,Page}.java
    │   ├── service/{PedidoParcelamentoService,AuditoriaLokiService}.java
    │   └── resource/PedidoParcelamentoResource.java
    ├── main/resources/application.properties
    └── test/java/.../PedidoParcelamentoResourceTest.java   # 6 testes
```

## Como rodar

```bash
docker-compose up -d        # Postgres + Loki + Grafana
mvn quarkus:dev             # em dev  (ou: java -jar target/quarkus-app/quarkus-run.jar)
mvn test                    # testes com Dev Services (não precisa do compose)
newman run postman/collection.json --env-var baseUrl=http://localhost:8080
```

## Resultados das verificações

| Verificação | Resultado |
|---|---|
| `mvn test` | 6/6 testes, 0 falhas (Postgres isolado via Testcontainers) |
| newman (coleção completa) | 10 requisições, 18 asserções, 0 falhas |
| E2E POST → Loki | evento presente com labels e JSON corretos |
| E2E Grafana | datasource + dashboard provisionados; LogQL validado |
