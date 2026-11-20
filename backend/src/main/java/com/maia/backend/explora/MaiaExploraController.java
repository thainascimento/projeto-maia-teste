
package com.maia.backend.explora;

import com.maia.backend.geocoding.GeoapifyPlacesService;
import com.maia.backend.local.LocalResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

        // Mantém a busca original dos pontos turísticos.
        List<LocalResponse> locais =
                geoapifyPlacesService.buscarLocais(
                        lat,
                        lon,
                        "pontos turísticos"
                );

        // Acrescenta fotografias sem remover os dados do Geoapify.
        exploraImagemService.adicionarImagens(locais);

        return locais;
    }
}
