package com.maia.backend.geocoding;

public record BairroSugestaoResponse(
        String nome,
        String cidade,
        String estado,
        String pais,
        double latitude,
        double longitude) {
}
