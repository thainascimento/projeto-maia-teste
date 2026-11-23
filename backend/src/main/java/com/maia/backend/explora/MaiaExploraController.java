
package com.maia.backend.explora;

import com.maia.backend.geocoding.GeoapifyPlacesService;
import com.maia.backend.local.LocalResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/maia-explora")
public class MaiaExploraController {

    private final GeoapifyPlacesService geoapifyPlacesService;
    private final ExploraImagemService exploraImagemService;

    public MaiaExploraController(
            GeoapifyPlacesService geoapifyPlacesService,
            ExploraImagemService exploraImagemService
    ) {
        this.geoapifyPlacesService = geoapifyPlacesService;
        this.exploraImagemService = exploraImagemService;
    }

    @GetMapping("/atracoes")
    public List<LocalResponse> buscarAtracoes(
            @RequestParam double lat,
            @RequestParam double lon
    ) {

        // Busca os pontos turísticos utilizando o Geoapify.
        List<LocalResponse> locais =
                geoapifyPlacesService.buscarLocais(
                        lat,
                        lon,
                        "pontos turísticos"
                );

        // Remove apenas registros que possuem o mesmo ID.
        // Locais com IDs diferentes são preservados,
        // mesmo que tenham nomes e coordenadas semelhantes.
        List<LocalResponse> locaisUnicos =
                removerDuplicatasPorId(locais);

        // Adiciona fotografias aos pontos turísticos.
        // A busca de imagens funciona para destinos
        // brasileiros e internacionais.
        exploraImagemService.adicionarImagens(locaisUnicos);

        return locaisUnicos;
    }

    /**
     * Remove registros duplicados exclusivamente pelo ID.
     *
     * Dois locais são considerados duplicados somente
     * quando possuem o mesmo identificador.
     *
     * Registros com IDs diferentes são preservados,
     * mesmo que compartilhem nome, endereço
     * ou coordenadas muito próximas.
     */
    private List<LocalResponse> removerDuplicatasPorId(
            List<LocalResponse> locais
    ) {

        List<LocalResponse> resultado = new ArrayList<>();

        if (locais == null || locais.isEmpty()) {
            return resultado;
        }

        Set<String> idsEncontrados = new HashSet<>();

        for (LocalResponse local : locais) {

            if (local == null) {
                continue;
            }

            String id = local.getId();

            // Se o ID estiver ausente, preserva o local,
            // pois não é possível confirmar duplicidade.
            if (id == null || id.isBlank()) {
                resultado.add(local);
                continue;
            }

            // O método add() retorna false quando
            // o ID já existe no conjunto.
            if (idsEncontrados.add(id)) {
                resultado.add(local);
            }
        }

        return resultado;
    }
}
