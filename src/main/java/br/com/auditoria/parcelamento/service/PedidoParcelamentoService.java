package br.com.auditoria.parcelamento.service;

import br.com.auditoria.parcelamento.dto.PedidoParcelamentoPage;
import br.com.auditoria.parcelamento.dto.PedidoParcelamentoRequest;
import br.com.auditoria.parcelamento.dto.PedidoParcelamentoResponse;
import br.com.auditoria.parcelamento.model.PedidoParcelamento;
import br.com.auditoria.parcelamento.model.TipoParcelamento;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Transactional
@ApplicationScoped
public class PedidoParcelamentoService {

    private static final int TAMANHO_PADRAO = 20;
    private static final int TAMANHO_MAXIMO = 100;

    @Inject
    AuditoriaLokiService auditoria;

    public PedidoParcelamentoResponse criar(PedidoParcelamentoRequest request, String usuario) {
        PedidoParcelamento pedido = new PedidoParcelamento();
        pedido.cpf = normalizarCpf(request.cpf());
        pedido.nome = request.nome().trim();
        pedido.tipo = request.tipo();
        pedido.valorTotal = request.valorTotal();
        pedido.dataPedido = request.dataPedido() != null ? request.dataPedido() : Instant.now();
        pedido.usuario = (usuario == null || usuario.isBlank()) ? "desconhecido" : usuario.trim();
        pedido.criadoEm = Instant.now();
        pedido.persist();

        auditoria.pedidoCriado(pedido);

        return PedidoParcelamentoResponse.from(pedido);
    }

    public PedidoParcelamentoResponse obterPorId(long id) {
        PedidoParcelamento pedido = PedidoParcelamento.findById(id);
        if (pedido == null) {
            throw new NotFoundException("pedido " + id + " não encontrado");
        }
        return PedidoParcelamentoResponse.from(pedido);
    }

    public PedidoParcelamentoPage listar(String cpf, TipoParcelamento tipo, LocalDate de, LocalDate ate,
            int page, int size) {
        int pagina = Math.max(page, 0);
        int tamanho = Math.min(Math.max(size, 1), TAMANHO_MAXIMO);

        StringBuilder filtro = new StringBuilder("1=1");
        List<Object> params = new ArrayList<>();

        if (cpf != null && !cpf.isBlank()) {
            params.add(normalizarCpf(cpf));
            filtro.append(" and cpf = ?").append(params.size());
        }
        if (tipo != null) {
            params.add(tipo);
            filtro.append(" and tipo = ?").append(params.size());
        }
        if (de != null) {
            params.add(de.atStartOfDay(ZoneOffset.UTC).toInstant());
            filtro.append(" and dataPedido >= ?").append(params.size());
        }
        if (ate != null) {
            params.add(ate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
            filtro.append(" and dataPedido < ?").append(params.size());
        }

        PanacheQuery<PedidoParcelamento> query = PedidoParcelamento
                .find(filtro.toString(), Sort.descending("dataPedido"), params.toArray());
        long total = query.count();
        query.page(Page.of(pagina, tamanho));

        List<PedidoParcelamentoResponse> items = query.list().stream()
                .map(PedidoParcelamentoResponse::from)
                .toList();

        return new PedidoParcelamentoPage(total, pagina, tamanho, items);
    }

    private static String normalizarCpf(String cpf) {
        String digitos = cpf.replaceAll("\\D", "");
        if (digitos.length() != 11) {
            throw new BadRequestException("CPF deve conter 11 dígitos");
        }
        return digitos;
    }
}
