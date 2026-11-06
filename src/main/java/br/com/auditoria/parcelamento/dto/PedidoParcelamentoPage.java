package br.com.auditoria.parcelamento.dto;

import java.util.List;

public record PedidoParcelamentoPage(
        long total,
        int page,
        int size,
        List<PedidoParcelamentoResponse> items) {
}
