package com.maia.backend.explora;

import com.maia.backend.local.LocalResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ExploraImagemService {

    /*
     * Fotografias verificadas manualmente.
     * As imagens devem ser associadas pelo ID do Geoapify.
     * Nenhuma fotografia sera atribuida por busca textual.
     */
    private static final Map<String, Imagem> IMAGENS_APROVADAS =
            Map.of();

    public void adicionarImagens(List<LocalResponse> locais) {

        if (locais == null || locais.isEmpty()) {
            return;
        }

        for (LocalResponse local : locais) {

            if (local == null || local.getId() == null) {
                continue;
            }

            Imagem imagem = IMAGENS_APROVADAS.get(local.getId());

            if (imagem == null) {
                continue;
            }

            local.setImagemUrl(imagem.url());
            local.setImagemFotografo(imagem.fotografo());
            local.setImagemFotografoUrl(imagem.fotografoUrl());
            local.setImagemUnsplashUrl(imagem.paginaUrl());
        }
    }

    private record Imagem(
            String url,
            String fotografo,
            String fotografoUrl,
            String paginaUrl
    ) {}
}
