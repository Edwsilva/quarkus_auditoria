package br.com.auditoria.parcelamento.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pedido_parcelamento", indexes = {
        @Index(name = "idx_pedido_cpf", columnList = "cpf"),
        @Index(name = "idx_pedido_data", columnList = "dataPedido")
})
public class PedidoParcelamento extends PanacheEntity {

    @Column(nullable = false, length = 11)
    public String cpf;

    @Column(nullable = false, length = 120)
    public String nome;

    @Column(nullable = false)
    public Instant dataPedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public TipoParcelamento tipo;

    @Column(precision = 14, scale = 2)
    public BigDecimal valorTotal;

    @Column(nullable = false, length = 60)
    public String usuario;

    @Column(nullable = false, updatable = false)
    public Instant criadoEm;
}
