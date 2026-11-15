package com.maia.backend.mare;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

@Service
public class MareService {

    private static final ZoneOffset FUSO_LITORAL_BRASILEIRO = ZoneOffset.ofHours(-3);
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final RestClient tabuaMareClient;
    private final CostaService costaService;
    private final ObjectMapper objectMapper;

    public MareService(ObjectMapper objectMapper, CostaService costaService) {
        this.objectMapper = objectMapper;
        this.costaService = costaService;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        this.tabuaMareClient = RestClient.builder()
                .baseUrl("https://tabuamare.api.br")
                .requestFactory(factory)
                .build();
    }

    public MareResponse buscarMare(double latitude, double longitude) {
        // Não consultamos marés para destinos a mais de 10 km do litoral.
        if (!costaService.possuiCostaProxima(latitude, longitude)) {
            return MareResponse.indisponivel();
        }

        try {
            String coordenadas = "[" + latitude + "," + longitude + "]";
            String respostaPorto = tabuaMareClient.get()
                    .uri("/api/v2/nearest-harbor-independent-state/{coordenadas}", coordenadas)
                    .retrieve()
                    .body(String.class);
            Map<?, ?> porto = primeiroItem(respostaPorto);
            if (porto == null) {
                return MareResponse.indisponivel();
            }

            String idPorto = obterString(porto, "id");
            String estacao = obterString(porto, "harbor_name");
            Integer anoDados = obterInteger(porto, "year");
            if (idPorto == null || idPorto.isBlank()) {
                return MareResponse.indisponivel();
            }

            LocalDate hoje = LocalDate.now(FUSO_LITORAL_BRASILEIRO);
            LocalDate amanha = hoje.plusDays(1);
            List<MareEventoResponse> eventos = new ArrayList<>();

            // Consultamos hoje e amanhã para localizar a próxima maré mesmo à noite.
            // Se o catálogo ainda não tiver o ano novo, preservamos os dados de hoje.
            consultarDia(idPorto, hoje, anoDados, eventos);
            if (amanha.getYear() == hoje.getYear()) {
                consultarDia(idPorto, amanha, anoDados, eventos);
            }

            if (eventos.isEmpty()) {
                return MareResponse.indisponivel();
            }

            eventos.sort(Comparator.comparing(
                    evento -> OffsetDateTime.parse(evento.dataHora()).toInstant()));

            return MareResponse.disponivel(
                    latitude,
                    longitude,
                    estacao,
                    null, // A API informa a instituição DHN, mas não um datum vertical específico.
                    encontrarProximaMare(eventos, "BAIXA"),
                    encontrarProximaMare(eventos, "ALTA"),
                    eventos
            );
        } catch (Exception e) {
            // Sem créditos, 429, ausência de dados ou falha de rede não devem quebrar a tela.
            System.out.println("Maré temporariamente indisponível: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
            return MareResponse.indisponivel();
        }
    }

    private void consultarDia(String idPorto, LocalDate dia, Integer anoDados,
                             List<MareEventoResponse> eventos) throws Exception {
        if (anoDados != null && anoDados != dia.getYear()) {
            return;
        }

        String dias = "[" + dia.getDayOfMonth() + "]";
        String resposta = tabuaMareClient.get()
                .uri("/api/v2/tabua-mare/{porto}/{mes}/{dias}", idPorto, dia.getMonthValue(), dias)
                .retrieve()
                .body(String.class);

        Map<?, ?> dadosPorto = primeiroItem(resposta);
        if (dadosPorto == null) {
            return;
        }
        Integer anoRetornado = obterInteger(dadosPorto, "year");
        if (anoRetornado != null && anoRetornado != dia.getYear()) {
            return;
        }

        Object mesesObj = dadosPorto.get("months");
        if (!(mesesObj instanceof List<?> meses)) {
            return;
        }

        for (Object mesObj : meses) {
            if (!(mesObj instanceof Map<?, ?> mes) ||
                    !Integer.valueOf(dia.getMonthValue()).equals(obterInteger(mes, "month"))) {
                continue;
            }
            Object diasObj = mes.get("days");
            if (!(diasObj instanceof List<?> diasRetornados)) {
                continue;
            }
            for (Object diaObj : diasRetornados) {
                if (!(diaObj instanceof Map<?, ?> dadosDia) ||
                        !Integer.valueOf(dia.getDayOfMonth()).equals(obterInteger(dadosDia, "day"))) {
                    continue;
                }
                Object horasObj = dadosDia.get("hours");
                if (!(horasObj instanceof List<?> horas)) {
                    continue;
                }

                List<HorarioMare> horarios = new ArrayList<>();
                for (Object horarioObj : horas) {
                    if (!(horarioObj instanceof Map<?, ?> horario)) {
                        continue;
                    }
                    String hora = obterString(horario, "hour");
                    Double nivel = obterDouble(horario, "level");
                    if (hora == null || nivel == null) {
                        continue;
                    }
                    horarios.add(new HorarioMare(LocalTime.parse(hora), nivel));
                }
                horarios.sort(Comparator.comparing(HorarioMare::hora));

                // Os registros da tábua são extremos consecutivos e alternam alta/baixa.
                // Classificamos o primeiro pela altura relativa ao segundo.
                if (horarios.size() < 2) {
                    continue;
                }
                boolean primeiraBaixa = horarios.get(0).nivel() < horarios.get(1).nivel();
                for (int i = 0; i < horarios.size(); i++) {
                    HorarioMare horario = horarios.get(i);
                    String tipo = (primeiraBaixa == (i % 2 == 0)) ? "BAIXA" : "ALTA";
                    String dataHora = OffsetDateTime.of(dia, horario.hora(),
                            FUSO_LITORAL_BRASILEIRO).toString();
                    eventos.add(new MareEventoResponse(tipo, dataHora, horario.nivel()));
                }
            }
        }
    }

    private Map<?, ?> primeiroItem(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<?, ?> resposta = objectMapper.readValue(json, Map.class);
        Object data = resposta.get("data");
        if (!(data instanceof List<?> lista) || lista.isEmpty()) {
            return null;
        }
        Object primeiro = lista.get(0);
        return primeiro instanceof Map<?, ?> mapa ? mapa : null;
    }

    private MareEventoResponse encontrarProximaMare(List<MareEventoResponse> eventos, String tipo) {
        OffsetDateTime agora = OffsetDateTime.now(FUSO_LITORAL_BRASILEIRO);
        MareEventoResponse proxima = null;
        OffsetDateTime horarioProxima = null;
        for (MareEventoResponse evento : eventos) {
            if (!tipo.equalsIgnoreCase(evento.tipo()) || evento.dataHora() == null) {
                continue;
            }
            OffsetDateTime horario = OffsetDateTime.parse(evento.dataHora());
            if (!horario.isAfter(agora)) {
                continue;
            }
            if (horarioProxima == null || horario.isBefore(horarioProxima)) {
                proxima = evento;
                horarioProxima = horario;
            }
        }
        return proxima;
    }

    private static String obterString(Map<?, ?> mapa, String chave) {
        Object valor = mapa.get(chave);
        return valor == null ? null : valor.toString();
    }

    private static Integer obterInteger(Map<?, ?> mapa, String chave) {
        Object valor = mapa.get(chave);
        return valor instanceof Number numero ? numero.intValue() : null;
    }

    private static Double obterDouble(Map<?, ?> mapa, String chave) {
        Object valor = mapa.get(chave);
        return valor instanceof Number numero ? numero.doubleValue() : null;
    }

    private record HorarioMare(LocalTime hora, double nivel) { }
}
