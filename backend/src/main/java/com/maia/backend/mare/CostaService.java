package com.maia.backend.mare;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CostaService {

    private static final double RAIO_METROS = 10_000;
    private static final double METROS_POR_GRAU = 111_195;

    private final List<double[]> segmentos = new ArrayList<>();

    public CostaService(ObjectMapper mapper) {
        try (InputStream arquivo = getClass().getResourceAsStream(
                "/geodata/coastline.geojson")) {

            if (arquivo == null) {
                throw new IllegalStateException(
                        "Arquivo coastline.geojson não encontrado.");
            }

            Map<?, ?> geojson = mapper.readValue(arquivo, Map.class);
            Object features = geojson.get("features");

            if (!(features instanceof List<?> lista)) {
                throw new IllegalStateException("GeoJSON inválido.");
            }

            for (Object featureObj : lista) {
                if (!(featureObj instanceof Map<?, ?> feature)) {
                    continue;
                }

                Object geometriaObj = feature.get("geometry");
                if (!(geometriaObj instanceof Map<?, ?> geometria)) {
                    continue;
                }

                if (!"LineString".equals(geometria.get("type"))) {
                    continue;
                }

                Object coordenadasObj = geometria.get("coordinates");
                if (!(coordenadasObj instanceof List<?> coordenadas)) {
                    continue;
                }

                double[] anterior = null;

                for (Object pontoObj : coordenadas) {
                    if (!(pontoObj instanceof List<?> ponto)
                            || ponto.size() < 2
                            || !(ponto.get(0) instanceof Number)
                            || !(ponto.get(1) instanceof Number)) {
                        anterior = null;
                        continue;
                    }

                    double longitude = ((Number) ponto.get(0)).doubleValue();
                    double latitude = ((Number) ponto.get(1)).doubleValue();
                    double[] atual = {latitude, longitude};

                    if (anterior != null) {
                        segmentos.add(new double[]{
                                anterior[0], anterior[1],
                                atual[0], atual[1]
                        });
                    }

                    anterior = atual;
                }
            }

            if (segmentos.isEmpty()) {
                throw new IllegalStateException(
                        "Nenhum segmento costeiro encontrado.");
            }

            System.out.println(
                    "CostaService: " + segmentos.size()
                            + " segmentos costeiros carregados.");

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Erro ao carregar dados costeiros.", e);
        }
    }

    public boolean possuiCostaProxima(double latitude, double longitude) {

        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            return false;
        }

        double escalaLongitude =
                METROS_POR_GRAU * Math.cos(Math.toRadians(latitude));

        for (double[] s : segmentos) {

            if (Math.min(s[0], s[2]) > latitude + 0.2
                    || Math.max(s[0], s[2]) < latitude - 0.2
                    || Math.min(s[1], s[3]) > longitude + 0.2
                    || Math.max(s[1], s[3]) < longitude - 0.2) {
                continue;
            }

            double ax = (s[1] - longitude) * escalaLongitude;
            double ay = (s[0] - latitude) * METROS_POR_GRAU;

            double bx = (s[3] - longitude) * escalaLongitude;
            double by = (s[2] - latitude) * METROS_POR_GRAU;

            double dx = bx - ax;
            double dy = by - ay;

            double comprimentoQuadrado = dx * dx + dy * dy;

            double t = comprimentoQuadrado == 0
                    ? 0
                    : -(ax * dx + ay * dy) / comprimentoQuadrado;

            t = Math.max(0, Math.min(1, t));

            double distancia = Math.hypot(
                    ax + t * dx,
                    ay + t * dy
            );

            if (distancia <= RAIO_METROS) {
                return true;
            }
        }

        return false;
    }
}