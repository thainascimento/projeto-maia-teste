import { fetchAutenticado } from '@/services/api';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Linking,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

type Atracao = {
  id: string | number;
  nome: string;
  categoria?: string | null;
  endereco?: string | null;
  bairro?: string | null;
  cidade?: string | null;
  distanciaMetros?: number | null;
  latitude?: number;
  longitude?: number;
  imagemUrl?: string | null;
  imagemFotografo?: string | null;
  imagemFotografoUrl?: string | null;
  imagemUnsplashUrl?: string | null;
};

// A fonte das imagens é o backend: somente fotografias verificadas por ID.
function licencaDaFoto(pagina?: string | null): string {
  if (!pagina) return 'Licença na página da fonte';
  if (pagina.includes('Memorial_da_rep')) return 'CC BY-SA 4.0';
  if (pagina.includes('Mirante_S')) return 'Domínio público (PDM)';
  return 'Ver licença na fonte';
}

function formatarDistancia(valor?: number | null): string {
  if (valor == null) return 'Distância indisponível';
  if (valor < 1000) return `${Math.round(valor)} m de distância`;
  return `${(valor / 1000).toFixed(1).replace('.', ',')} km de distância`;
}

export default function ExploraScreen() {
  const [atracoes, setAtracoes] = useState<Atracao[]>([]);
  const [indice, setIndice] = useState(0);
  const [favoritos, setFavoritos] = useState<Atracao[]>([]);
  const [carregando, setCarregando] = useState(true);
  const [erro, setErro] = useState('');
  const [falhouImagem, setFalhouImagem] = useState(false);
  const atracaoAtual = atracoes[indice];

  async function carregarAtracoes() {
    setCarregando(true);
    setErro('');
    try {
      // Apenas para esta fase de testes: centro de Maceió.
      const resposta = await fetchAutenticado(
        '/maia-explora/atracoes?lat=-9.6658&lon=-35.7353'
      );
      if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`);
      const dados: unknown = await resposta.json();
      if (!Array.isArray(dados)) throw new Error('Resposta inesperada do backend');
      setAtracoes(dados as Atracao[]);
      setIndice(0);
      setFavoritos([]);
    } catch (e) {
      console.warn('Erro ao carregar atrações:', e);
      setErro('Não foi possível carregar as atrações. Verifique sua conexão.');
    } finally {
      setCarregando(false);
    }
  }

  useEffect(() => {
    void carregarAtracoes();
  }, []);

  useEffect(() => {
    // Reativa a imagem ao trocar de atração.
    setFalhouImagem(false);
  }, [atracaoAtual?.id]);

  function escolher(gostou: boolean) {
    if (!atracaoAtual) return;
    if (gostou) {
      setFavoritos((lista) =>
        lista.some((item) => String(item.id) === String(atracaoAtual.id))
          ? lista
          : [...lista, atracaoAtual]
      );
    }
    setIndice((anterior) => anterior + 1);
  }

  return (
    <ScrollView style={styles.tela} contentContainerStyle={styles.conteudo}>
      <Pressable onPress={() => router.back()} style={styles.voltar}>
        <Text style={styles.voltarTexto}>‹ VOLTAR</Text>
      </Pressable>
      <Text style={styles.etiqueta}>MAIA EXPLORA</Text>
      <Text style={styles.titulo}>Descubra seu próximo lugar favorito</Text>
      <Text style={styles.subtitulo}>
        Escolha atrações para o seu roteiro. As fotos disponíveis são verificadas e fornecidas pelo MAIA.
      </Text>
      <View style={styles.localizacao}>
        <Text style={styles.localizacaoTexto}>📍 Explorando Maceió — AL (teste)</Text>
      </View>

      {carregando ? (
        <View style={styles.estado}>
          <ActivityIndicator color="#6d28d9" size="large" />
          <Text style={styles.estadoTexto}>Buscando atrações...</Text>
        </View>
      ) : erro ? (
        <View style={styles.estado}>
          <Text style={styles.estadoTexto}>{erro}</Text>
          <Pressable style={styles.botaoRecarregar} onPress={() => void carregarAtracoes()}>
            <Text style={styles.botaoRecarregarTexto}>TENTAR NOVAMENTE</Text>
          </Pressable>
        </View>
      ) : atracaoAtual ? (
        <>
          <Text style={styles.contador}>LUGAR {indice + 1} DE {atracoes.length}</Text>
          <View style={styles.cartao}>
            {atracaoAtual.imagemUrl && !falhouImagem ? (
              <View>
                <Image
                  source={{ uri: atracaoAtual.imagemUrl }}
                  resizeMode="cover"
                  style={styles.imagem}
                  onError={() => setFalhouImagem(true)}
                />
                <View style={styles.creditoArea}>
                  <Text style={styles.credito} numberOfLines={2}>
                    Foto: {atracaoAtual.imagemFotografo || "Autor não informado"} · {licencaDaFoto(atracaoAtual.imagemUnsplashUrl)}
                  </Text>
                  {!!atracaoAtual.imagemUnsplashUrl && (
                    <Pressable onPress={() => void Linking.openURL(atracaoAtual.imagemUnsplashUrl!)}>
                      <Text style={styles.linkCredito}>Ver fotografia, fonte e licença</Text>
                    </Pressable>
                  )}
                </View>
              </View>
            ) : (
              <View style={styles.semFoto}>
                <Text style={styles.emoji}>🏝️</Text>
                <Text style={styles.semFotoTexto}>Fotografia verificada não disponível</Text>
              </View>
            )}
            <View style={styles.detalhes}>
              <Text style={styles.nome}>{atracaoAtual.nome}</Text>
              <Text style={styles.bairro}>📍 {atracaoAtual.bairro || atracaoAtual.cidade || 'Maceió'}</Text>
              <Text style={styles.distancia}>{formatarDistancia(atracaoAtual.distanciaMetros)}</Text>
              {!!atracaoAtual.endereco && <Text style={styles.endereco}>{atracaoAtual.endereco}</Text>}
            </View>
          </View>
          <View style={styles.acoes}>
            <Pressable style={[styles.acao, styles.descartar]} onPress={() => escolher(false)}>
              <Text style={styles.descartarTexto}>✕ DESCARTAR</Text>
            </Pressable>
            <Pressable style={[styles.acao, styles.gostei]} onPress={() => escolher(true)}>
              <Text style={styles.gosteiTexto}>♥ GOSTEI!</Text>
            </Pressable>
          </View>
        </>
      ) : (
        <View style={styles.estado}>
          <Text style={styles.finalTitulo}>Você explorou todos os lugares! 💜</Text>
          <Text style={styles.estadoTexto}>Selecionados: {favoritos.length} lugares.</Text>
          <Pressable style={styles.botaoRecarregar} onPress={() => void carregarAtracoes()}>
            <Text style={styles.botaoRecarregarTexto}>EXPLORAR NOVAMENTE</Text>
          </Pressable>
        </View>
      )}

      {favoritos.length > 0 && (
        <View style={styles.favoritos}>
          <Text style={styles.favoritosTitulo}>♥ Meus favoritos ({favoritos.length})</Text>
          {favoritos.map((item) => (
            <View key={String(item.id)} style={styles.favoritoItem}>
              <Text style={styles.favoritoNome}>{item.nome}</Text>
              <Text style={styles.favoritoBairro}>{item.bairro || item.cidade || 'Maceió'}</Text>
            </View>
          ))}
          <Text style={styles.aviso}>Favoritos temporários: ainda não são salvos no MySQL.</Text>
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  tela: { flex: 1, backgroundColor: '#f7f7fb' },
  conteudo: { paddingTop: 56, paddingHorizontal: 20, paddingBottom: 75 },
  voltar: { alignSelf: 'flex-start', marginBottom: 22 },
  voltarTexto: { color: '#6d28d9', fontSize: 13, fontWeight: '800' },
  etiqueta: { color: '#6d28d9', fontSize: 12, fontWeight: '900', letterSpacing: 1.5, marginBottom: 8 },
  titulo: { color: '#241b35', fontSize: 28, fontWeight: '900', marginBottom: 10 },
  subtitulo: { color: '#777080', fontSize: 14, lineHeight: 21, marginBottom: 22 },
  localizacao: { alignSelf: 'flex-start', backgroundColor: '#ede5ff', borderRadius: 20, paddingHorizontal: 14, paddingVertical: 10, marginBottom: 22 },
  localizacaoTexto: { color: '#6331b2', fontSize: 13, fontWeight: '700' },
  estado: { backgroundColor: '#fff', padding: 28, borderRadius: 22, alignItems: 'center', marginTop: 15 },
  estadoTexto: { color: '#777080', fontSize: 14, lineHeight: 21, textAlign: 'center', marginTop: 12 },
  contador: { color: '#6d28d9', fontSize: 12, fontWeight: '800', marginBottom: 12 },
  cartao: { backgroundColor: '#fff', borderRadius: 24, overflow: 'hidden', borderWidth: 1, borderColor: '#ece7f3' },
  imagem: { width: '100%', height: 250, backgroundColor: '#e7ddfa' },
  creditoArea: { paddingHorizontal: 12, paddingVertical: 9, backgroundColor: '#f7f3ff' },
  credito: { fontSize: 11, color: '#54486a' },
  linkCredito: { fontSize: 11, color: '#6d28d9', fontWeight: '700', marginTop: 4 },
  semFoto: { height: 245, backgroundColor: '#e7ddfa', alignItems: 'center', justifyContent: 'center', padding: 18 },
  emoji: { fontSize: 55, marginBottom: 12 },
  semFotoTexto: { color: '#5c36a1', fontSize: 13, textAlign: 'center', fontWeight: '700' },
  detalhes: { padding: 20 },
  nome: { color: '#292135', fontSize: 23, fontWeight: '900', marginBottom: 12 },
  bairro: { color: '#6d28d9', fontSize: 14, fontWeight: '700', marginBottom: 8 },
  distancia: { color: '#333', fontSize: 14, fontWeight: '700', marginBottom: 12 },
  endereco: { fontSize: 13, color: '#777080', lineHeight: 19 },
  acoes: { flexDirection: 'row', gap: 12, marginTop: 20 },
  acao: { flex: 1, alignItems: 'center', justifyContent: 'center', borderRadius: 18, paddingVertical: 19 },
  descartar: { backgroundColor: '#fff', borderWidth: 1, borderColor: '#e2dce9' },
  gostei: { backgroundColor: '#6d28d9' },
  descartarTexto: { color: '#856a99', fontSize: 13, fontWeight: '900' },
  gosteiTexto: { color: '#fff', fontSize: 13, fontWeight: '900' },
  favoritos: { marginTop: 30 },
  favoritosTitulo: { color: '#241b35', fontSize: 19, fontWeight: '900', marginBottom: 14 },
  favoritoItem: { backgroundColor: '#fff', borderRadius: 14, padding: 15, marginBottom: 10, borderWidth: 1, borderColor: '#ece7f3' },
  favoritoNome: { fontSize: 15, fontWeight: '800', color: '#342744' },
  favoritoBairro: { fontSize: 12, color: '#777080', marginTop: 5 },
  aviso: { color: '#888', fontSize: 12, lineHeight: 18, marginTop: 8 },
  finalTitulo: { fontSize: 19, fontWeight: '900', color: '#6d28d9', textAlign: 'center' },
  botaoRecarregar: { backgroundColor: '#6d28d9', borderRadius: 14, paddingHorizontal: 20, paddingVertical: 14, marginTop: 20 },
  botaoRecarregarTexto: { fontSize: 12, fontWeight: '900', color: '#fff' },
});

