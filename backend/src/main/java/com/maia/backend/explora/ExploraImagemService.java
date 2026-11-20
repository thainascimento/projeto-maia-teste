package com.maia.backend.explora;

import com.maia.backend.local.LocalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExploraImagemService {

    // Evita consumir muitas consultas do plano gratuito em uma única chamada.
    private static final int MAX_CONSULTAS_POR_REQUISICAO = 6;
    private static final int MAX_ENTRADAS_CACHE = 250;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String accessKey;
    private final Map<String, Imagem> cache = new ConcurrentHashMap<>();

    public ExploraImagemService(
            ObjectMapper objectMapper,
            @Value("${unsplash.access-key:}") String accessKey
    ) {
        this.objectMapper = objectMapper;
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.restClient = RestClient.builder()
                .baseUrl("https://api.unsplash.com")
                .build();
    }

    public void adicionarImagens(List<LocalResponse> locais) {
        if (locais == null || locais.isEmpty() || accessKey.isBlank()) {
            return;
        }

        int consultas = 0;
        for (LocalResponse local : locais) {
            if (local == null || local.getNome() == null || local.getNome().isBlank()) {
                continue;
            }

            String busca = montarBusca(local);
            Imagem imagem = cache.get(busca);

            if (imagem == null) {
                if (consultas >= MAX_CONSULTAS_POR_REQUISICAO) {
                    continue;
                }
                consultas++;
                imagem = consultarUnsplash(busca);
                if (imagem != null) {
                    if (cache.size() >= MAX_ENTRADAS_CACHE) {
                        cache.clear();
                    }
                    cache.put(busca, imagem);
                }
            }

            if (imagem != null) {
                local.setImagemUrl(imagem.url());
                local.setImagemFotografo(imagem.fotografo());
                local.setImagemFotografoUrl(imagem.fotografoUrl());
                local.setImagemUnsplashUrl(imagem.paginaUrl());
            }
        }
    }

    private String montarBusca(LocalResponse local) {
        StringBuilder busca = new StringBuilder(local.getNome().trim());
        if (local.getCidade() != null && !local.getCidade().isBlank()) {
            busca.append(" ").append(local.getCidade().trim());
        }
        if (local.getPais() != null && !local.getPais().isBlank()) {
            busca.append(" ").append(local.getPais().trim());
        }
        return busca.toString();
    }

    private Imagem consultarUnsplash(String busca) {
        try {
            String json = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/photos")
                            .queryParam("query", busca)
                            .queryParam("per_page", 1)
                            .queryParam("orientation", "landscape")
                            .build())
                    .header("Authorization", "Client-ID " + accessKey)
                    .retrieve()
                    .body(String.class);

            if (json == null || json.isBlank()) {
                return null;
            }

            Map<String, Object> resposta = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() {}
            );
            Object resultadosObj = resposta.get("results");
            if (!(resultadosObj instanceof List<?> resultados) || resultados.isEmpty()) {
                return null;
            }
            if (!(resultados.get(0) instanceof Map<?, ?> foto)) {
                return null;
            }

            String url = texto(mapa(foto.get("urls")), "regular");
            if (url == null) {
                return null;
            }

            Map<?, ?> usuario = mapa(foto.get("user"));
            String fotografo = texto(usuario, "name");
            String fotografoUrl = texto(mapa(usuario.get("links")), "html");
            String paginaUrl = texto(mapa(foto.get("links")), "html");

            return new Imagem(url, fotografo, fotografoUrl, paginaUrl);
        } catch (RuntimeException erro) {
            // Falha ou limite do Unsplash não deve impedir o Explora de mostrar locais.
            return null;
        }
    }

    private Map<?, ?> mapa(Object valor) {
        return valor instanceof Map<?, ?> mapa ? mapa : Map.of();
    }

    private String texto(Map<?, ?> mapa, String chave) {
        Object valor = mapa.get(chave);
        if (!(valor instanceof String texto) || texto.isBlank()) {
            return null;
        }
        return texto;
    }

    private record Imagem(
            String url,
            String fotografo,
            String fotografoUrl,
            String paginaUrl
    ) {}
}
