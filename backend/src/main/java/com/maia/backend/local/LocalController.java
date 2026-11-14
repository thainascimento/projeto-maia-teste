package com.maia.backend.local;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maia.backend.geocoding.GeoapifyPlacesService;

@RestController
@RequestMapping("/locais")
public class LocalController {

    private final GeoapifyPlacesService
            geoapifyPlacesService;

    public LocalController(
            GeoapifyPlacesService geoapifyPlacesService
    ) {
        this.geoapifyPlacesService =
                geoapifyPlacesService;
    }

    @GetMapping("/proximos")
    public List<LocalResponse> buscarProximos(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam String categoria
    ) {

        return geoapifyPlacesService
                .buscarLocais(
                        lat,
                        lon,
                        categoria
                );
    }
}