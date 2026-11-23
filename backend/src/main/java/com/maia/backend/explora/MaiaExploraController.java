
package com.maia.backend.explora;

import com.maia.backend.geocoding.GeoapifyPlacesService;
import com.maia.backend.local.LocalResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/maia-explora")
public class MaiaExploraController {

    private static final double DISTANCIA_DUPLICATA_METROS = 15.0;

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

        // Consulta original do Geoapify.
        List<LocalResponse> locais =
                geoapifyPlacesService.buscarLocais(
                        lat,
                        lon,
                        "pontos turísticos"
                );

        // Remove possíveis duplicatas somente do Explora.
        List<LocalResponse> locaisUnicos =
                removerDuplicatas(locais);

        // Busca fotografias apenas dos locais únicos.
        exploraImagemService.adicionarImagens(locaisUnicos);

        return locaisUnicos;
    }

    private List<LocalResponse> removerDuplicatas(
            List<LocalResponse> locais
    ) {
        List<LocalResponse> resultado = new ArrayList<>();

        if (locais == null || locais.isEmpty()) {
            return resultado;
        }

        for (LocalResponse atual : locais) {
            if (atual == null) {
                continue;
            }

            int indiceDuplicata = -1;

            for (int i = 0; i < resultado.size(); i++) {
                LocalResponse existente = resultado.get(i);

                if (saoDuplicatas(atual, existente)) {
                    indiceDuplicata = i;
                    break;
                }
            }

            if (indiceDuplicata == -1) {
                resultado.add(atual);
            } else {
                LocalResponse existente =
                        resultado.get(indiceDuplicata);

                if (pontuacao(atual) > pontuacao(existente)) {
                    resultado.set(indiceDuplicata, atual);
                }
            }
        }

        return resultado;
    }

    private boolean saoDuplicatas(
            LocalResponse primeiro,
            LocalResponse segundo
    ) {
        String nome1 = normalizar(primeiro.getNome());
        String nome2 = normalizar(segundo.getNome());

        if (nome1.isBlank() || !nome1.equals(nome2)) {
            return false;
        }

        if (!coordenadasValidas(primeiro)
                || !coordenadasValidas(segundo)) {
            return false;
        }

        double distancia = distanciaMetros(
                primeiro.getLatitude(),
                primeiro.getLongitude(),
                segundo.getLatitude(),
                segundo.getLongitude()
        );

        return distancia <= DISTANCIA_DUPLICATA_METROS;
    }

    private String normalizar(String texto) {
        if (texto == null) {
            return "";
        }

        return Normalizer.normalize(
                    texto, Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private boolean coordenadasValidas(LocalResponse local) {
        double lat = local.getLatitude();
        double lon = local.getLongitude();

        return Double.isFinite(lat)
                && Double.isFinite(lon)
                && Math.abs(lat) <= 90
                && Math.abs(lon) <= 180
                && !(lat == 0 && lon == 0);
    }

    private double distanciaMetros(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {
        double raioTerra = 6371008.8;

        double diferencaLat =
                Math.toRadians(lat2 - lat1);

        double diferencaLon =
                Math.toRadians(lon2 - lon1);

        double a =
                Math.pow(Math.sin(diferencaLat / 2), 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(diferencaLon / 2), 2);

        a = Math.max(0, Math.min(1, a));

        return 2 * raioTerra
                * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private int pontuacao(LocalResponse local) {
        int pontos = 0;

        if (preenchido(local.getEndereco())) pontos++;
        if (preenchido(local.getCidade())) pontos++;
        if (preenchido(local.getSite())) pontos++;
        if (preenchido(local.getTelefone())) pontos++;

        return pontos;
    }

    private boolean preenchido(String valor) {
        return valor != null && !valor.isBlank();
    }
}
