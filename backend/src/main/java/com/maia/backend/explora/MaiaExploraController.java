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

    public MaiaExploraController(
            GeoapifyPlacesService geoapifyPlacesService
    ) {
        this.geoapifyPlacesService = geoapifyPlacesService;
    }

    @GetMapping("/atracoes")
    public List<LocalResponse> buscarAtracoes(
            @RequestParam double lat,
            @RequestParam double lon
    ) {
        return geoapifyPlacesService.buscarLocais(
                lat,
                lon,
                "pontos turísticos"
        );
    }
}