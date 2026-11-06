package br.com.auditoria.parcelamento.dto;

import br.com.auditoria.parcelamento.model.PedidoParcelamento;
import br.com.auditoria.parcelamento.model.TipoParcelamento;

import java.math.BigDecimal;
import java.time.Instant;

public record PedidoParcelamentoResponse(
        Long id,
        String cpf,
        String nome,
        TipoParcelamento tipo,
        BigDecimal valorTotal,
        Instant dataPedido,
        String usuario) {

    public static PedidoParcelamentoResponse from(PedidoParcelamento pedido) {
        return new PedidoParcelamentoResponse(
                pedido.id,
                pedido.cpf,
                pedido.nome,
                pedido.tipo,
                pedido.valorTotal,
                pedido.dataPedido,
                pedido.usuario);
    }
}
