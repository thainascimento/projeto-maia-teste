
package com.maia.backend.explora;

import com.maia.backend.geocoding.GeoapifyPlacesService;
import com.maia.backend.local.LocalResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/maia-explora")
public class MaiaExploraController {

    private static final int MAXIMO_ATRACOES = 24;
    private static final int MINIMO_ATRACOES = 12;

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

        List<LocalResponse> encontrados =
                geoapifyPlacesService.buscarLocais(
                        lat,
                        lon,
                        "pontos turísticos"
                );

        List<LocalResponse> candidatos = new ArrayList<>();

        adicionarCandidatos(candidatos, encontrados);

        int quantidadeInicial =
                encontrados == null ? 0 : encontrados.size();

        System.out.println(
                "[EXPLORA] Busca inicial: " + quantidadeInicial
                        + " | Candidatos aceitos: "
                        + candidatos.size()
        );

        // Caso a busca geral retorne poucos lugares
        // adequados, tenta categorias mais específicas.
        if (candidatos.size() < MINIMO_ATRACOES) {

            String[] categoriasAlternativas = {
                    "museus",
                    "mirantes",
                    "praias",
                    "igrejas"
            };

            for (String categoria : categoriasAlternativas) {

                try {

                    List<LocalResponse> alternativas =
                            geoapifyPlacesService.buscarLocais(
                                    lat,
                                    lon,
                                    categoria
                            );

                    adicionarCandidatos(
                            candidatos,
                            alternativas
                    );

                    System.out.println(
                            "[EXPLORA] Busca alternativa: "
                                    + categoria
                                    + " | Total acumulado: "
                                    + candidatos.size()
                    );

                } catch (Exception e) {

                    System.err.println(
                            "[EXPLORA] Erro ao buscar "
                                    + categoria
                                    + ": "
                                    + e.getMessage()
                    );
                }
            }
        }

        // A pontuação serve para ordenar,
        // nunca para eliminar atrações.
        candidatos.sort(
                Comparator
                        .comparingInt(
                                (LocalResponse local) ->
                                        pontuacao(local)
                        )
                        .reversed()
                        .thenComparingDouble(
                                this::distancia
                        )
        );

        List<LocalResponse> unicos =
                removerDuplicatas(candidatos);

        List<LocalResponse> resultado =
                new ArrayList<>();

        for (LocalResponse local : unicos) {

            if (resultado.size() >= MAXIMO_ATRACOES) {
                break;
            }

            resultado.add(local);
        }

        System.out.println(
                "[EXPLORA] Resultado final: "
                        + resultado.size()
                        + " atracoes."
        );

