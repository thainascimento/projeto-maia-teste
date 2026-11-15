
package com.maia.backend.mare;

import java.util.List;

public record MareResponse(
        boolean disponivel,
        Double latitude,
        Double longitude,
        String estacao,
        String datum,
        MareEventoResponse proximaMareBaixa,
        MareEventoResponse proximaMareAlta,
        List<MareEventoResponse> eventos,
        String motivoIndisponibilidade
) {
    public static MareResponse indisponivel() {
        return indisponivel("ERRO_CONSULTA");
    }

    public static MareResponse indisponivel(String motivo) {
        return new MareResponse(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                motivo
        );
    }

    public static MareResponse disponivel(
            Double latitude,
            Double longitude,
            String estacao,
            String datum,
            MareEventoResponse proximaMareBaixa,
            MareEventoResponse proximaMareAlta,
            List<MareEventoResponse> eventos
    ) {
        return new MareResponse(
                true,
                latitude,
                longitude,
                estacao,
                datum,
                proximaMareBaixa,
                proximaMareAlta,
                eventos == null ? List.of() : eventos,
                null
        );
    }
}
