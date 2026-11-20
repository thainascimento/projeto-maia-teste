package com.maia.backend.explora;

import com.maia.backend.local.LocalResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ExploraImagemService {

    /*
     * Fotografias verificadas manualmente.
     *
     * Observacao:
     * o campo imagemUnsplashUrl sera usado temporariamente como
     * pagina da fonte da imagem, mesmo quando a fonte nao for Unsplash.
     */
    private static final Map<String, Imagem> IMAGENS_APROVADAS =
            Map.ofEntries(
                    Map.entry(
                            "51d4d7f335cbdd41c059c0481e3e9f5323c0f00103f90135c19021010000009203154d6972616e74652053c3a36f20476f6ec3a7616c6f",
                            new Imagem(
                                    "https://commons.wikimedia.org/wiki/Special:Redirect/file/Mirante%20S%C3%A3o%20Gon%C3%A7alo,%20Macei%C3%B3,%20Brazil%20(51381741427).jpg",
                                    "Daniel Alves",
                                    "https://commons.wikimedia.org/wiki/File:Mirante_S%C3%A3o_Gon%C3%A7alo,_Macei%C3%B3,_Brazil_(51381741427).jpg",
                                    "https://commons.wikimedia.org/wiki/File:Mirante_S%C3%A3o_Gon%C3%A7alo,_Macei%C3%B3,_Brazil_(51381741427).jpg"
                            )
                    ),
                    Map.entry(
                            "51cf9decbd56dd41c0597491edc8505723c0f00102f901ad34fa33000000009203164d656d6f7269616c20c3a020526570c3ba626c696361",
                            new Imagem(
                                    "https://commons.wikimedia.org/wiki/Special:Redirect/file/Memorial_da_rep%C3%BAblica.maceio.jpg",
                                    "Rodolfo Freire",
                                    "https://commons.wikimedia.org/wiki/File:Memorial_da_rep%C3%BAblica.maceio.jpg",
                                    "https://commons.wikimedia.org/wiki/File:Memorial_da_rep%C3%BAblica.maceio.jpg"
                            )
                    )
            );

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
