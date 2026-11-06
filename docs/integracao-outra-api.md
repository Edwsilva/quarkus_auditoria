# Como integrar outra API ao mesmo Loki e Grafana

Este guia mostra como uma **segunda API** passa a ser monitorada pela stack que já existe neste
projeto (Postgres + Loki + Grafana), sem subir outra instância de nada.

---

## 1. Visão geral da stack atual

| Serviço  | Porta (host)                       | Uso                                                                    |
| -------- | ---------------------------------- | ---------------------------------------------------------------------- |
| Loki     | `localhost:3100`                   | Recebe e indexa eventos/logs (`POST /loki/api/v1/push`)                |
| Grafana  | `localhost:3000` (`admin`/`admin`) | Consulta e dashboards; datasource `Loki` já provisionado (`uid: loki`) |
| Postgres | `localhost:5432`                   | Só da API de parcelamento — **não é compartilhado**                    |

A aplicação atual envia eventos em `AuditoriaLokiService` → `POST http://localhost:3100/loki/api/v1/push`.

> A outra API pode rodar **na host** (como esta) ou **dentro do docker-compose** — só muda a URL do Loki
> (ver passo A.3).

---

## 2. Conceito-chave: a separação é feita por labels

O Loki não tem "tenant por API": ele separa tudo por **labels de stream**. A regra é:

```
{job="auditoria"}        ← API de parcelamento (atual)
{job="outra-api"}        ← nova API
```

- **Labels** (índice, baixa cardinalidade): `job`, `app`, `evento`, `nivel` — poucos valores possíveis.
- **Log line** (dentro do JSON): cpf, id, usuário, mensagem — valores livres e de alta cardinalidade.

⚠️ **Nunca** use cpf, id de pedido ou usuário como label — isso fragmenta o índice e estoura o Loki.

---

## 3. Opção A — Push direto da API (recomendada para APIs que você controla)

Copie o padrão já testado neste projeto.

### A.1 Copiar o serviço de push

Copie o arquivo:

```
src/main/java/br/com/auditoria/parcelamento/service/AuditoriaLokiService.java
```

para a nova API (ex.: `com.outraapi.auditoria.LokiPushService`) e ajuste:

| O que mudar               | De                             | Para                                      |
| ------------------------- | ------------------------------ | ----------------------------------------- |
| Nome da classe            | `AuditoriaLokiService`         | `LokiPushService` (ou similar)            |
| `audit.loki.job` (config) | `auditoria`                    | `outra-api`                               |
| `audit.loki.app` (config) | `pedido-parcelamento`          | nome da nova API                          |
| Payload                   | campos de `PedidoParcelamento` | os campos que fizerem sentido na nova API |

O formato do push **não muda** — só as labels e o conteúdo da log line:

```json
{
  "streams": [
    {
      "stream": {
        "job": "outra-api",
        "app": "meu-servico",
        "evento": "PEDIDO_CRIADO"
      },
      "values": [["<nanos desde epoch>", "<linha de log em JSON>"]]
    }
  ]
}
```

### A.2 Dependência no `pom.xml` da nova API

Se for Quarkus/Java com Vert.x (mesma stack):

```xml
<dependency>
  <groupId>io.vertx</groupId>
  <artifactId>vertx-web-client</artifactId>
</dependency>
```

Outras linguagens: qualquer HTTP client assíncrono serve (a chamada é um `POST` simples).

### A.3 Configuração (`application.properties`)

```properties
audit.loki.enabled=true
audit.loki.job=outra-api
audit.loki.app=meu-servico

# API rodando NA HOST:
audit.loki.url=http://localhost:3100

# API rodando DENTRO do docker-compose (mesma rede):
# audit.loki.url=http://loki:3100
```

### A.4 Disparar o push no código da nova API

```java
@Inject
LokiPushService loki;

public void aoCriarPedido(Pedido p) {
    loki.registrarEvento("PEDIDO_CRIADO", jsonDoEvento(p));
}
```

### A.5 Teste manual (funciona em qualquer linguagem)

```bash
curl -X POST http://localhost:3100/loki/api/v1/push \
  -H "Content-Type: application/json" \
  -d '{
    "streams": [{
      "stream": {"job": "outra-api", "app": "teste-manual", "evento": "SMOKE_TEST"},
      "values": [["'"$(date +%s%N)"'", "{\"msg\":\"hello do outra-api\"}"]]
    }]
  }'
```

---

## 4. Opção B — Coleta com Grafana Alloy (sem tocar no código da API)

Para API de terceiros, que só grava logs em arquivo/stdout.

### B.1 Adicionar ao `docker-compose.yml`

```yaml
alloy:
  image: grafana/alloy:v1.21.3
  command:
    [
      "--storage.path=/var/lib/alloy/data",
      "--config.file=/etc/alloy/config.alloy",
    ]
  volumes:
    - ./deploy/alloy/config.alloy:/etc/alloy/config.alloy:ro
    - /var/log/outra-api:/var/log/outra-api:ro # diretório de logs da outra API
  depends_on:
    - loki
```

