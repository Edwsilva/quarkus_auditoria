package br.com.auditoria.parcelamento.resource;

import br.com.auditoria.parcelamento.dto.PedidoParcelamentoPage;
import br.com.auditoria.parcelamento.dto.PedidoParcelamentoRequest;
import br.com.auditoria.parcelamento.dto.PedidoParcelamentoResponse;
import br.com.auditoria.parcelamento.model.TipoParcelamento;
import br.com.auditoria.parcelamento.service.PedidoParcelamentoService;
import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import java.time.LocalDate;

@Path("/api/pedidos-parcelamento")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PedidoParcelamentoResource {

    private final PedidoParcelamentoService service;

    public PedidoParcelamentoResource(PedidoParcelamentoService service) {
        this.service = service;
    }

    @POST
    public Response criar(@Valid PedidoParcelamentoRequest request,
            @HeaderParam("X-Usuario") String usuario) {
        PedidoParcelamentoResponse response = service.criar(request, usuario);
        URI location = UriBuilder.fromResource(PedidoParcelamentoResource.class)
                .path("/{id}")
                .build(response.id());
        return Response.created(location).entity(response).build();
    }

    @GET
    public PedidoParcelamentoPage listar(
            @QueryParam("cpf") String cpf,
            @QueryParam("tipo") String tipo,
            @QueryParam("de") LocalDate de,
            @QueryParam("ate") LocalDate ate,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return service.listar(cpf, converterTipo(tipo), de, ate, page, size);
    }

    @GET
    @Path("/{id}")
    public Response obter(@PathParam("id") long id) {
        return Response.ok(service.obterPorId(id)).build();
    }

    private static TipoParcelamento converterTipo(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return TipoParcelamento.from(valor);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
