package br.com.auditoria.parcelamento.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jboss.logging.Logger;

import java.util.Locale;

public enum TipoParcelamento {
    REFINANCIAMENTO,
    RENEGOCIACAO,
    PORTABILIDADE,
    PARCELAMENTO_NEGOCIADO,
    PROMOCIONAL;

    private static final Logger LOG = Logger.getLogger(TipoParcelamento.class);

    @JsonCreator
    public static TipoParcelamento from(String valor) {
        if (valor == null) {
            LOG.debug("from(null) -> null");
            return null;
        }
        try {
            TipoParcelamento tipo = valueOf(valor.trim().toUpperCase(Locale.ROOT));
            LOG.debugf("from(\"%s\") -> %s", valor, tipo);
            return tipo;
        } catch (IllegalArgumentException e) {
            LOG.warnf("from(\"%s\") -> ERRO: tipo desconhecido", valor);
            throw new IllegalArgumentException("tipo de parcelamento desconhecido: " + valor);
        }
    }

    @JsonValue
    public String toJson() {
        LOG.debugf("toJson() <- %s", name());
        return name();
    }
}
