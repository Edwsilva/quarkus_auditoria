package br.com.auditoria.parcelamento.dto;

import br.com.auditoria.parcelamento.model.TipoParcelamento;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record PedidoParcelamentoRequest(
        @NotBlank(message = "cpf é obrigatório")
        String cpf,

        @NotBlank(message = "nome é obrigatório")
        String nome,

        @NotNull(message = "tipo é obrigatório")
        TipoParcelamento tipo,

        @DecimalMin(value = "0", message = "valorTotal não pode ser negativo")
        BigDecimal valorTotal,

        Instant dataPedido) {
}
