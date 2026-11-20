package com.maia.backend.explora;

import com.maia.backend.local.LocalResponse;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class ExploraImagemService {

    private static final Logger LOG = Logger.getLogger(ExploraImagemService.class.getName());
    private static final String API = "https://commons.wikimedia.org/w/api.php";
    private static final String USER_AGENT =
            "MAIAExplora/1.0 (https://github.com/thainascimento/projeto-maia-teste)";

    // Busca geografica conservadora: fotos com coordenadas proximas ao proprio local.
    private static final int RAIO_FOTO_METROS = 200;
    private static final int MAX_CONSULTAS_POR_REQUISICAO = 10;
    private static final Duration CACHE_COM_FOTO = Duration.ofHours(24);
    private static final Duration CACHE_SEM_FOTO = Duration.ofHours(2);

    private static final Set<String> PALAVRAS_IGNORADAS = Set.of(
            "a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "em",
            "na", "no", "nas", "nos", "um", "uma", "the", "of", "at", "in",
            "maceio", "alagoas", "brasil", "brazil", "foto", "imagem", "file",
            "jpg", "jpeg", "png", "webp", "view", "photo", "image"
    );

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, ResultadoCache> cache = new ConcurrentHashMap<>();

    public ExploraImagemService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void adicionarImagens(List<LocalResponse> locais) {
        if (locais == null || locais.isEmpty()) return;

        int consultas = 0;
        for (LocalResponse local : locais) {
            if (local == null || local.getId() == null || local.getNome() == null) continue;
            if (!coordenadasValidas(local)) continue;

            // Fotos ja conferidas continuam disponiveis; novos destinos usam busca automatica.
            Imagem aprovada = fotoAprovada(local.getId());
            if (aprovada != null) {
                aplicarImagem(local, aprovada);
                continue;
            }

            String chave = local.getId();
            ResultadoCache existente = cache.get(chave);
            Imagem imagem;

            if (existente != null && Instant.now().isBefore(existente.expiraEm())) {
                imagem = existente.imagem();
            } else {
                // Limita as chamadas externas para nao travar a resposta do Explora.
                if (consultas >= MAX_CONSULTAS_POR_REQUISICAO) continue;
                consultas++;
                imagem = buscarFotoGeografica(local);
                Duration ttl = imagem == null ? CACHE_SEM_FOTO : CACHE_COM_FOTO;
                cache.put(chave, new ResultadoCache(imagem, Instant.now().plus(ttl)));
            }

            if (imagem == null) continue;
            aplicarImagem(local, imagem);
        }
    }


    private static void aplicarImagem(LocalResponse local, Imagem imagem) {
        local.setImagemUrl(imagem.url());
        local.setImagemFotografo(imagem.autor());
        local.setImagemFotografoUrl(imagem.pagina());
        // Campo legado: guarda pagina Wikimedia Commons, nao um link Unsplash.
        local.setImagemUnsplashUrl(imagem.pagina());
    }

    // Preserva as quatro imagens ja aprovadas, sem exigir cadastro para novos destinos.
    private static Imagem fotoAprovada(String id) {
        return switch (id) {
            case "51593fec3b75dc41c059d03648d27f5823c0f00103f9019f65068801000000920315457374c3a1747561206461204c6962657264616465" ->
                new Imagem("https://commons.wikimedia.org/wiki/Special:Redirect/file/2010-10-30_replica_Statue_of_Liberty_Maceio_Alagoas_Brazil.JPG",
                    "Chris Scott (Cricobr)", "https://commons.wikimedia.org/wiki/File:2010-10-30_replica_Statue_of_Liberty_Maceio_Alagoas_Brazil.JPG");
            case "51391ca732d6d941c059888961e2ea5323c0f00103f9017f9e188701000000920319457374c3a17475612047726163696c69616e6f2052616d6f73" ->
                new Imagem("https://commons.wikimedia.org/wiki/Special:Redirect/file/Graciliano_Ramos_statue.jpg",
                    "Flaviohmg", "https://commons.wikimedia.org/wiki/File:Graciliano_Ramos_statue.jpg");
            case "51d4d7f335cbdd41c059c0481e3e9f5323c0f00103f90135c19021010000009203154d6972616e74652053c3a36f20476f6ec3a7616c6f" ->
                new Imagem("https://commons.wikimedia.org/wiki/Special:Redirect/file/Mirante%20S%C3%A3o%20Gon%C3%A7alo,%20Macei%C3%B3,%20Brazil%20(51381741427).jpg",
                    "Daniel Alves", "https://commons.wikimedia.org/wiki/File:Mirante_S%C3%A3o_Gon%C3%A7alo,_Macei%C3%B3,_Brazil_(51381741427).jpg");
            case "51cf9decbd56dd41c0597491edc8505723c0f00102f901ad34fa33000000009203164d656d6f7269616c20c3a020526570c3ba626c696361" ->
                new Imagem("https://commons.wikimedia.org/wiki/Special:Redirect/file/Memorial_da_rep%C3%BAblica.maceio.jpg",
                    "Rodolfo Freire", "https://commons.wikimedia.org/wiki/File:Memorial_da_rep%C3%BAblica.maceio.jpg");
            default -> null;
        };
    }

    private Imagem buscarFotoGeografica(LocalResponse local) {
        List<String> palavrasLocal = palavrasRelevantes(local.getNome());
        // Nomes genericos como "Pandeiro" nao bastam para identificar uma fotografia.
        if (palavrasLocal.size() < 2) return null;

        String coordenadas = local.getLatitude() + "|" + local.getLongitude();
        String url = API + "?action=query&format=json&formatversion=2"
                + "&generator=geosearch&ggsprimary=all&ggsnamespace=6"
                + "&ggscoord=" + codificar(coordenadas)
                + "&ggsradius=" + RAIO_FOTO_METROS
                + "&ggslimit=50&prop=imageinfo&iiprop=url%7Cextmetadata"
                + "&iiurlwidth=640";

        try {
            HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resposta = httpClient.send(
                    requisicao, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resposta.statusCode() != 200) {
                LOG.fine("Wikimedia Commons respondeu HTTP " + resposta.statusCode());
                return null;
            }

            Map<String, Object> dados = objectMapper.readValue(
                    resposta.body(), new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> paginas = listaDeMapas(
                    mapa(dados.get("query")).get("pages"));

            List<Candidata> candidatas = new ArrayList<>();
            for (Map<String, Object> pagina : paginas) {
                String titulo = texto(pagina.get("title"));
                if (!titulo.matches("(?i).*\\.(jpg|jpeg|png|webp)$")) continue;
                List<String> palavrasFoto = palavrasRelevantes(titulo);
                if (!palavrasFoto.containsAll(palavrasLocal)) continue;

                List<Map<String, Object>> infos = listaDeMapas(pagina.get("imageinfo"));
                if (infos.isEmpty()) continue;
                Map<String, Object> info = infos.get(0);
                Map<String, Object> metadados = mapa(info.get("extmetadata"));
                String autor = limparHtml(texto(mapa(metadados.get("Artist")).get("value")));
                String licenca = limparHtml(texto(mapa(metadados.get("LicenseShortName")).get("value")));
                String link = texto(info.get("descriptionurl"));
                String imagem = texto(info.get("thumburl"));
                if (imagem.isBlank()) imagem = texto(info.get("url"));
                if (autor.isBlank() || licenca.isBlank() || !licencaPermitida(licenca)) continue;
                if (!imagem.startsWith("https://") || !link.startsWith("https://commons.wikimedia.org/")) continue;

                int pontuacao = palavrasFoto.size() - palavrasLocal.size();
                candidatas.add(new Candidata(new Imagem(imagem, autor, link), pontuacao));
            }
            return candidatas.stream()
                    .min(Comparator.comparingInt(Candidata::pontuacao))
                    .map(c -> c.imagem())
                    .orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Nao foi possivel obter imagem para: " + local.getNome(), e);
            return null;
        }
    }

    private static boolean coordenadasValidas(LocalResponse local) {
        return Double.isFinite(local.getLatitude()) && Double.isFinite(local.getLongitude())
                && Math.abs(local.getLatitude()) <= 90 && Math.abs(local.getLongitude()) <= 180
                && !(local.getLatitude() == 0 && local.getLongitude() == 0);
    }

    private static List<String> palavrasRelevantes(String texto) {
        String normalizado = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String[] partes = normalizado.split("[^a-z0-9]+");
        Set<String> unicas = new HashSet<>();
        List<String> palavras = new ArrayList<>();
        for (String parte : partes) {
            if (parte.length() >= 3 && !PALAVRAS_IGNORADAS.contains(parte) && unicas.add(parte)) {
                palavras.add(parte);
            }
        }
        return palavras;
    }

    private static boolean licencaPermitida(String licenca) {
        String l = licenca.toLowerCase(Locale.ROOT).replace('_', '-');
        if (l.contains("-nc") || l.contains("-nd") || l.contains("noncommercial") || l.contains("no derivatives")) return false;
        return l.contains("cc by") || l.contains("cc-by") || l.contains("cc0")
                || l.contains("public domain") || l.contains("dominio publico") || l.equals("pd");
    }

    private static String limparHtml(String valor) {
        return valor.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replaceAll("\\s+", " ").trim();
    }

    private static String texto(Object valor) { return valor == null ? "" : valor.toString(); }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object objeto) {
        return objeto instanceof Map<?, ?> ? (Map<String, Object>) objeto : Map.of();
    }

    private static List<Map<String, Object>> listaDeMapas(Object objeto) {
        if (!(objeto instanceof List<?> lista)) return List.of();
        List<Map<String, Object>> resultado = new ArrayList<>();
        for (Object item : lista) if (item instanceof Map<?, ?>) resultado.add(mapa(item));
        return resultado;
    }

    private record Imagem(String url, String autor, String pagina) {}
    private record Candidata(Imagem imagem, int pontuacao) {}
    private record ResultadoCache(Imagem imagem, Instant expiraEm) {}
}
