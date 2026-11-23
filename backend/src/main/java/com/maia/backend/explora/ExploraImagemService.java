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
    private static final int RAIO_FOTO_METROS = 250;
    private static final int MAX_CONSULTAS_POR_REQUISICAO = 6;
    private static final int MAX_BUSCAS_POR_NOME = 6;
    private static final Duration TEMPO_MAXIMO_IMAGENS = Duration.ofSeconds(10);
    private static final Duration CACHE_COM_FOTO = Duration.ofHours(24);
    private static final Duration CACHE_SEM_FOTO = Duration.ofHours(2);

    private static final Set<String> PALAVRAS_IGNORADAS = Set.of(
            "a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "em",
            "na", "no", "nas", "nos", "um", "uma", "the", "of", "at", "in",
            "foto", "imagem", "file",
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

        long prazo = System.nanoTime() + TEMPO_MAXIMO_IMAGENS.toNanos();
        List<LocalResponse> pendentes = new ArrayList<>();

        // Imagens ja verificadas e respostas positivas em cache nao gastam chamadas.
        for (LocalResponse local : locais) {
            if (local == null || local.getId() == null || local.getNome() == null) continue;
            Imagem aprovada = fotoAprovada(local.getId());
            if (aprovada != null) {
                aplicarImagem(local, aprovada);
                continue;
            }
            if (!coordenadasValidas(local)) continue;
            ResultadoCache porNome = cacheValido("nome:" + local.getId());
            ResultadoCache porGeo = cacheValido("geo:" + local.getId());
            Imagem foto = porNome != null && porNome.imagem() != null ? porNome.imagem()
                    : porGeo != null ? porGeo.imagem() : null;
            if (foto != null) aplicarImagem(local, foto);
            else pendentes.add(local);
        }

        // Primeiro nomes especificos, usando cidade dinamica, nao cidades fixas no codigo.
        int buscasNome = 0;
        for (LocalResponse local : pendentes) {
            if (System.nanoTime() >= prazo || buscasNome >= MAX_BUSCAS_POR_NOME) break;
            if (local.getImagemUrl() != null && !local.getImagemUrl().isBlank()) continue;
            if (palavrasRelevantes(local.getNome()).size() < 2) continue;
            String chave = "nome:" + local.getId();
            if (cacheValido(chave) != null) continue;
            buscasNome++;
            Imagem imagem = buscarFotoPorNome(local);
            armazenar(chave, imagem);
            if (imagem != null) aplicarImagem(local, imagem);
        }

        // Em seguida, busca geografica somente para os pontos ainda sem imagem.
        int buscasGeo = 0;
        for (LocalResponse local : pendentes) {
            if (System.nanoTime() >= prazo || buscasGeo >= MAX_CONSULTAS_POR_REQUISICAO) break;
            if (local.getImagemUrl() != null && !local.getImagemUrl().isBlank()) continue;
            if (palavrasRelevantes(local.getNome()).size() < 2) continue;
            String chave = "geo:" + local.getId();
            if (cacheValido(chave) != null) continue;
            buscasGeo++;
            Imagem imagem = buscarFotoGeografica(local);
            armazenar(chave, imagem);
            if (imagem != null) aplicarImagem(local, imagem);
        }
    }

    private ResultadoCache cacheValido(String chave) {
        ResultadoCache encontrado = cache.get(chave);
        if (encontrado != null && Instant.now().isBefore(encontrado.expiraEm())) {
            return encontrado;
        }
        return null;
    }

    private void armazenar(String chave, Imagem imagem) {
        cache.put(chave, new ResultadoCache(imagem, Instant.now().plus(
                imagem == null ? CACHE_SEM_FOTO : CACHE_COM_FOTO)));
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

    // Busca por nome com prova geografica: coordenadas proximas OU
    // titulo iniciado pelo nome da atracao e municipio identificado.
    private Imagem buscarFotoPorNome(LocalResponse local) {
        List<String> palavrasLocal = palavrasRelevantes(local.getNome());
        if (palavrasLocal.size() < 2) return null;

        // Cidade e contexto opcional, calculado para qualquer municipio do Brasil.
        List<String> palavrasCidade = palavrasRelevantes(texto(local.getCidade()));
        String termos = local.getNome().trim();
        if (!palavrasCidade.isEmpty()
                && !palavrasLocal.containsAll(palavrasCidade)) {
            termos += " " + local.getCidade().trim();
        }
        String url = API + "?action=query&format=json&formatversion=2"
                + "&generator=search&gsrnamespace=6&gsrlimit=20"
                + "&gsrsearch=" + codificar(termos)
                + "&prop=imageinfo%7Ccoordinates&iiprop=url%7Cextmetadata"
                + "&iiurlwidth=640";

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resposta = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resposta.statusCode() != 200) return null;

            Map<String, Object> dados = objectMapper.readValue(resposta.body(),
                    new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> paginas = listaDeMapas(mapa(dados.get("query")).get("pages"));

            for (Map<String, Object> pagina : paginas) {
                String titulo = texto(pagina.get("title"));
                if (!titulo.matches("(?i).*\\.(jpg|jpeg|png|webp)$")) continue;
                List<String> palavrasTitulo = palavrasRelevantes(titulo);
                if (!palavrasTitulo.containsAll(palavrasLocal)) continue;

                boolean cidadeNoTitulo = !palavrasCidade.isEmpty()
                        && palavrasTitulo.containsAll(palavrasCidade);
                List<Map<String, Object>> pontos = listaDeMapas(pagina.get("coordinates"));
                boolean temCoordenadas = false;
                boolean coordenadaProxima = false;
                for (Map<String, Object> ponto : pontos) {
                    Object lat = ponto.get("lat");
                    Object lon = ponto.get("lon");
                    if (lat instanceof Number la && lon instanceof Number lo) {
                        temCoordenadas = true;
                        if (distanciaMetros(local.getLatitude(), local.getLongitude(),
                                la.doubleValue(), lo.doubleValue()) <= 300) {
                            coordenadaProxima = true;
                            break;
                        }
                    }
                }
                // Coordenadas explicitas e distantes invalidam a foto, mesmo na mesma cidade.
                if (temCoordenadas && !coordenadaProxima) continue;
                // Sem coordenadas, aceita apenas titulo que COMECA pelo nome da atracao
                // e menciona a cidade. Exclui fotos de ruas, funerais, objetos de museu etc.
                if (!temCoordenadas && !(cidadeNoTitulo
                        && tituloComecaComNomeDaAtracao(titulo, local.getNome()))) continue;

                List<Map<String, Object>> infos = listaDeMapas(pagina.get("imageinfo"));
                if (infos.isEmpty()) continue;
                Map<String, Object> info = infos.get(0);
                Map<String, Object> metadados = mapa(info.get("extmetadata"));
                String autor = limparHtml(texto(mapa(metadados.get("Artist")).get("value")));
                String licenca = limparHtml(texto(mapa(metadados.get("LicenseShortName")).get("value")));
                String paginaUrl = texto(info.get("descriptionurl"));
                String imagemUrl = texto(info.get("thumburl"));
                if (imagemUrl.isBlank()) imagemUrl = texto(info.get("url"));
                if (autor.isBlank() || !licencaPermitida(licenca)) continue;
                if (!imagemUrl.startsWith("https://")
                        || !paginaUrl.startsWith("https://commons.wikimedia.org/")) continue;
                return new Imagem(imagemUrl, autor, paginaUrl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Busca por nome falhou: " + local.getNome(), e);
        }
        return null;
    }

    // Remove o prefixo File: e compara palavras em ordem, para evitar
    // confundir uma pessoa ou monumento com uma rua, evento ou objeto homonimo.
    private static boolean tituloComecaComNomeDaAtracao(String titulo, String nome) {
        String nomeArquivo = titulo.replaceFirst("(?i)^File:", "")
                .replaceFirst("(?i)\\.(jpg|jpeg|png|webp)$", "");
        List<String> palavrasArquivo = palavrasRelevantes(nomeArquivo);
        List<String> palavrasAtracao = palavrasRelevantes(nome);
        if (palavrasAtracao.size() < 2 || palavrasArquivo.size() < palavrasAtracao.size()) {
            return false;
        }
        return palavrasArquivo.subList(0, palavrasAtracao.size()).equals(palavrasAtracao);
    }

    private static double distanciaMetros(double lat1, double lon1,
                                          double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371008.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static boolean coordenadasValidas(LocalResponse local) {
        return Double.isFinite(local.getLatitude()) && Double.isFinite(local.getLongitude())
                && Math.abs(local.getLatitude()) <= 90 && Math.abs(local.getLongitude()) <= 180
                && !(local.getLatitude() == 0 && local.getLongitude() == 0);
    }

    private static List<String> palavrasRelevantes(String texto) {
        if (texto == null || texto.isBlank()) return List.of();
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