### B.2 Criar `deploy/alloy/config.alloy`

```river
local.file_match "outra_api" {
  path_targets = ["/var/log/outra-api/*.log"]
}

loki.source.file "outra_api" {
  targets    = local.file_match.outra_api.targets
  forward_to = [loki.write.outra_api.receiver]
}

loki.write "outra_api" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"

    // se a API já escrever JSON com labels em algum campo, use loki.process
    // com stage.json/stage.labels antes do forward_to
  }
}
```

Suba com:

```bash
docker-compose up -d alloy
```

---

## 5. Visualizando no Grafana

### 5.1 Consulta imediata (Explore)

1. Abra Grafana → **Explore** → datasource **Loki**.
2. Digite: `{job="outra-api"}` → **Run query**.
3. Os eventos devem aparecer na linha do tempo.

### 5.2 Dashboard novo (reaproveitando o atual)

1. Copie o dashboard existente:

```bash
cp deploy/grafana/dashboards/auditoria.json deploy/grafana/dashboards/outra-api.json
```

2. Edite `deploy/grafana/dashboards/outra-api.json`:

| Campo                 | De                                    | Para                            |
| --------------------- | ------------------------------------- | ------------------------------- |
| `uid`                 | `auditoria-parcelamento`              | `outra-api` (precisa ser único) |
| `title`               | `Auditoria - Pedidos de Parcelamento` | `Monitoramento - Outra API`     |
| Todas as `expr`       | `{job="auditoria"}`                   | `{job="outra-api"}`             |
| Painéis com `evento=` | `PEDIDO_PARCELAMENTO_CRIADO`          | eventos da nova API             |

3. Reinicie o Grafana (ou aguarde o `updateIntervalSeconds: 30` do provisionamento):

```bash
docker-compose restart grafana
```

> O `deploy/grafana/provisioning/dashboards/dashboards.yaml` já aponta para
> `/var/lib/grafana/dashboards` — **qualquer** `.json` novo nessa pasta é carregado automaticamente,
> sem alterar arquivos de provisionamento.

### 5.3 Organização opcional

Para separar visualmente, crie uma pasta no Grafana (ex.: "Outra API") e mova o dashboard para lá
na UI, ou adicione um segundo `provider` em `dashboards.yaml` com outra `folder`.

---

## 6. Consultas LogQL úteis

```logql
# Todos os eventos da nova API
{job="outra-api"}

# Últimos erros
{job="outra-api"} | json | nivel="ERROR"

# Contagem por evento nos últimos 5 min
sum by (evento) (count_over_time({job="outra-api"} | json [5m]))

# Busca textual
{job="outra-api"} |= "timeout"

# Comparar as duas APIs no mesmo painel
sum by (job) (count_over_time({job=~"auditoria|outra-api"} | json [5m]))
```

---

## 7. Checklist de verificação

```bash
# 1. Stack de observabilidade no ar
docker-compose up -d
curl -s http://localhost:3100/ready          # → 200

# 2. Push da nova API chegando
curl -s -G "http://localhost:3100/loki/api/v1/query" \
  --data-urlencode 'query={job="outra-api"}' | python3 -m json.tool

# 3. Dashboard provisionado (precisa aparecer com uid "outra-api")
curl -s -u admin:admin http://localhost:3000/api/dashboards/uid/outra-api

# 4. No Grafana UI: Explore → {job="outra-api"} → eventos visíveis
```

---

## 8. Resumo dos arquivos

| Arquivo                                                    | Precisa mudar?                             | Quando                   |
| ---------------------------------------------------------- | ------------------------------------------ | ------------------------ |
| `docker-compose.yml`                                       | Não (só se for Opção B: adicionar `alloy`) | Coleta sem código        |
| `deploy/loki/loki-config.yaml`                             | **Não**                                    | —                        |
| `deploy/grafana/provisioning/datasources/datasources.yaml` | **Não**                                    | Mesmo datasource serve   |
| `deploy/grafana/provisioning/dashboards/dashboards.yaml`   | **Não**                                    | Pasta já é compartilhada |
| `deploy/grafana/dashboards/outra-api.json`                 | **Novo arquivo**                           | Dashboard da nova API    |
| `deploy/alloy/config.alloy`                                | **Novo arquivo**                           | Só na Opção B            |
| Código da outra API (serviço de push + config)             | **Sim**                                    | Opção A                  |

curl -i -X POST http://localhost:8080/api/pedidos-parcelamento -H "Content-Type: application/json; charset=UTF-8" -H "Accept: application/json" -H "X-Usuario: operador1" -d '{
"cpf": "96185899787",
"nome": "José da Silva",
"tipo": "PROMOCIonAL",
"valorTotal": 18000
}'