        return resultado;
    }

    private void adicionarCandidatos(
            List<LocalResponse> destino,
            List<LocalResponse> encontrados
    ) {

        if (encontrados == null || encontrados.isEmpty()) {
            return;
        }

        for (LocalResponse local : encontrados) {

            if (local == null) {
                continue;
            }

            System.out.println(
                    "[EXPLORA] Avaliando: "
                            + local.getNome()
                            + " | Categorias: "
                            + local.getCategoriasGeoapify()
            );

            if (!nomeValido(local)) {
                continue;
            }

            if (!localVisitavel(local)) {
                continue;
            }

            destino.add(local);
        }
    }

    // Evita recomendar pinturas, esculturas e
    // objetos individuais como destinos de passeio.
    private boolean localVisitavel(LocalResponse local) {

        if (local == null) {
            return false;
        }

        List<String> categorias =
                local.getCategoriasGeoapify();

        if (categorias != null) {

            for (String categoria : categorias) {

                if (categoria == null) {
                    continue;
                }

                String c = categoria
                        .toLowerCase(Locale.ROOT)
                        .trim();

                if (c.equals("tourism.attraction.artwork")
                        || c.startsWith(
                                "tourism.attraction.artwork."
                        )) {

                    return false;
                }
            }
        }

        String nome = normalizar(local.getNome());

        if (contem(
                nome,
                "placa de inauguracao",
                "placa comemorativa",
                "placa informativa",
                "placa de identificacao",
                "painel informativo",
                "placa de homenagem",
                "pintura a oleo",
                "quadro a oleo",
                "obra pictorica"
        )) {

            return false;
        }

        return true;
    }

    private boolean nomeValido(LocalResponse local) {

        if (local == null || local.getNome() == null) {
            return false;
        }

        String nome = local.getNome().trim();

        if (nome.length() < 4 || nome.length() > 120) {
            return false;
        }

        if (!nome.matches(".*[\\p{L}].*")) {
            return false;
        }

        String normalizado = normalizar(nome);

        Set<String> nomesGenericos = Set.of(
                "sem nome",
                "unknown",
                "unnamed",
                "no name",
                "atracao",
                "ponto turistico",
                "monumento",
                "estatua",
                "escultura",
                "placa",
                "obra de arte"
        );

        if (nomesGenericos.contains(normalizado)) {
            return false;
        }

        return !normalizado.startsWith("http ")
                && !normalizado.startsWith("https ")
                && !normalizado.startsWith("www ");
    }

    private int pontuacao(LocalResponse local) {

        int pontos = 20;

        List<String> categorias =
                local.getCategoriasGeoapify();

        if (categorias != null) {

            for (String categoria : categorias) {

                if (categoria == null) {
                    continue;
                }

                String c = categoria
                        .toLowerCase(Locale.ROOT)
                        .trim();

                if (c.startsWith("entertainment.museum")) {
                    pontos = Math.max(pontos, 100);

                } else if (
                        c.startsWith("tourism.attraction.viewpoint")
                ) {
                    pontos = Math.max(pontos, 95);

                } else if (
                        c.startsWith("tourism.sights.castle")
                        || c.startsWith("tourism.sights.fort")
                ) {
                    pontos = Math.max(pontos, 95);

                } else if (
                        c.startsWith("tourism.sights.lighthouse")
                        || c.startsWith("tourism.sights.ruines")
                        || c.startsWith(
                                "tourism.sights.archaeological_site"
                        )
                ) {
                    pontos = Math.max(pontos, 85);

                } else if (
                        c.startsWith("tourism.sights.place_of_worship")
                ) {
                    pontos = Math.max(pontos, 70);

                } else if (
                        c.equals("beach")
                        || c.startsWith("natural.beach")
                ) {
                    pontos = Math.max(pontos, 90);

                } else if (
                        c.startsWith("leisure.park")
                        || c.startsWith("leisure.garden")
                ) {
                    pontos = Math.max(pontos, 85);

                } else if (
                        c.startsWith("tourism.sights.memorial")
                ) {
                    pontos = Math.max(pontos, 35);

                } else if (
                        c.equals("tourism.attraction")
                        || c.equals("tourism.sights")
                ) {
                    pontos = Math.max(pontos, 40);
                }
            }
        }

        String nome = normalizar(local.getNome());

        if (contem(
                nome,
                "museu",
                "museum",
                "pinacoteca",
                "centro cultural",
                "teatro"
        )) {
            pontos = Math.max(pontos, 85);
        }

        if (contem(
                nome,
                "parque",
                "jardim botanico",
                "reserva natural",
                "trilha"
        )) {
            pontos = Math.max(pontos, 85);
        }

        if (contem(
                nome,
                "praia",
                "piscinas naturais",
                "mirante",
                "cachoeira"
        )) {
            pontos = Math.max(pontos, 90);
        }

        if (contem(
                nome,
                "catedral",
                "mosteiro",
                "santuario",
                "igreja historica"
        )) {
            pontos = Math.max(pontos, 75);
        }

        return pontos;
    }

    private boolean contem(
            String texto,
            String... termos
    ) {

        for (String termo : termos) {

            if (texto.contains(termo)) {
                return true;
            }
        }

        return false;
    }

    private List<LocalResponse> removerDuplicatas(
            List<LocalResponse> locais
    ) {

        List<LocalResponse> resultado =
                new ArrayList<>();

        Set<String> ids = new HashSet<>();

        for (LocalResponse local : locais) {

            String id = local.getId();

            if (id != null
                    && !id.isBlank()
                    && ids.contains(id)) {
                continue;
            }

            boolean duplicado = false;

            for (LocalResponse existente : resultado) {

                boolean mesmoNome =
                        normalizar(local.getNome()).equals(
                                normalizar(existente.getNome())
                        );

                if (!mesmoNome) {
                    continue;
                }

                double metros = distanciaEntre(
                        local.getLatitude(),
                        local.getLongitude(),
                        existente.getLatitude(),
                        existente.getLongitude()
                );

                if (metros <= 100) {
                    duplicado = true;
                    break;
                }
            }

            if (!duplicado) {

                resultado.add(local);

                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }

        return resultado;
    }

    private double distancia(LocalResponse local) {

        Double metros = local.getDistanciaMetros();

        if (metros != null
                && Double.isFinite(metros)
                && metros >= 0) {
            return metros;
        }

        return Double.MAX_VALUE;
    }

    private double distanciaEntre(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {

        final double raioTerra = 6371008.8;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a =
                Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);

        return 2 * raioTerra
                * Math.atan2(
                        Math.sqrt(a),
                        Math.sqrt(Math.max(0, 1 - a))
                );
    }

    private String normalizar(String valor) {

        if (valor == null) {
            return "";
        }

        return Normalizer.normalize(
                        valor,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
